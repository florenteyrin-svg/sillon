package app.sillon.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sillon.R
import app.sillon.player.QueueTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Songs of the server kept on the phone, to play without network (the phone-in-a-circle button).
 * Files are copied as they are on the server (same quality), in the app's own storage; the phone plays
 * them instead of the server whenever they are there.
 *
 * Downloads run in an Android background job with a progress notification: they go on when the app is
 * closed, resume where they stopped (partial files are kept) and each song is tried 3 times.
 */
object Offline {
    private lateinit var dir: File
    private lateinit var api: SillonApi
    private lateinit var appContext: Context
    private val json = Json { ignoreUnknownKeys = true }
    private val waiting = ArrayList<QueueTrack>() // guarded by this

    /** Songs on the phone, by id. */
    val tracks = mutableStateMapOf<String, QueueTrack>()
    /** Songs waiting or downloading. */
    val pending = mutableStateMapOf<String, Boolean>()
    /** Songs that could not be downloaded (server unreachable...): the button offers to try again. */
    val failed = mutableStateMapOf<String, QueueTrack>()

    data class Progress(val id: String, val title: String, val done: Int, val count: Int, val bytes: Long, val total: Long) {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    /** The song downloading now, with its bytes. */
    val progress = mutableStateOf<Progress?>(null)
    private var batchDone = 0

    fun init(context: Context, api: SillonApi) {
        this.api = api
        appContext = context.applicationContext
        dir = File(context.filesDir, "offline").apply { mkdirs() }
        read("index.json")?.forEach { t -> if (file(t.id).isFile) tracks[t.id] = t }
        read("queue.json")?.filter { it.id !in tracks }?.let { q ->
            synchronized(this) { waiting.addAll(q) }
            q.forEach { pending[it.id] = true }
            if (q.isNotEmpty()) start()
        }
    }

    private fun read(name: String) = runCatching {
        json.decodeFromString(ListSerializer(QueueTrack.serializer()), File(dir, name).readText())
    }.getOrNull()

    private fun write(name: String, list: List<QueueTrack>) = runCatching {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(QueueTrack.serializer()), list))
        tmp.renameTo(File(dir, name))
    }

    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    fun has(id: String) = id in tracks

    /** The file to play instead of the server, when the song is on the phone. */
    fun uri(id: String): String? = if (::dir.isInitialized && id in tracks) Uri.fromFile(file(id)).toString() else null

    fun download(list: List<QueueTrack>) {
        val add = list.filter { !it.isCatalog && it.id !in tracks && it.id !in pending }
        if (add.isEmpty()) return
        add.forEach { pending[it.id] = true; failed.remove(it.id) }
        synchronized(this) { waiting.addAll(add); write("queue.json", waiting.toList()) }
        start()
    }

    private fun start() {
        WorkManager.getInstance(appContext).enqueueUniqueWork("offline",
            ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<Worker>().build())
    }

    fun remove(ids: Collection<String>) {
        ids.forEach { id -> tracks.remove(id); file(id).delete(); failed.remove(id) }
        write("index.json", tracks.values.toList())
    }

    fun bytesUsed(): Long = dir.listFiles()?.filter { !it.name.endsWith(".json") }?.sumOf { it.length() } ?: 0L

    /** Downloads the queue, one song after the other, until it is empty. */
    private suspend fun runQueue(notify: suspend (Progress) -> Unit) {
        while (true) {
            val t = synchronized(this) { waiting.firstOrNull() } ?: break
            val count = synchronized(this) { batchDone + waiting.size }
            var ok = false
            for (attempt in 1..3) {
                ok = runCatching { fetch(t, count, notify) }.isSuccess
                if (ok) break
                kotlinx.coroutines.delay(3000L * attempt)
            }
            batchDone++
            synchronized(this) { waiting.remove(t); write("queue.json", waiting.toList()) }
            withContext(Dispatchers.Main) {
                if (ok) { tracks[t.id] = t; write("index.json", tracks.values.toList()) } else failed[t.id] = t
                pending.remove(t.id)
            }
        }
        batchDone = 0
        withContext(Dispatchers.Main) { progress.value = null }
    }

    private suspend fun fetch(t: QueueTrack, count: Int, notify: suspend (Progress) -> Unit) {
        val dest = file(t.id)
        val part = File(dir, dest.name + ".part")
        val have = if (part.isFile) part.length() else 0L
        val req = Request.Builder().url(api.streamUrl(t.id)).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        api.http.newBuilder().readTimeout(2, TimeUnit.MINUTES).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("server ${r.code}")
            val resumed = r.code == 206
            var bytes = if (resumed) have else 0L
            val total = bytes + (r.body?.contentLength()?.takeIf { it > 0 } ?: 0L)
            var last = 0L
            FileOutputStream(part, resumed).use { out ->
                val input = r.body!!.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    bytes += n
                    val now = System.currentTimeMillis()
                    if (now - last > 400) {
                        last = now
                        val p = Progress(t.id, "${t.artist} — ${t.title}", batchDone, count, bytes, total)
                        withContext(Dispatchers.Main) { progress.value = p }
                        notify(p)
                    }
                }
            }
            if (total > 0 && bytes < total) error("incomplete")
        }
        if (!part.renameTo(dest)) error("rename")
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        private val channel = "offline"

        private fun info(p: Progress?): ForegroundInfo {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channel) == null) {
                nm.createNotificationChannel(NotificationChannel(channel, "Téléchargements sur le téléphone", NotificationManager.IMPORTANCE_LOW))
            }
            val n = NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (p == null) "Téléchargement sur le téléphone" else "Sur le téléphone : ${p.done + 1} / ${p.count}")
                .setContentText(p?.title ?: "Préparation…")
                .setProgress(1000, ((p?.fraction ?: 0f) * 1000).toInt(), p == null || p.total == 0L)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .build()
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(42, n)
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = info(null)

        override suspend fun doWork(): Result {
            runCatching { setForeground(info(null)) }
            runQueue { p -> runCatching { setForeground(info(p)) } }
            return Result.success()
        }
    }
}
