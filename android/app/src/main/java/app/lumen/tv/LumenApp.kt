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
        AppState.isTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        Themes.apply(Net.prefs.tvTheme, Net.prefs.tvAccent)
        // Coming back to the app after 5+ minutes away asks "Who's watching?" again.
        var awaySince = 0L
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> awaySince = System.currentTimeMillis()
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (awaySince > 0 && AppState.loaded) AppState.profileCheckTick++
                    awaySince = 0
                }
                else -> {}
            }
        })
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Net.http }
        .crossfade(false) // cheaper on TV hardware; the backdrop has its own fade
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(250L * 1024 * 1024).build() }
        .build()
}
