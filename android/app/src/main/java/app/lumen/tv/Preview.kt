package app.lumen.tv

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
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
    /** Live TV stopped because nobody answered "Are you still watching?": no preview until a button is pressed. */
    var idleStopped by mutableStateOf(false)
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
    return ExoPlayer.Builder(ctx, lumenRenderers(ctx))
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(8_000, 30_000, 1_000, 2_000).build())
        .build().also { applyAudioPrefs(it) }
}

/**
 * Decoders and audio output for every player (full screen, Live TV preview, Multi-View), so they all sound the same:
 * Settings → Player / Audio: hardware decoding, Dolby Vision, stereo downmix.
 */
@OptIn(UnstableApi::class)
fun lumenRenderers(ctx: Context): DefaultRenderersFactory {
    val hw = AppState.uiBool("hwDecoding", true)
    val noDv = AppState.uiBool("disableDolbyVision", false)
    val stereo = AppState.uiStr("audioOutput", "auto") == "stereo"
    return object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? =
            if (!stereo) super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            // No passthrough: decode everything and let Android mix it down for TV speakers / headphones.
            else androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES).build()
    }
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector { mime, secure, tunneling ->
            val m = if (noDv && mime == MimeTypes.VIDEO_DOLBY_VISION) MimeTypes.VIDEO_H265 else mime
            val list = runCatching { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(m, secure, tunneling) }.getOrDefault(emptyList())
            if (!hw) list.filter { !it.hardwareAccelerated }.ifEmpty { list } else list
        }
}

/** Track choices that go with lumenRenderers (stereo output = at most two audio channels). */
fun applyAudioPrefs(exo: ExoPlayer) {
    if (AppState.uiStr("audioOutput", "auto") == "stereo")
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setMaxAudioChannelCount(2).build()
}

/**
 * Live TV audio track (Settings → Audio → Live TV audio track). Many IPTV channels carry the same sound twice: AAC
 * ("mp4a") and Dolby E-AC-3. The Dolby track often plays much quieter, and players could end up on different ones.
 * With "AAC" (the default) every channel starts on its AAC track when it has one; picking a track yourself in the
 * player still wins for that channel.
 */
class LiveAudio(private val isLive: () -> Boolean) : androidx.media3.common.Player.Listener {
    private var handled = ""
    var exo: ExoPlayer? = null

    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
        val p = exo ?: return
        if (!isLive() || AppState.uiStr("liveAudioTrack", "aac") != "aac") return
        val audio = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        if (audio.size < 2 && (audio.firstOrNull()?.length ?: 0) < 2) return
        val key = (p.currentMediaItem?.localConfiguration?.uri?.toString() ?: "") + audio.joinToString { it.mediaTrackGroup.id }
        if (key == handled) return // once per channel, so a track picked by hand stays
        handled = key
        fun isAac(f: androidx.media3.common.Format) = f.sampleMimeType == MimeTypes.AUDIO_AAC || f.codecs?.startsWith("mp4a") == true
        val selected = audio.any { g -> (0 until g.length).any { g.isTrackSelected(it) && isAac(g.getTrackFormat(it)) } }
        if (selected) return
        for (g in audio) for (i in 0 until g.length) if (g.isTrackSupported(i) && isAac(g.getTrackFormat(i))) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
            return
        }
    }

    companion object {
        /** Watch [exo]'s tracks; returns the listener so it can be removed. */
        fun attach(exo: ExoPlayer, isLive: () -> Boolean = { true }): LiveAudio =
            LiveAudio(isLive).also { it.exo = exo; exo.addListener(it) }
    }
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
    if (Preview.idleStopped) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) { T("Stopped — press any button to continue", 13.sp, C.text3) }
        return
    }
    if (!started || Preview.pausedForPlayer) { Box(modifier.background(Color.Black)); return }

    // Take over the full-screen player's stream if there is one (no reconnect), otherwise start our own.
    val taken = remember(ch.providerId, ch.id) { Preview.take(ch) }
    val exo = remember(ch.providerId, ch.id) { taken?.first ?: buildExo(ctx) }
    var desc by remember(exo) { mutableStateOf(taken?.second) }
    DisposableEffect(exo) {
        // Same loudness as full screen (Live TV volume offset + Maximum volume).
        Volume.apply(exo, live = true, session = exo.audioSessionId)
        val l = object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { Volume.apply(exo, live = true, session = audioSessionId) }
        }
        exo.addListener(l)
        val la = LiveAudio.attach(exo)
        onDispose {
            exo.removeListener(l)
            exo.removeListener(la)
            Volume.release(exo)
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

/**
 * Loudness for a player: Settings → Audio → Maximum volume plus the Live TV / Plex volume offset (dB). Quieter
 * turns the player down; louder uses Android's loudness enhancer (no clipping). Kept per player, so a stream
 * handed from full screen to the Live TV preview keeps the same loudness.
 */
object Volume {
    private val enhancers = java.util.WeakHashMap<androidx.media3.exoplayer.ExoPlayer, android.media.audiofx.LoudnessEnhancer>()

    fun gainMb(live: Boolean): Double {
        val offsetDb = AppState.uiNum(if (live) "liveVolumeOffset" else "plexVolumeOffset", 0.0)
        val maxMb = AppState.uiNum("maxVolume", 100.0).takeIf { it > 100 }?.let { 2000 * kotlin.math.log10(it / 100.0) } ?: 0.0
        return maxMb + offsetDb * 100
    }

    @Synchronized fun apply(exo: androidx.media3.exoplayer.ExoPlayer, live: Boolean, session: Int) {
        val g = gainMb(live)
        runCatching { exo.volume = if (g < 0) Math.pow(10.0, g / 2000).toFloat().coerceIn(0f, 1f) else 1f }
        enhancers.remove(exo)?.let { runCatching { it.release() } }
        if (g <= 0 || session == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) return
        runCatching { android.media.audiofx.LoudnessEnhancer(session).apply { setTargetGain(g.toInt()); enabled = true } }.getOrNull()?.let { enhancers[exo] = it }
    }

    @Synchronized fun release(exo: androidx.media3.exoplayer.ExoPlayer) { enhancers.remove(exo)?.let { runCatching { it.release() } } }
}
