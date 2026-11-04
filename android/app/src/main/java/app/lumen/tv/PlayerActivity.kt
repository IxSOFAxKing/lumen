package app.lumen.tv

import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

/** Outlives an activity: used to report the final position and release server sessions after the player closes. */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

data class PanelItem(val label: String, val selected: Boolean = false, val header: Boolean = false, val action: () -> Unit = {})
data class Panel(val title: String, val items: List<PanelItem>, val index: Int)
data class Btn(val id: String, val label: String)

/** Everything the player overlay shows; plain Compose state driven by [PlayerActivity]. */
class PlayerState {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var live by mutableStateOf(false)
    var channel by mutableStateOf<Channel?>(null)
    var group by mutableStateOf("")
    var pos by mutableDoubleStateOf(0.0)
    var dur by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var controls by mutableStateOf(true)
    var zone by mutableIntStateOf(0)          // 0 = seek bar, 1 = buttons
    var btn by mutableIntStateOf(0)
    var buttons by mutableStateOf<List<Btn>>(emptyList())
    var scrub by mutableStateOf<Double?>(null)
    var panel by mutableStateOf<Panel?>(null)
    var skip by mutableStateOf<Marker?>(null)
    var upNext by mutableStateOf<Int?>(null)
    var upNextLabel by mutableStateOf("")
    var chList by mutableStateOf(false)
    var chIndex by mutableIntStateOf(0)
    var digits by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var method by mutableStateOf("")
}

/**
 * Native player (Media3 / ExoPlayer): hardware decoding of HEVC, Dolby Digital/Plus, DTS passthrough, MKV,
 * MPEG-TS and HLS. The overlay is drawn in Compose and controlled entirely from the remote; it always hides
 * itself after a few seconds.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var exo: ExoPlayer
    private lateinit var view: PlayerView
    val st = PlayerState()
    private var source: Source? = null
    private var desc: Desc? = null
    private var openJob: Job? = null
    private var fallbacks = 0
    private var hideAt = 0L
    private var scrubCommitAt = 0L
    private var lastReport = 0L
    private var lastPing = 0L
    private var bufferingSince = 0L
    private var digitsAt = 0L
    private var upNextDismissed = false
    private var upNextTick = 0L
    private val skippedMarkers = HashSet<String>()
    private var zapJob: Job? = null
    private var prevChannel: Channel? = null
    private var closed = false

    private val hideMs get() = ((if (st.live) AppState.uiNum("zapOverlaySeconds", 4.0) else AppState.uiNum("controlsHideSeconds", 3.0) + 1) * 1000).toLong().coerceAtLeast(2500)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("LumenTV/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(90_000) // Plex/Lumen transcoders can take a while for the first segment
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${Net.token}", "Cookie" to "lumen_device=${Net.token}"))
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        // Bigger buffers than the default smooth out IPTV streams.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 1_500, 3_000).build()
        exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .build()
        exo.addListener(listener)

        view = PlayerView(this).apply {
            player = exo
            useController = false
            isFocusable = false
            setKeepContentOnPlayerReset(true)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(AColor.BLACK)
            subtitleView?.let { styleSubtitles(it) }
        }
        setContent { LumenTheme(Net.prefs.uiScale) { PlayerOverlay(st, view) } }
        PlayerBus.player = WeakReference(this)

        val req = intent.getStringExtra("req")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val webJson = intent.getStringExtra("json")
        val src = when {
            req != null -> sourceFromRequest(req)
            webJson != null -> runCatching { WebSource(JSONObject(webJson)) }.getOrNull()
            else -> null
        }
        if (src == null) { finish(); return }
        if (src is LiveSource) {
            st.group = Playback.zapTitle
            st.chIndex = Playback.zapList.indexOfFirst { it.id == src.channel.id && it.providerId == src.channel.providerId }.coerceAtLeast(0)
        }
        start(src, req?.optDouble("offset", 0.0) ?: (webJson?.let { runCatching { JSONObject(it).optDouble("startPosition", 0.0) }.getOrNull() } ?: 0.0))
        lifecycleScope.launch { while (isActive) { tick(); delay(250) } }
    }

    private fun styleSubtitles(sv: SubtitleView) {
        val size = AppState.uiNum("subtitleSize", 100.0).toFloat() / 100f
        val bgAlpha = (AppState.uiNum("subtitleBg", 60.0).coerceIn(0.0, 100.0) * 2.55).toInt()
        val fg = runCatching { AColor.parseColor(AppState.uiStr("subtitleColor", "#ffffff")) }.getOrDefault(AColor.WHITE)
        sv.setStyle(CaptionStyleCompat(fg, AColor.argb(bgAlpha, 0, 0, 0), AColor.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, AColor.BLACK, null))
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size)
    }

    /** Playback requested by the web interface while the player is already open. */
    fun loadWeb(json: String) {
        val j = runCatching { JSONObject(json) }.getOrNull() ?: return
        start(WebSource(j), j.optDouble("startPosition", 0.0))
    }

    // ------------------------------------------------------------------ loading
    private fun absPos(): Double = exo.currentPosition / 1000.0 + (desc?.startOffset ?: 0.0)
    private fun duration(): Double {
        val s = source?.duration ?: 0.0
        if (s > 0) return s
        val d = exo.duration
        return if (d != C.TIME_UNSET && d > 0) d / 1000.0 + (desc?.startOffset ?: 0.0) else 0.0
    }

    private fun start(src: Source, offset: Double, mode: String? = null, keepState: Boolean = false) {
        openJob?.cancel()
        val old = source
        val oldDesc = desc
        if (old != null && old !== src && !old.isLive && oldDesc != null) {
            val p = absPos(); val d = duration()
            AppScope.launch { old.progress(p, d, "stopped") }
        }
        if (old !== src) {
            fallbacks = 0; upNextDismissed = false; skippedMarkers.clear(); st.upNext = null; st.skip = null
        }
        source = src
        desc = null
        st.error = null
        st.live = src.isLive
        st.title = src.title; st.subtitle = src.subtitle
        st.channel = (src as? LiveSource)?.channel
        if (!keepState) { st.scrub = null; st.panel = null }
        st.buffering = true
        poke()
        openJob = lifecycleScope.launch {
            if (oldDesc != null) AppScope.launch { Playback.release(oldDesc) }
            try {
                src.prepare()
                st.title = src.title; st.subtitle = src.subtitle
                val d = src.open(offset, mode)
                if (d.url.isBlank()) throw IllegalStateException("The server didn't return a stream")
                desc = d
                st.method = d.method
                st.buttons = buildButtons(src)
                play(d)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                showError(e.message ?: "Couldn't start playback")
            }
        }
    }

    private fun play(d: Desc) {
        val b = MediaItem.Builder().setUri(Uri.parse(d.url))
        when (d.type) {
            "hls" -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            "mpegts" -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        d.subtitleUrl?.let { sub ->
            b.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub)).setMimeType(MimeTypes.TEXT_VTT).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        val startMs = if (st.live) 0L else (((d.startPosition - d.startOffset).coerceAtLeast(0.0)) * 1000).toLong()
        if (startMs > 1000) exo.setMediaItem(b.build(), startMs) else exo.setMediaItem(b.build())
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun showError(msg: String) {
        st.error = msg
        st.buffering = false
        st.controls = false
    }

    private fun buildButtons(src: Source): List<Btn> {
        if (src.isLive) return emptyList()
        val l = mutableListOf(Btn("play", "Play / Pause"), Btn("back", "Back ${seekBack().toInt()}s"), Btn("fwd", "Forward ${seekFwd().toInt()}s"),
            Btn("restart", "Start over"), Btn("tracks", "Audio & subtitles"))
        if (src.isPlex) l += Btn("quality", "Quality")
        if (src.hasNext) l += Btn("next", "Next episode")
        return l
    }

    private fun seekBack() = AppState.uiNum("seekBack", 10.0)
    private fun seekFwd() = AppState.uiNum("seekForward", 30.0)

    // ------------------------------------------------------------------ player events
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            st.buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && st.error == null && desc == null
            if (state == Player.STATE_BUFFERING) { if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis() } else bufferingSince = 0L
            if (state == Player.STATE_READY) fallbacks = fallbacks.coerceAtMost(1)
            if (state == Player.STATE_ENDED) onEnded()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            st.playing = isPlaying
            if (!isPlaying && !st.live) report("paused")
            if (isPlaying) poke(short = true)
        }
        override fun onPlayerError(error: PlaybackException) {
            val src = source ?: return
            val d = desc
            if (src.isLive && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { exo.seekToDefaultPosition(); exo.prepare(); return }
            if (src is WebSource) {
                PlayerBus.error(error.errorCodeName)
                st.message = "Trying another way…"
                lifecycleScope.launch { delay(12_000); if (desc === d && !exo.isPlaying) showError(error.errorCodeName) }
                return
            }
            val mode = d?.let { nextMode(it) }
            if (mode != null && fallbacks < 3) {
                fallbacks++
                st.message = "Trying another way to play this…"
                start(src, if (src.isLive) 0.0 else absPos().coerceAtLeast(d?.startPosition ?: 0.0), mode, keepState = true)
            } else showError("${error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}${error.message?.let { ": $it" } ?: ""}")
        }
    }

    private fun onEnded() {
        val src = source ?: return
        if (src.isLive) { start(src, 0.0); return }
        report("stopped")
        if (src is WebSource) { PlayerBus.ended(); if (!src.hasNext) finish(); return }
        if (src.hasNext && AppState.uiBool("autoplayNext", true)) playNext() else finish()
    }

    private fun playNext() {
        val src = source ?: return
        st.upNext = null
        if (src is WebSource) { PlayerBus.ended(); return }
        lifecycleScope.launch {
            val n = src.next()
            if (n == null) finish() else start(n, 0.0)
        }
    }

    private fun report(state: String) {
        val src = source ?: return
        if (src.isLive || desc == null) return
        lastReport = System.currentTimeMillis()
        val p = absPos(); val d = duration()
        if (p <= 0.5) return
        AppScope.launch { src.progress(p, d, state) }
    }

    // ------------------------------------------------------------------ tick (4× a second)
    private fun tick() {
        val now = System.currentTimeMillis()
        val src = source
        if (desc != null) {
            st.pos = absPos()
            st.dur = duration()
        }
        // controls auto-hide (also while paused, after a longer delay)
        if (st.controls && st.panel == null && st.scrub == null && now > hideAt && (st.playing || st.live || now > hideAt + 6000)) {
            st.controls = false
            st.zone = 0
        }
        if (st.scrub != null && now > scrubCommitAt) commitScrub()
        if (st.message != null && now > hideAt + 2000) st.message = null
        if (st.digits.isNotEmpty() && now - digitsAt > 1500) commitDigits()
        if (src == null) return
        if (exo.isPlaying && now - lastReport > 10_000) report("playing")
        if (now - lastPing > 20_000) { lastPing = now; desc?.let { d -> AppScope.launch { Playback.ping(d) } } }
        // Live stream stuck buffering: reconnect.
        if (src.isLive && bufferingSince > 0 && now - bufferingSince > 15_000 && desc != null) { bufferingSince = 0; st.message = "Reconnecting…"; start(src, 0.0, keepState = true) }
        if (!src.isLive) markersAndUpNext(src, now)
    }

    private fun markersAndUpNext(src: Source, now: Long) {
        val pos = st.pos
        val m = src.markers.firstOrNull { (it.type == "intro" || it.type == "credits") && pos >= it.start && pos < it.end - 1 }
        st.skip = m
        if (m != null) {
            val id = "${m.type}:${m.start}"
            val auto = if (m.type == "intro") AppState.uiBool("autoSkipIntro", false) else AppState.uiBool("autoSkipCredits", false)
            if (auto && id !in skippedMarkers) { skippedMarkers += id; skip(m) }
        }
        // "Up next" card: when the credits start (or 30 s before the end)
        val dur = st.dur
        if (src.hasNext && AppState.uiBool("autoplayNext", true) && !upNextDismissed && dur > 60) {
            val credits = src.markers.firstOrNull { it.type == "credits" }
            val trigger = credits?.start ?: (dur - 30)
            if (pos >= trigger && st.upNext == null) {
                st.upNext = AppState.uiNum("autoplayCountdown", 10.0).toInt().coerceIn(3, 60)
                st.upNextLabel = src.nextLabel ?: "Next episode"
                upNextTick = now
            }
            val left = st.upNext
            if (left != null && exo.isPlaying && now - upNextTick >= 1000) {
                upNextTick = now
                if (left <= 1) playNext() else st.upNext = left - 1
            }
        }
    }

    private fun skip(m: Marker) {
        val src = source ?: return
        st.skip = null
        if (m.type == "credits" && src.hasNext) { playNext(); return }
        seekAbs(m.end)
    }

    // ------------------------------------------------------------------ actions
    private fun poke(short: Boolean = false) {
        st.controls = true
        hideAt = System.currentTimeMillis() + if (short) minOf(hideMs, 2500) else hideMs
    }

    private fun togglePause() { if (exo.isPlaying) exo.pause() else exo.play(); poke() }

    private fun seekAbs(t: Double) {
        val d = desc ?: return
        val src = source ?: return
        val max = if (st.dur > 2) st.dur - 2 else Double.MAX_VALUE
        val target = t.coerceIn(0.0, max)
        if (d.restartOnSeek) start(src, target, keepState = true)
        else exo.seekTo(((target - d.startOffset).coerceAtLeast(0.0) * 1000).toLong())
    }

    private fun scrubBy(delta: Double, repeat: Int) {
        val base = st.scrub ?: st.pos
        val accel = 1 + (repeat / 4).coerceAtMost(8)
        st.scrub = (base + delta * accel).coerceIn(0.0, if (st.dur > 0) st.dur else Double.MAX_VALUE)
        scrubCommitAt = System.currentTimeMillis() + 900
        poke()
    }

    private fun commitScrub() {
        val t = st.scrub ?: return
        st.scrub = null
        seekAbs(t)
        poke()
    }

    private fun activate(id: String) {
        val src = source ?: return
        when (id) {
            "play" -> togglePause()
            "back" -> seekAbs(st.pos - seekBack())
            "fwd" -> seekAbs(st.pos + seekFwd())
            "restart" -> seekAbs(0.0)
            "next" -> playNext()
            "tracks" -> st.panel = tracksPanel(src)
            "quality" -> st.panel = qualityPanel(src)
        }
        poke()
    }

    private fun langName(f: Format): String? = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage.takeIf { n -> n.isNotBlank() } ?: it }

    private fun tracksPanel(src: Source): Panel {
        val items = mutableListOf<PanelItem>()
        val d = desc
        val serverSide = src.isPlex && d != null && !d.method.startsWith("direct")
        if (serverSide) {
            // Plex is transcoding: it picks the streams, so ask it for a different one.
            items += PanelItem("Audio", header = true)
            src.audio.forEach { a -> items += PanelItem(a.label, a.selected) { src.choose(audioId = a.id); start(src, st.pos, keepState = true) } }
            items += PanelItem("Subtitles", header = true)
            items += PanelItem("Off", src.subtitles.none { it.selected }) { src.choose(subtitleId = 0); start(src, st.pos, keepState = true) }
            src.subtitles.forEach { s -> items += PanelItem(s.label, s.selected) { src.choose(subtitleId = s.id); start(src, st.pos, keepState = true) } }
        } else {
            val groups = exo.currentTracks.groups
            items += PanelItem("Audio", header = true)
            groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    val codec = f.sampleMimeType?.substringAfter('/')?.uppercase()?.replace("VND.DTS", "DTS")
                    val ch = when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (f.channelCount > 0) "${f.channelCount}ch" else null }
                    val label = listOfNotNull(f.label ?: langName(f) ?: "Track ${items.size}", codec, ch).joinToString(" · ")
                    items += PanelItem(label, g.isTrackSelected(i)) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    }
                }
            }
            items += PanelItem("Subtitles", header = true)
            val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val anyText = textGroups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
            items += PanelItem("Off", !anyText) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            textGroups.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    val label = listOfNotNull(f.label ?: langName(f) ?: "Subtitle", if ((f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) "Forced" else null).joinToString(" · ")
                    items += PanelItem(label, g.isTrackSelected(i)) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    }
                }
            }
            if (src.isPlex && src.subtitles.isNotEmpty() && textGroups.isEmpty()) {
                items += PanelItem("Plex subtitles (switches to transcoding)", header = true)
                src.subtitles.forEach { s -> items += PanelItem(s.label, s.selected) { src.choose(subtitleId = s.id); start(src, st.pos, keepState = true) } }
            }
        }
        val first = items.indexOfFirst { !it.header && it.selected }.takeIf { it >= 0 } ?: items.indexOfFirst { !it.header }.coerceAtLeast(0)
        return Panel("Audio & subtitles", items, first)
    }

    private fun qualityPanel(src: Source): Panel {
        val cur = (desc?.raw?.optString("quality") ?: "auto")
        val opts = listOf("auto" to "Auto (direct play when possible)", "original" to "Original", "20000" to "1080p · 20 Mbps", "12000" to "1080p · 12 Mbps",
            "8000" to "1080p · 8 Mbps", "4000" to "720p · 4 Mbps", "2000" to "720p · 2 Mbps", "720" to "480p · 1.5 Mbps")
        val items = opts.map { (v, l) -> PanelItem(l, v == cur) { src.choose(quality = v); start(src, st.pos, keepState = true) } }
        val method = desc?.method ?: ""
        return Panel("Quality  ·  now: $method", items, opts.indexOfFirst { it.first == cur }.coerceAtLeast(0))
    }

    // ------------------------------------------------------------------ live TV
    private fun zap(dir: Int) {
        val list = Playback.zapList
        if (list.size < 2) { poke(); return }
        val wrap = AppState.uiBool("zapWrap", true)
        var i = st.chIndex + dir
        i = if (wrap) (i + list.size) % list.size else i.coerceIn(0, list.size - 1)
        zapTo(i, debounce = true)
    }

    private fun zapTo(i: Int, debounce: Boolean = false) {
        val list = Playback.zapList
        val ch = list.getOrNull(i) ?: return
        val cur = (source as? LiveSource)?.channel
        st.chIndex = i
        st.channel = ch; st.title = ch.name; st.subtitle = ""
        poke()
        zapJob?.cancel()
        zapJob = lifecycleScope.launch {
            if (debounce) delay(400)
            if (cur != null && cur.id != ch.id) prevChannel = cur
            Net.prefs.lastChannel = "${ch.providerId}:${ch.id}"
            start(LiveSource(ch), 0.0)
        }
    }

    private fun commitDigits() {
        val n = st.digits.toIntOrNull()
        st.digits = ""
        if (n == null) return
        val i = Playback.zapList.indexOfFirst { it.num == n }
        if (i >= 0) zapTo(i) else Toast.makeText(this, "No channel $n in ${st.group.ifBlank { "this list" }}", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ remote control
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        return onKey(code, event.repeatCount) || super.dispatchKeyEvent(event)
    }

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun onKey(code: Int, repeat: Int): Boolean {
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        // Error screen
        st.error?.let {
            if (isOk(code)) { val s = source; if (s != null) { fallbacks = 0; start(s, if (s.isLive) 0.0 else st.pos) } } else if (back) finish()
            return true
        }
        // Side panel (audio/subtitles/quality)
        st.panel?.let { p ->
            when {
                code == KeyEvent.KEYCODE_DPAD_UP -> st.panel = p.copy(index = prevItem(p, p.index))
                code == KeyEvent.KEYCODE_DPAD_DOWN -> st.panel = p.copy(index = nextItem(p, p.index))
                isOk(code) -> { st.panel = null; p.items.getOrNull(p.index)?.takeIf { !it.header }?.action?.invoke() }
                back || code == KeyEvent.KEYCODE_DPAD_LEFT -> st.panel = null
            }
            poke(); return true
        }
        // Live channel list
        if (st.chList) {
            val n = Playback.zapList.size
            when {
                code == KeyEvent.KEYCODE_DPAD_UP -> st.chIndex = (st.chIndex - (1 + repeat / 3)).coerceAtLeast(0)
                code == KeyEvent.KEYCODE_DPAD_DOWN -> st.chIndex = (st.chIndex + (1 + repeat / 3)).coerceAtMost(n - 1)
                code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_CHANNEL_UP -> st.chIndex = (st.chIndex - 10).coerceAtLeast(0)
                code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN -> st.chIndex = (st.chIndex + 10).coerceAtMost(n - 1)
                isOk(code) -> { st.chList = false; val cur = (source as? LiveSource)?.channel; val ch = Playback.zapList.getOrNull(st.chIndex); if (ch != null && (cur == null || ch.id != cur.id)) zapTo(st.chIndex) }
                back || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    st.chList = false
                    st.chIndex = Playback.zapList.indexOfFirst { it.id == (source as? LiveSource)?.channel?.id }.coerceAtLeast(0)
                }
            }
            return true
        }
        // Up next card
        if (st.upNext != null) {
            if (isOk(code) && !st.controls) { playNext(); return true }
            if (back) { st.upNext = null; upNextDismissed = true; return true }
        }
        // Skip intro / credits while the controls are hidden
        st.skip?.let { m -> if (isOk(code) && !st.controls) { skip(m); return true } }
        // Media keys
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { exo.play(); poke(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo.pause(); poke(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
        }
        return if (st.live) liveKey(code, repeat, back) else vodKey(code, repeat, back)
    }

    private fun nextItem(p: Panel, from: Int): Int { var i = from + 1; while (i < p.items.size && p.items[i].header) i++; return if (i < p.items.size) i else from }
    private fun prevItem(p: Panel, from: Int): Int { var i = from - 1; while (i >= 0 && p.items[i].header) i--; return if (i >= 0) i else from }

    private fun liveKey(code: Int, repeat: Int, back: Boolean): Boolean {
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            st.digits = (st.digits + (code - KeyEvent.KEYCODE_0)).takeLast(5); digitsAt = System.currentTimeMillis(); poke(); return true
        }
        when {
            back -> if (st.controls) st.controls = false else finish()
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP -> zap(-1)
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN -> zap(1)
            isOk(code) || code == KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (repeat > 0) return true
                if (Playback.zapList.size > 1) { st.chList = true; st.controls = false } else poke()
            }
            code == KeyEvent.KEYCODE_LAST_CHANNEL -> prevChannel?.let { pc -> Playback.zapList.indexOfFirst { it.id == pc.id }.takeIf { it >= 0 }?.let { zapTo(it) } }
            code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_GUIDE -> if (st.controls && code != KeyEvent.KEYCODE_DPAD_RIGHT) st.controls = false else poke()
            else -> return false
        }
        return true
    }

    private fun vodKey(code: Int, repeat: Int, back: Boolean): Boolean {
        val btns = st.buttons
        when {
            back -> when {
                st.scrub != null -> { st.scrub = null; poke() }
                st.controls -> { st.controls = false; st.zone = 0 }
                else -> finish()
            }
            isOk(code) -> {
                if (repeat > 0) return true
                when {
                    !st.controls -> togglePause()
                    st.zone == 0 -> if (st.scrub != null) commitScrub() else togglePause()
                    else -> btns.getOrNull(st.btn)?.let { activate(it.id) }
                }
            }
            code == KeyEvent.KEYCODE_DPAD_LEFT -> if (!st.controls || st.zone == 0) scrubBy(-seekBack(), repeat) else { st.btn = (st.btn - 1).coerceAtLeast(0); poke() }
            code == KeyEvent.KEYCODE_DPAD_RIGHT -> if (!st.controls || st.zone == 0) scrubBy(seekFwd(), repeat) else { st.btn = (st.btn + 1).coerceAtMost(btns.size - 1); poke() }
            code == KeyEvent.KEYCODE_MEDIA_REWIND -> scrubBy(-seekBack(), repeat)
            code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> scrubBy(seekFwd(), repeat)
            code == KeyEvent.KEYCODE_MEDIA_NEXT -> if (source?.hasNext == true) playNext()
            code == KeyEvent.KEYCODE_DPAD_UP -> { if (st.controls && st.zone == 1) st.zone = 0; poke() }
            code == KeyEvent.KEYCODE_DPAD_DOWN -> { if (st.controls) { st.zone = 1; st.btn = st.btn.coerceIn(0, (btns.size - 1).coerceAtLeast(0)) }; poke() }
            code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_CAPTIONS -> source?.let { st.panel = tracksPanel(it); poke() }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onStop() {
        super.onStop()
        if (isFinishing) close() else if (!isChangingConfigurations) { report("paused"); exo.pause() }
    }

    override fun onDestroy() {
        close()
        if (PlayerBus.player?.get() === this) PlayerBus.player = null
        exo.release()
        super.onDestroy()
    }

    private fun close() {
        if (closed) return
        closed = true
        val src = source
        val d = desc
        if (src != null && d != null) {
            if (!src.isLive) { val p = absPos(); val du = duration(); AppScope.launch { src.progress(p, du, "stopped") } }
            AppScope.launch { Playback.release(d) }
        }
        if (src is WebSource) PlayerBus.closed(if (src.isLive) 0.0 else absPos() - (d?.startOffset ?: 0.0))
        Net.clearCache()
        exo.stop()
    }
}
