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
)

/** The app's link to the playback service: commands in, player state out. */
class PlayerConnection(context: Context, private val api: SillonApi) {
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
    }

    private fun refresh() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toQueueTrack() }
        _state.value = PlayerState(
            current = c.currentMediaItem?.toQueueTrack(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: ((c.currentMediaItem?.mediaMetadata?.durationMs) ?: 0L),
            queue = queue,
            index = c.currentMediaItemIndex,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
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
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); refresh() }
    fun skipTo(index: Int) { controller?.seekTo(index, 0); controller?.play() }
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
