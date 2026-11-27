package app.sillon.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File

/** Last answers kept on the phone, so every screen shows at once and refreshes quietly in the background. */
class DiskCache(context: Context) {
    private val dir = File(context.filesDir, "cache").apply { mkdirs() }

    suspend fun <T> read(key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(serializer, File(dir, "$key.json").readText()) }.getOrNull()
    }

    suspend fun <T> write(key: String, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(dir, "$key.json.tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            tmp.renameTo(File(dir, "$key.json"))
        }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
