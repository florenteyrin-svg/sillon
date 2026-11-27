package app.sillon.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.sillon.data.Settings
import app.sillon.data.SillonApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * DJ mix on the phone side.
 *
 * The server prepares each transition (tempo glide, beats matched, voices swapped) and then serves every
 * song of the mix as ONE continuous file: [transition that brings it in] + the song, up to the exact point
 * where the transition to the next song starts. The player only goes from one file to the next, all in the
 * same format: no jump inside a song, no cut between songs.
 *
 * A queued song is rebuilt as such a "segment" once the transitions around it are ready, always ahead of
 * time (never the song playing, never the next one when it is about to start). The song playing when the
 * mix is switched on keeps its normal end: the mix starts from the next one.
 */
class DjMixer(
    private val player: Player,
    private val api: SillonApi,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val done = HashSet<String>() // "A>B" transitions ready on the server
    private val failed = HashSet<String>()
    private val askedAt = HashMap<String, Long>()

    private fun song(item: MediaItem) = item.mediaId
    private fun builtPrev(item: MediaItem) = item.mediaMetadata.extras?.getString(Extras.DJ_PREV) ?: ""
    private fun builtNext(item: MediaItem) = item.mediaMetadata.extras?.getString(Extras.DJ_NEXT) ?: ""

    private fun mixable(item: MediaItem): Boolean =
        !item.mediaId.startsWith(CATALOG_PREFIX) && (item.mediaMetadata.extras?.getBoolean(Extras.MIXABLE) ?: false)

    /** Called twice a second by the playback service. */
    fun tick() {
        if (!settings.djEnabled || player.shuffleModeEnabled) {
            undo()
            return
        }
        val cur = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (cur < 0 || count == 0) return

        // Ask for the transitions of the next songs (the song playing keeps its end unless it was built mixed)
        val first = if (builtNext(player.getMediaItemAt(cur)).isNotEmpty()) cur else cur + 1
        for (k in first until minOf(count - 1, cur + 4)) {
            val a = player.getMediaItemAt(k)
            val b = player.getMediaItemAt(k + 1)
            if (mixable(a) && mixable(b)) ask(song(a), song(b))
        }

        // Rebuild the queued songs whose surroundings changed
        val remaining = player.duration.takeIf { it > 0 }?.minus(player.currentPosition) ?: Long.MAX_VALUE
        for (k in cur + 1 until minOf(count, cur + 4)) {
            if (k == cur + 1 && remaining < 25_000) continue // about to start: leave it as it is
            val item = player.getMediaItemAt(k)
            if (!mixable(item)) continue
            val before = player.getMediaItemAt(k - 1)
            val prev = if (builtNext(before) == song(item)) song(before) else ""
            val after = if (k + 1 < count) player.getMediaItemAt(k + 1) else null
            val next = if (after != null && mixable(after) && "${song(item)}>${song(after)}" in done) song(after) else ""
            if (prev != builtPrev(item) || next != builtNext(item)) rebuild(k, item, prev, next)
        }
    }

    private fun ask(from: String, to: String) {
        val key = "$from>$to"
        if (key in done || key in failed) return
        val now = System.currentTimeMillis()
        if (now - (askedAt[key] ?: 0) < 5000) return
        askedAt[key] = now
        scope.launch {
            val t = runCatching { withContext(Dispatchers.IO) { api.mixTransition(from, to) } }.getOrNull() ?: return@launch
            when (t.status) {
                "done" -> done += key
                "failed" -> failed += key
            }
        }
    }

    private fun rebuild(index: Int, item: MediaItem, prev: String, next: String) {
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            putString(Extras.DJ_PREV, prev)
            putString(Extras.DJ_NEXT, next)
        }
        val meta = item.mediaMetadata.buildUpon().setExtras(extras).build()
        val uri = if (prev.isEmpty() && next.isEmpty()) api.streamUrl(song(item)) else api.segmentUrl(song(item), prev, next)
        val rebuilt = MediaItem.Builder().setMediaId(item.mediaId).setUri(uri).setMediaMetadata(meta).build()
        // remove + add: replaceMediaItem() keeps the old source when it thinks nothing important changed
        player.removeMediaItem(index)
        player.addMediaItem(index, rebuilt)
        // Let the server render it now, so it is ready long before it plays
        if (prev.isNotEmpty() || next.isNotEmpty()) scope.launch(Dispatchers.IO) {
            runCatching {
                api.http.newCall(Request.Builder().url(uri).header("Range", "bytes=0-0").build()).execute().close()
            }
        }
    }

    /** DJ mix off: queued songs go back to the original files (the song playing is left as it is). */
    fun undo() {
        val cur = player.currentMediaItemIndex
        for (k in 0 until player.mediaItemCount) {
            if (k == cur) continue
            val item = player.getMediaItemAt(k)
            if (builtPrev(item).isNotEmpty() || builtNext(item).isNotEmpty()) rebuild(k, item, "", "")
        }
    }
}
