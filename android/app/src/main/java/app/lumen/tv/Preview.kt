package app.lumen.tv

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The live channel shown in the Live TV screen's preview window after you leave full screen. */
object Preview {
    var channel by mutableStateOf<Channel?>(null)
    /** True while the full-screen player is open (providers often allow only one connection). */
    var pausedForPlayer by mutableStateOf(false)
    /** The full-screen player's running stream, handed over on Back so the preview continues without reloading. */
    var handoff: Triple<Channel, ExoPlayer, Desc>? = null

    fun take(ch: Channel): Pair<ExoPlayer, Desc>? {
        val h = handoff ?: return null
        handoff = null
        if (h.first.id != ch.id || h.first.providerId != ch.providerId) {
            h.second.release(); AppScope.launch { Playback.release(h.third) }; return null
        }
        return h.second to h.third
    }
}

@OptIn(UnstableApi::class)
fun buildExo(ctx: Context): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent("LumenTV/${BuildConfig.VERSION_NAME}")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(90_000)
        .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${Net.token}", "Cookie" to "lumen_device=${Net.token}"))
    val renderers = DefaultRenderersFactory(ctx).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER).setEnableDecoderFallback(true)
    return ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(8_000, 30_000, 1_000, 2_000).build())
        .build()
}

fun mediaItemFor(d: Desc): MediaItem {
    val b = MediaItem.Builder().setUri(Uri.parse(d.url))
    when (d.type) {
        "hls" -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
        "mpegts" -> b.setMimeType(MimeTypes.VIDEO_MP2T)
    }
    return b.build()
}

/** Small live player (TiviMate-style preview). Stops when the app goes to the background or full screen opens. */
@OptIn(UnstableApi::class)
@Composable
fun LivePreview(ch: Channel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) started = false
            if (e == Lifecycle.Event.ON_START) { started = true; Preview.pausedForPlayer = false }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    if (!started || Preview.pausedForPlayer) { Box(modifier.background(Color.Black)); return }

    // Take over the full-screen player's stream if there is one (no reconnect), otherwise start our own.
    val taken = remember(ch.providerId, ch.id) { Preview.take(ch) }
    val exo = remember(ch.providerId, ch.id) { taken?.first ?: buildExo(ctx) }
    var desc by remember(exo) { mutableStateOf(taken?.second) }
    DisposableEffect(exo) {
        onDispose {
            exo.release()
            desc?.let { d -> AppScope.launch { Playback.release(d) } }
        }
    }
    LaunchedEffect(exo) {
        if (desc != null) { exo.playWhenReady = true; return@LaunchedEffect } // already playing (handed over)
        delay(800) // let the full-screen player hang up first
        val d = runCatching { LiveSource(ch).open(0.0) }.getOrNull() ?: return@LaunchedEffect
        desc = d
        exo.setMediaItem(mediaItemFor(d))
        exo.prepare()
        exo.playWhenReady = true
    }
    LaunchedEffect(exo) { while (true) { delay(20_000); desc?.let { Playback.ping(it) } } }
    AndroidView(
        factory = { PlayerView(it).apply { useController = false; isFocusable = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
        update = { it.player = exo },
        modifier = modifier.background(Color.Black),
    )
}
