package app.sillon.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.sillon.R
import app.sillon.data.SillonApi
import app.sillon.ui.MainActivity
import app.sillon.widget.Artwork
import app.sillon.widget.SpectrumEngine
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vinyl home screen widget (the one from the Finamp fork): the record and the album art turn while music plays
 * (108 pre-rotated frames, 60 fps), the arm follows the song, live spectrum behind the title, progress,
 * quality logo, previous / play / next, Album and Artist.
 */
object Widget {
    private const val ACTION = "app.sillon.widget.ACTION"
    private var art: Bitmap? = null
    private var artKey: String? = null

    private val labelIds: IntArray by lazy {
        IntArray(108) { i -> R.id::class.java.getField("widget_label_$i").getInt(null) }
    }

    fun attach(context: Context, player: Player, api: SillonApi, scope: CoroutineScope) {
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = update(context, p, scope)
        })
        update(context, player, scope)
    }

    private fun action(context: Context, name: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(context, code, Intent(context, Receiver::class.java).setAction(ACTION).putExtra("a", name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openApp(context: Context) = PendingIntent.getActivity(context, 9, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @OptIn(UnstableApi::class)
    fun update(context: Context, player: Player, scope: CoroutineScope) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, Receiver::class.java))
        val playing = player.isPlaying
        // Live spectrum of the player's own audio
        SpectrumEngine.update(context, (player as? ExoPlayer)?.audioSessionId ?: 0, playing, true)
        if (ids.isEmpty()) return

        val meta = player.currentMediaItem?.mediaMetadata
        val v = RemoteViews(context.packageName, R.layout.widget_vinyl)
        v.setTextViewText(R.id.widget_title, meta?.title ?: "Sillon")
        v.setTextViewText(R.id.widget_artist, meta?.artist ?: "")

        v.setViewVisibility(R.id.widget_vinyl_spinning, if (playing) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.widget_vinyl_still, if (playing) View.GONE else View.VISIBLE)
        val duration = player.duration.takeIf { it > 0 }
        val position = player.currentPosition.coerceAtLeast(0)
        val progress = if (duration != null) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val angle = if (playing && meta?.title != null) 4f + (24.6f - 4f) * progress else -4f
        v.setInt(R.id.widget_tonearm, "setImageLevel", ((angle + 4f) / 30f * 10000).toInt().coerceIn(0, 10000))
        if (duration != null) {
            v.setProgressBar(R.id.widget_progress, 1000, (position * 1000 / duration).toInt().coerceIn(0, 1000), false)
            v.setTextViewText(R.id.widget_duration, "%d:%02d".format(duration / 60000, duration / 1000 % 60))
        } else {
            v.setProgressBar(R.id.widget_progress, 1000, 0, false)
            v.setTextViewText(R.id.widget_duration, "")
        }
        v.setChronometer(R.id.widget_elapsed, SystemClock.elapsedRealtime() - position, null, playing)

        val logo = when (meta?.extras?.getString(Extras.QUALITY)) {
            "hires", "atmos" -> R.drawable.widget_quality_hires
            "cd" -> R.drawable.widget_quality_cd
            "lossy" -> R.drawable.widget_quality_mp3
            else -> null
        }
        v.setViewVisibility(R.id.widget_quality, if (logo != null) View.VISIBLE else View.GONE)
        if (logo != null) v.setImageViewResource(R.id.widget_quality, logo)

        // The card takes a dark colour from the album art (it is drawn white and tinted here)
        val a = art
        v.setInt(R.id.widget_background, "setColorFilter", a?.let { Artwork.backgroundColor(it) } ?: Artwork.defaultBackground(context))

        // Album art: still when paused, turning with the record while playing
        if (a != null) v.setImageViewBitmap(R.id.widget_art, Artwork.circle(a)) else v.setImageViewResource(R.id.widget_art, R.drawable.widget_ic_music)
        val frames = if (playing && a != null) Artwork.rotatedLabelFiles(context, artKey ?: "", a, labelIds.size, 144) else null
        v.setViewVisibility(R.id.widget_label_spinning, if (frames != null) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.widget_art, if (frames != null) View.GONE else View.VISIBLE)
        frames?.forEachIndexed { i, uri -> v.setImageViewUri(labelIds[i], uri) }

        v.setImageViewResource(R.id.widget_play, if (playing) R.drawable.widget_ic_pause else R.drawable.widget_ic_play)
        v.setOnClickPendingIntent(R.id.widget_play, action(context, "toggle", 1))
        v.setOnClickPendingIntent(R.id.widget_skip_previous, action(context, "prev", 2))
        v.setOnClickPendingIntent(R.id.widget_skip_next, action(context, "next", 3))
        v.setOnClickPendingIntent(R.id.widget_btn_album, action(context, "album", 4))
        v.setOnClickPendingIntent(R.id.widget_btn_artist, action(context, "artist", 5))
        v.setOnClickPendingIntent(R.id.widget_spectrum, action(context, "spectrum", 6))
        v.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        v.setOnClickPendingIntent(R.id.widget_tonearm, openApp(context))
        manager.updateAppWidget(ids, v)

        val url = meta?.artworkUri?.toString()
        if (url != null && url != artKey) {
            artKey = url
            scope.launch {
                val result = SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context).data(url).size(300).allowHardware(false).build())
                val bmp = result.image?.toBitmap() ?: return@launch
                art = bmp
                withContext(Dispatchers.IO) { Artwork.rotatedLabelFiles(context, url, bmp, labelIds.size, 144) }
                update(context, player, scope)
            }
        }
    }

    class Receiver : AppWidgetProvider() {
        override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
            // Waking the playback service makes it draw the widget
            controller(context) { it.release() }
        }

        override fun onReceive(context: Context, intent: Intent) {
            super.onReceive(context, intent)
            if (intent.action != ACTION) return
            val name = intent.getStringExtra("a") ?: return
            if (name == "spectrum") { SpectrumEngine.nextStyle(context); return }
            val pending = goAsync()
            controller(context) { c ->
                when (name) {
                    "toggle" -> if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
                    "prev" -> c.seekToPrevious()
                    "next" -> c.seekToNextMediaItem()
                    "album" -> c.sendCustomCommand(PlaybackService.CMD_ALBUM, android.os.Bundle.EMPTY)
                    "artist" -> c.sendCustomCommand(PlaybackService.CMD_ARTIST, android.os.Bundle.EMPTY)
                }
                android.os.Handler(context.mainLooper).postDelayed({ c.release(); pending.finish() }, 800)
            }
        }

        private fun controller(context: Context, block: (MediaController) -> Unit) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({ runCatching { block(future.get()) } }, MoreExecutors.directExecutor())
        }
    }
}
