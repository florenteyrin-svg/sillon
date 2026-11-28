package app.sillon.ui

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.sillon.data.SillonApi
import app.sillon.player.PlaybackService
import app.sillon.player.QueueTrack
import app.sillon.player.toMediaItem
import app.sillon.player.isTransition
import app.sillon.player.toQueueTrack
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlayerState(
    val current: QueueTrack? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<QueueTrack> = emptyList(),
    val index: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val remote: String? = null, // Music Assistant player the music was sent to
)

/** The app's link to the playback service: commands in, player state out. */
class PlayerConnection(context: Context, private val api: SillonApi, private val ma: app.sillon.data.MusicAssistant? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get().also { c ->
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = refresh()
                })
            }
            refresh()
        }, MoreExecutors.directExecutor())
        scope.launch {
            while (true) {
                delay(500)
                if (controller?.isPlaying == true) refresh()
            }
        }
        // Remote: while the music plays on Music Assistant, the app shows (and follows) what it plays
        scope.launch {
            while (true) {
                delay(2000)
                val p = MaState.activePlayer ?: continue
                val now = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ma?.nowPlaying(p.id) } }.getOrNull() ?: continue
                remotePlaying = now.playing
                remotePositionMs = (now.positionSec * 1000).toLong()
                val c = controller
                if (c != null) {
                    val idx = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == now.songId }
                    if (idx != null && idx != c.currentMediaItemIndex) c.seekTo(idx, remotePositionMs)
                    if (c.isPlaying) c.pause()
                }
                refresh()
            }
        }
    }

    private var remotePlaying = false
    private var remotePositionMs = 0L

    private fun remote(block: suspend (app.sillon.data.MusicAssistant, String) -> Unit): Boolean {
        val p = MaState.activePlayer ?: return false
        val m = ma ?: return false
        scope.launch { runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block(m, p.id) } } }
        return true
    }

    private fun refresh() {
        val c = controller ?: return
        // DJ transitions are part of the player's queue but not shown as songs
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        val queue = items.filter { !it.isTransition }.map { it.toQueueTrack() }
        val index = items.take(c.currentMediaItemIndex.coerceAtLeast(0)).count { !it.isTransition }
        _state.value = PlayerState(
            current = c.currentMediaItem?.toQueueTrack(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: ((c.currentMediaItem?.mediaMetadata?.durationMs) ?: 0L),
            queue = queue,
            index = index,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        ).let { st ->
            val p = MaState.activePlayer
            if (p == null) st else st.copy(remote = p.name, isPlaying = remotePlaying, isBuffering = false, positionMs = remotePositionMs)
        }
    }

    fun play(tracks: List<QueueTrack>, start: Int = 0, shuffle: Boolean = false) {
        val c = controller ?: return
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(tracks.map<QueueTrack, MediaItem> { it.toMediaItem(api) }, start.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)), 0)
        c.prepare()
        c.play()
    }

    fun playNext(track: QueueTrack) {
        val c = controller ?: return
        val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
        c.addMediaItem(at, track.toMediaItem(api))
        if (c.mediaItemCount == 1) { c.prepare(); c.play() }
    }

    fun addToQueue(track: QueueTrack) {
        val c = controller ?: return
        c.addMediaItem(track.toMediaItem(api))
        if (c.mediaItemCount == 1) { c.prepare(); c.play() }
    }

    fun toggle() {
        if (remote { m, id -> m.command(id, "play_pause"); remotePlaying = !remotePlaying }) return
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /** "album" or "artist": queued right after the playing song (handled by the playback service). */
    fun queueAfter(kind: String, onDone: (Boolean) -> Unit) {
        val c = controller ?: return
        val cmd = if (kind == "album") PlaybackService.CMD_ALBUM else PlaybackService.CMD_ARTIST
        val f = c.sendCustomCommand(cmd, android.os.Bundle.EMPTY)
        f.addListener({ onDone(runCatching { f.get().resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS }.getOrDefault(false)) },
            MoreExecutors.directExecutor())
    }

    fun pause() { controller?.pause() }
    fun play() { controller?.play() }

    /** Jumps to a song of the queue (by id) at a position: used to take the music back from Music Assistant. */
    fun resumeAt(songId: String?, positionMs: Long) {
        val c = controller ?: return
        val idx = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == songId }
        if (idx != null) c.seekTo(idx, positionMs) else c.seekTo(positionMs)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun next() { if (!remote { m, id -> m.command(id, "next") }) controller?.seekToNextMediaItem() }
    fun previous() { if (!remote { m, id -> m.command(id, "previous") }) controller?.seekToPrevious() }
    fun seekTo(ms: Long) {
        if (remote { m, id -> m.seek(id, ms / 1000); remotePositionMs = ms }) return
        controller?.seekTo(ms); refresh()
    }
    /** [index] counts songs only (DJ transitions are skipped). */
    fun skipTo(index: Int) {
        val c = controller ?: return
        val real = (0 until c.mediaItemCount).filter { !c.getMediaItemAt(it).isTransition }.getOrNull(index) ?: return
        c.seekTo(real, 0); c.play()
    }
    fun setShuffle(on: Boolean) { controller?.shuffleModeEnabled = on }
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}
