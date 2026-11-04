package app.lumen.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** Sets up the server client and an image loader that sends the device token and caches artwork on disk. */
class LumenApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Net.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Net.http }
        .crossfade(true)
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(250L * 1024 * 1024).build() }
        .build()
}
