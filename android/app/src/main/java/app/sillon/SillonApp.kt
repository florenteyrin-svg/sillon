package app.sillon

import android.app.Application
import app.sillon.data.DiskCache
import app.sillon.data.Settings
import app.sillon.data.SillonApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache as CoilDiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class SillonApp : Application(), SingletonImageLoader.Factory {
    lateinit var settings: Settings
    lateinit var api: SillonApi
    lateinit var cache: DiskCache
    lateinit var musicAssistant: app.sillon.data.MusicAssistant

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        api = SillonApi(settings)
        cache = DiskCache(this)
        musicAssistant = app.sillon.data.MusicAssistant(settings, api.http)
        app.sillon.data.Offline.init(this, api)
    }

    // Covers are kept on the phone (500 MB), so lists show them instantly, even offline
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { api.http })) }
        .diskCache { CoilDiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(500L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
