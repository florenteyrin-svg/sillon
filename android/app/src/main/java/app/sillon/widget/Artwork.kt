package app.sillon.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/** Album art helpers for the widgets: loading, rounding and picking a background colour. */
object Artwork {
    private const val TAG = "SillonWidget"

    /** Loads the square album art at [path], scaled to at most [sizePx] (RemoteViews have a bitmap memory limit). */
    fun load(path: String?, sizePx: Int): Bitmap? {
        if (path.isNullOrEmpty() || sizePx <= 0) return null
        val file = File(path)
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
            val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            squareCrop(decoded, sizePx)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load album art $path", e)
            null
        }
    }

    private fun squareCrop(source: Bitmap, sizePx: Int): Bitmap {
        val side = min(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val target = min(side, sizePx)
        return if (target == side) cropped else Bitmap.createScaledBitmap(cropped, target, target, true)
    }

    /** Rounds the corners of [source], which is shown [shownSizePx] wide, by [radiusPx] on screen. */
    fun rounded(source: Bitmap, radiusPx: Float, shownSizePx: Int): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        // The bitmap may be smaller than it is shown: scale the radius to keep the same corners on screen.
        val radius = radiusPx * source.width / max(1, shownSizePx)
        Canvas(output).drawRoundRect(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), radius, radius, paint)
        return output
    }

    /** The art cut into a circle, as the label of the record. */
    fun circle(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radius = min(source.width, source.height) / 2f
        Canvas(output).drawCircle(source.width / 2f, source.height / 2f, radius, paint)
        return output
    }

    /**
     * [count] PNG files of the art as a round label, each turned 360/[count] degrees further, [sizePx] wide, as
     * content:// addresses the launcher can read (served by FrameProvider, which serves
     * local files by path). Made once per album art; older frames are deleted.
     */
    private fun frameFiles(context: Context, key: String, count: Int): List<File> {
        // "v2": frames made by older versions could hold the previous song's art under this song's name
        val name = "v2_" + Integer.toHexString(key.hashCode())
        return List(count) { i -> File(File(context.cacheDir, "widget_label_frames"), "${name}_${count}_$i.png") }
    }

    private fun uris(context: Context, files: List<File>) =
        files.map { Uri.Builder().scheme("content").authority(context.packageName).path(it.absolutePath).build() }

    /** The frames of this art if they are already made (never makes them: that is done off the main thread). */
    fun readyLabelFiles(context: Context, key: String, count: Int): List<Uri>? =
        frameFiles(context, key, count).takeIf { f -> f.all { it.isFile } }?.let { uris(context, it) }

    fun rotatedLabelFiles(context: Context, key: String, source: Bitmap, count: Int, sizePx: Int): List<Uri>? {
        return try {
            val files = frameFiles(context, key, count)
            val dir = files.first().parentFile!!
            if (!files.all { it.isFile }) {
                dir.deleteRecursively()
                dir.mkdirs()
                val label = circle(Bitmap.createScaledBitmap(source, sizePx, sizePx, true))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                files.forEachIndexed { i, file ->
                    val frame = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    Canvas(frame).apply {
                        rotate(i * 360f / count, sizePx / 2f, sizePx / 2f)
                        drawBitmap(label, 0f, 0f, paint)
                    }
                    FileOutputStream(file).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    frame.recycle()
                }
            }
            uris(context, files)
        } catch (e: Exception) {
            Log.w(TAG, "Could not make the spinning label", e)
            null
        }
    }

    /** A dark colour taken from the art, so white text stays readable on it. */
    fun backgroundColor(art: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(art, 16, 16, true)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0L
        for (x in 0 until small.width) {
            for (y in 0 until small.height) {
                val c = small.getPixel(x, y)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                count++
            }
        }
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / count).toInt(), (g / count).toInt(), (b / count).toInt(), hsv)
        hsv[1] = min(hsv[1] * 1.1f, 0.75f)
        hsv[2] = min(hsv[2], 0.32f).coerceAtLeast(0.14f)
        return Color.HSVToColor(hsv)
    }

    /** Material You colour on Android 12+, a neutral dark grey before. */
    fun defaultBackground(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getColor(android.R.color.system_accent1_800)
        } else {
            Color.rgb(0x1C, 0x1C, 0x1E)
        }
}
