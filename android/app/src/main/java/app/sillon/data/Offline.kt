package app.sillon.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import app.sillon.player.QueueTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/**
 * Songs of the server kept on the phone, to play without network (the phone-in-a-circle button).
 * Files are copied as they are on the server (same quality), in the app's own storage; the phone plays
 * them instead of the server whenever they are there.
 */
object Offline {
    private lateinit var dir: File
    private lateinit var api: SillonApi
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<QueueTrack>(Channel.UNLIMITED)

    /** Songs on the phone, by id (newest last). */
    val tracks = mutableStateMapOf<String, QueueTrack>()
    /** Songs waiting or downloading. */
    val pending = mutableStateMapOf<String, Boolean>()

    fun init(context: Context, api: SillonApi) {
        this.api = api
        dir = File(context.filesDir, "offline").apply { mkdirs() }
        runCatching {
            json.decodeFromString(ListSerializer(QueueTrack.serializer()), File(dir, "index.json").readText())
        }.getOrNull()?.forEach { t -> if (file(t.id).isFile) tracks[t.id] = t }
        scope.launch { for (t in queue) fetch(t) }
    }

    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    fun has(id: String) = id in tracks

    /** The file to play instead of the server, when the song is on the phone. */
    fun uri(id: String): String? = if (::dir.isInitialized && id in tracks) Uri.fromFile(file(id)).toString() else null

    fun download(list: List<QueueTrack>) {
        list.filter { !it.isCatalog && it.id !in tracks && it.id !in pending }.forEach {
            pending[it.id] = true
            queue.trySend(it)
        }
    }

    fun remove(ids: Collection<String>) {
        ids.forEach { id -> tracks.remove(id); file(id).delete() }
        save()
    }

    fun bytesUsed(): Long = dir.listFiles()?.filter { it.name != "index.json" }?.sumOf { it.length() } ?: 0L

    private fun fetch(t: QueueTrack) {
        val dest = file(t.id)
        val part = File(dir, dest.name + ".part")
        val ok = runCatching {
            api.http.newBuilder().readTimeout(2, java.util.concurrent.TimeUnit.MINUTES).build()
                .newCall(Request.Builder().url(api.streamUrl(t.id)).build()).execute().use { r ->
                    if (!r.isSuccessful) error("server ${r.code}")
                    part.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
            part.renameTo(dest)
        }.getOrDefault(false)
        if (!ok) part.delete()
        scope.launch(Dispatchers.Main) {
            if (ok) { tracks[t.id] = t; save() }
            pending.remove(t.id)
        }
    }

    @Synchronized
    private fun save() {
        val list = tracks.values.toList()
        scope.launch {
            runCatching { File(dir, "index.json").writeText(json.encodeToString(ListSerializer(QueueTrack.serializer()), list)) }
        }
    }
}
