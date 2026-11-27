package app.sillon.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.sillon.data.MixTransition
import app.sillon.data.Settings
import app.sillon.data.SillonApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DJ mix on the phone side. The server prepares each transition (end of A + start of B, mixed); here the
 * queue becomes  A (cut at aOut) -> transition -> B (from bIn), so the three files play back to back.
 *
 * Transitions are asked for the playing song and the next one in advance. When the next song's transition
 * is ready before that song starts, its end is clipped in the queue (seamless); for the song already playing
 * the player jumps to the transition at aOut.
 */
class DjMixer(
    private val player: Player,
    private val api: SillonApi,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val ready = HashMap<String, MixTransition>()
    private val askedAt = HashMap<String, Long>()
    private val failed = HashSet<String>()
    private var cut: Pair<String, Long>? = null // song playing, position (ms) where to jump to its transition

    private fun pairKey(a: MediaItem, b: MediaItem) = "${a.mediaId}>${b.mediaId}"

    private fun mixable(item: MediaItem): Boolean =
        !item.isTransition && !item.mediaId.startsWith(CATALOG_PREFIX) &&
            (item.mediaMetadata.extras?.getBoolean(Extras.MIXABLE) ?: false)

    /** Called twice a second by the playback service. */
    fun tick() {
        if (!settings.djEnabled || player.shuffleModeEnabled) {
            if (hasDjItems()) undo()
            return
        }
        val cur = player.currentMediaItemIndex
        if (cur < 0 || player.mediaItemCount == 0) return

        cut?.let { (id, ms) ->
            if (player.currentMediaItem?.mediaId != id) cut = null
            else if (player.currentPosition >= ms && cur + 1 < player.mediaItemCount && player.getMediaItemAt(cur + 1).isTransition) {
                cut = null
                player.seekTo(cur + 1, 0)
                return
            }
        }

        // The playing song and the next two songs (transition items skipped)
        val songs = (cur until player.mediaItemCount).filter { !player.getMediaItemAt(it).isTransition }.take(3)
        for (k in 0 until songs.size - 1) consider(songs[k], songs[k + 1], cur)
    }

    private fun consider(x: Int, y: Int, cur: Int) {
        if (y != x + 1) return // a transition already sits between them
        val a = player.getMediaItemAt(x)
        val b = player.getMediaItemAt(y)
        if (!mixable(a) || !mixable(b)) return
        val key = pairKey(a, b)
        if (key in failed) return
        val t = ready[key]
        if (t == null || t.status != "done") {
            ask(key, a.mediaId, b.mediaId)
            return
        }
        apply(x, y, a, b, t, x == cur)
    }

    private fun ask(key: String, from: String, to: String) {
        val now = System.currentTimeMillis()
        if (now - (askedAt[key] ?: 0) < 4000) return
        askedAt[key] = now
        scope.launch {
            val t = runCatching { withContext(Dispatchers.IO) { api.mixTransition(from, to) } }.getOrNull() ?: return@launch
            when (t.status) {
                "done" -> ready[key] = t
                "failed" -> failed += key
            }
        }
    }

    private fun apply(x: Int, y: Int, a: MediaItem, b: MediaItem, t: MixTransition, aIsPlaying: Boolean) {
        val aOutMs = (t.aOut * 1000).toLong()
        val bInMs = (t.bIn * 1000).toLong()
        val url = t.url ?: return
        // Too late for the song playing: it is already past the point where the transition starts
        if (aIsPlaying && player.currentPosition > aOutMs - 1500) return

        val extras = Bundle(b.mediaMetadata.extras ?: Bundle()).apply { putBoolean(Extras.TRANSITION, true) }
        // The transition shows the next song (it is already playing in the mix)
        val transition = MediaItem.Builder()
            .setMediaId(MIX_PREFIX + pairKey(a, b))
            .setUri(api.serverUrl(url))
            .setMediaMetadata(b.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
        player.addMediaItem(y, transition)

        val bClip = b.clippingConfiguration.buildUpon().setStartPositionMs(bInMs).build()
        swap(y + 1, b.buildUpon().setClippingConfiguration(bClip).build())

        if (aIsPlaying) {
            cut = a.mediaId to aOutMs
        } else {
            val aClip = a.clippingConfiguration.buildUpon().setEndPositionMs(aOutMs).build()
            swap(x, a.buildUpon().setClippingConfiguration(aClip).build())
        }
    }

    /**
     * Puts a new version of a queued song in place. replaceMediaItem() would keep the old source when the
     * file is the same and ignore the new start/end points (the song then restarted from 0): remove + add
     * makes the player build a clipped source.
     */
    private fun swap(index: Int, item: MediaItem) {
        player.removeMediaItem(index)
        player.addMediaItem(index, item)
    }

    private fun hasDjItems(): Boolean = (0 until player.mediaItemCount).any {
        val item = player.getMediaItemAt(it)
        item.isTransition || item.clippingConfiguration != MediaItem.ClippingConfiguration.UNSET
    }

    /** DJ mix switched off: back to whole songs (the one playing is left as it is). */
    fun undo() {
        cut = null
        val cur = player.currentMediaItemIndex
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i == cur) continue
            val item = player.getMediaItemAt(i)
            if (item.isTransition) {
                player.removeMediaItem(i)
            } else if (item.clippingConfiguration != MediaItem.ClippingConfiguration.UNSET) {
                swap(i, item.buildUpon().setClippingConfiguration(MediaItem.ClippingConfiguration.UNSET).build())
            }
        }
    }
}
