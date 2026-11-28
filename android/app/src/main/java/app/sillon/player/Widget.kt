package app.sillon.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.sillon.R
import app.sillon.data.SillonApi
import app.sillon.ui.MainActivity
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Home screen widget: a spinning record with the cover in the middle, title, quality logo and buttons. */
object Widget {
    private const val ACTION = "app.sillon.widget.ACTION"
    private var lastArt: String? = null
    private var lastBitmap: Bitmap? = null

    fun attach(context: Context, player: Player, api: SillonApi, scope: CoroutineScope) {
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = update(context, p, scope)
        })
        update(context, player, scope)
    }

    private fun intent(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(context, code, Intent(context, Receiver::class.java).setAction(ACTION).putExtra("a", action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun update(context: Context, player: Player, scope: CoroutineScope) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, Receiver::class.java))
        if (ids.isEmpty()) return
        val meta = player.currentMediaItem?.mediaMetadata
        val v = RemoteViews(context.packageName, R.layout.widget_vinyl)
        v.setTextViewText(R.id.w_title, meta?.title ?: "Sillon")
        v.setTextViewText(R.id.w_artist, meta?.artist ?: "")
        val q = qualityLabel(meta?.extras?.getString(Extras.QUALITY))?.uppercase()
        v.setViewVisibility(R.id.w_quality, if (q != null) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.w_quality, q ?: "")
        // The record spins while music plays
        v.setViewVisibility(R.id.w_spin, if (player.isPlaying) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.w_still, if (player.isPlaying) View.GONE else View.VISIBLE)
        v.setImageViewResource(R.id.w_play, if (player.isPlaying) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        lastBitmap?.let { v.setImageViewBitmap(R.id.w_cover, it) }
        v.setOnClickPendingIntent(R.id.w_play, intent(context, "toggle", 1))
        v.setOnClickPendingIntent(R.id.w_prev, intent(context, "prev", 2))
        v.setOnClickPendingIntent(R.id.w_next, intent(context, "next", 3))
        v.setOnClickPendingIntent(R.id.w_album, intent(context, "album", 4))
        v.setOnClickPendingIntent(R.id.w_artist_btn, intent(context, "artist", 5))
        v.setOnClickPendingIntent(R.id.w_disc, PendingIntent.getActivity(context, 6, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        manager.updateAppWidget(ids, v)

        val art = meta?.artworkUri?.toString()
        if (art != null && art != lastArt) {
            lastArt = art
            scope.launch {
                val result = SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context).data(art).size(256).allowHardware(false).build())
                val bmp = result.image?.toBitmap() ?: return@launch
                lastBitmap = circle(bmp)
                update(context, player, scope)
            }
        }
    }

    /** The cover as a round record label. */
    private fun circle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    class Receiver : AppWidgetProvider() {
        override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
            // Waking the playback service makes it refresh the widget
            controller(context) { it.release() }
        }

        override fun onReceive(context: Context, intent: Intent) {
            super.onReceive(context, intent)
            if (intent.action != ACTION) return
            val action = intent.getStringExtra("a") ?: return
            val pending = goAsync()
            controller(context) { c ->
                when (action) {
                    "toggle" -> if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
                    "prev" -> c.seekToPrevious()
                    "next" -> c.seekToNextMediaItem()
                    "album" -> c.sendCustomCommand(PlaybackService.CMD_ALBUM, android.os.Bundle.EMPTY)
                    "artist" -> c.sendCustomCommand(PlaybackService.CMD_ARTIST, android.os.Bundle.EMPTY)
                }
                // let a custom command reach the service before letting go
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
