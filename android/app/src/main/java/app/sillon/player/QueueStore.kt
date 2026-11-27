package app.sillon.player

import android.content.Context
import app.sillon.data.json
import kotlinx.serialization.Serializable
import java.io.File

/** The queue, current track and position, saved on the phone: the app and the car resume with no loading. */
@Serializable
data class SavedQueue(
    val tracks: List<QueueTrack> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

class QueueStore(context: Context) {
    private val file = File(context.filesDir, "queue.json")

    fun load(): SavedQueue? = runCatching { json.decodeFromString<SavedQueue>(file.readText()) }.getOrNull()

    fun save(q: SavedQueue) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(SavedQueue.serializer(), q))
            tmp.renameTo(file)
        }
    }
}
