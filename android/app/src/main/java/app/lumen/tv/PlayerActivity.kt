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
import androidx.compose.ui.graphics.asImageBitmap
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
data class Panel(val title: String, val items: List<PanelItem>, val index: Int, val parent: Panel? = null)
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
    var sleepAt by mutableStateOf(0L)
    var thumb by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var subOffsetMs by mutableIntStateOf(0)
    /** TiviMate-style live bar: selected button (0 prev ch, 1 rewind, 2 play, 3 forward, 4 next ch, 5 restart, 6 live, 7 record). */
    var liveBtn by mutableIntStateOf(2)
    /** Live overlay opened with OK (not just the banner after a channel change); 0 = buttons, 1 = seek bar. */
    var liveOverlay by mutableStateOf(false)
    var liveZone by mutableIntStateOf(0)
    var badges by mutableStateOf<List<String>>(emptyList())
    var providerName by mutableStateOf("")
    var poster by mutableStateOf<String?>(null)
    var context by mutableStateOf("")
    var metaLine by mutableStateOf("")
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
        playerActions = object : PlayerActions {
            override fun tap() {
                when {
                    st.panel != null -> st.panel = null
                    st.chList -> st.chList = false
                    st.controls -> { st.controls = false; st.zone = 0 }
                    else -> if (st.live) openLiveOverlay() else poke()
                }
            }
            override fun button(id: String) { st.zone = 1; st.btn = st.buttons.indexOfFirst { it.id == id }.coerceAtLeast(0); activate(id) }
            override fun liveButton(i: Int) { st.liveBtn = i; liveAction(i); poke() }
            override fun scrubTo(fraction: Float) {
                if (st.live || st.dur <= 0) return
                st.scrub = (fraction.coerceIn(0f, 1f) * st.dur); scrubCommitAt = System.currentTimeMillis() + 60_000; poke()
            }
            override fun commitScrub() { this@PlayerActivity.commitScrub() }
            override fun panelPick(i: Int) {
                val p = st.panel ?: return
                val item = p.items.getOrNull(i) ?: return
                if (item.header) return
                st.panel = null
                item.action()
                poke()
            }
            override fun channelPick(i: Int) { st.chList = false; zapTo(i) }
            override fun skip() { st.skip?.let { skip(it) } }
            override fun upNext() { playNext() }
            override fun openChannels() { if (Playback.zapList.size > 1) { st.chList = true; st.controls = false } }
            override fun openGuide() { lifecycleScope.launch { programmesPanel()?.let { st.panel = it; poke() } } }
            override fun openOptions() { source?.let { st.panel = if (it.isLive) liveOptions(it) else vodSettings(it); poke() } }
        }

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
        st.channel = (src as? LiveSource)?.channel ?: st.channel.takeIf { src is CatchupSource }
        (src as? LiveSource)?.channel?.let { c -> st.providerName = AppState.providers.firstOrNull { it.id == c.providerId }?.name ?: "" }
        if (!keepState) { st.scrub = null; st.panel = null }
        st.buffering = true
        poke()
        openJob = lifecycleScope.launch {
            if (oldDesc != null) AppScope.launch { Playback.release(oldDesc) }
            try {
                src.prepare()
                st.title = src.title; st.subtitle = src.subtitle
                st.poster = src.poster; st.context = src.context; st.metaLine = src.metaLine
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
        // Same controls as Plex: previous / rewind / play / forward / next, then audio & subtitles, episodes, chapters, settings.
        val l = mutableListOf<Btn>()
        if (src.hasPrev) l += Btn("prev", "Previous")
        l += Btn("back", "Back ${seekBack().toInt()}s"); l += Btn("play", "Play / Pause"); l += Btn("fwd", "Forward ${seekFwd().toInt()}s")
        if (src.hasNext) l += Btn("next", "Next")
        l += Btn("restart", "Start over"); l += Btn("tracks", "Audio & subtitles")
        if (src.isPlex && src.ratingKey != null) l += Btn("episodes", "Episodes")
        if (src.chapters.size > 1) l += Btn("chapters", "Chapters")
        l += Btn("settings", "Settings")
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
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // After a subtitle-sync reload, switch to the shifted subtitles.
            if (!pickSynced) return
            tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { g ->
                for (i in 0 until g.length) if (g.getTrackFormat(i).id?.contains("lumen-sync") == true) {
                    pickSynced = false
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    return
                }
            }
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
        if (st.sleepAt > 0 && now >= st.sleepAt) { finish(); return }
        if (!st.controls && st.liveOverlay) { st.liveOverlay = false; st.liveZone = 0 }
        updateBadges()
        if (st.message != null && now > hideAt + 2000) st.message = null
        if (st.digits.isNotEmpty() && now - digitsAt > 1500) commitDigits()
        if (src == null) return
        if (exo.isPlaying && now - lastReport > 10_000) report("playing")
        if (now - lastPing > 20_000) { lastPing = now; desc?.let { d -> AppScope.launch { Playback.ping(d) } } }
        // Live stream stuck buffering: reconnect.
        if (src.isLive && bufferingSince > 0 && now - bufferingSince > 15_000 && desc != null) { bufferingSince = 0; st.message = "Reconnecting…"; start(src, 0.0, keepState = true) }
        if (!src.isLive) markersAndUpNext(src, now)
        if (src is LiveSource && now - lastEpg > 60_000) refreshEpg(src)
    }

    private var lastEpg = 0L
    /** Keep the banner's now/next current during long viewing. */
    private fun refreshEpg(src: LiveSource) {
        lastEpg = System.currentTimeMillis()
        val ch = st.channel ?: return
        val p = ch.onAir
        if (p != null && ch.next != null) return
        lifecycleScope.launch {
            val n = nowS()
            val list = runCatching { Net.arr("/api/iptv/epg/${ch.providerId}/${android.net.Uri.encode(ch.id)}?from=${n - 60}&to=${n + 6 * 3600}") }.getOrNull()?.objects()?.mapNotNull { Programme.from(it) } ?: return@launch
            val cur = list.firstOrNull { it.isNow }
            val next = list.firstOrNull { it.start >= (cur?.stop ?: n) }
            if (st.channel?.id == ch.id) st.channel = ch.copy(now = cur, next = next)
            if (src === source) st.subtitle = cur?.let { "${fmtTime(it.start)} – ${fmtTime(it.stop)} · ${it.title}" } ?: ""
        }
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
            "prev" -> lifecycleScope.launch { src.previous()?.let { start(it, 0.0) } }
            "tracks" -> st.panel = tracksPanel(src)
            "quality" -> st.panel = qualityPanel(src)
            "settings" -> st.panel = vodSettings(src)
            "chapters" -> st.panel = chaptersPanel(src)
            "episodes" -> lifecycleScope.launch { episodesPanel(src)?.let { st.panel = it; poke() } }
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

    // ------------------------------------------------------------------ option menus
    private var aspect = 0      // 0 fit, 1 fill (stretch), 2 zoom (crop)
    private var speed = 1f
    private var subScale = 1f

    private fun sub(parent: Panel, title: String, items: List<PanelItem>): Panel =
        Panel(title, items, items.indexOfFirst { !it.header && it.selected }.takeIf { it >= 0 } ?: items.indexOfFirst { !it.header }.coerceAtLeast(0), parent)

    private fun aspectPanel(parent: Panel): Panel {
        val opts = listOf("Fit (original)" to AspectRatioFrameLayout.RESIZE_MODE_FIT, "Stretch to fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL, "Zoom (crop)" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
        return sub(parent, "Aspect ratio", opts.mapIndexed { i, (l, m) -> PanelItem(l, aspect == i) { aspect = i; view.resizeMode = m } })
    }

    private fun sleepPanel(parent: Panel): Panel {
        val now = System.currentTimeMillis()
        val left = if (st.sleepAt > now) ((st.sleepAt - now) / 60000 + 1) else 0
        val opts = listOf(0, 15, 30, 45, 60, 90, 120, 180)
        return sub(parent, "Sleep timer" + if (left > 0) "  ·  $left min left" else "", opts.map { m ->
            PanelItem(if (m == 0) "Off" else "$m minutes", if (m == 0) st.sleepAt == 0L else false) {
                st.sleepAt = if (m == 0) 0L else System.currentTimeMillis() + m * 60_000L
                Toast.makeText(this, if (m == 0) "Sleep timer off" else "Stopping in $m minutes", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun speedPanel(parent: Panel): Panel {
        val opts = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        return sub(parent, "Playback speed", opts.map { v -> PanelItem(if (v == 1f) "Normal" else "${v}×", speed == v) { speed = v; exo.setPlaybackSpeed(v) } })
    }

    // ---------- seek-bar thumbnails ----------
    private var bif: Bif? = null
    private var bifFor: String? = null
    private var thumbIdx = -1
    private var thumbJob: Job? = null
    private var noThumbs = false
    private var singleFailed = false

    private fun loadThumbnails(src: Source, d: Desc) {
        if (!src.isPlex) { bif = null; bifFor = null; st.thumb = null; return }
        val part = d.raw.str("partId") ?: return
        if (bifFor == part) return
        bifFor = part; bif = null; thumbIdx = -1; st.thumb = null; noThumbs = false; singleFailed = false
        lifecycleScope.launch {
            val b = Bif.load(this@PlayerActivity, part)
            if (bifFor == part) bif = b
            if (b == null) noThumbs = true
        }
    }

    private fun updateThumb() {
        val t = st.scrub
        val b = bif
        if (t == null) { if (st.thumb != null) { st.thumb = null; thumbIdx = -1 }; return }
        if (b == null) {
            // No BIF file: fetch single preview frames (every 10 s) instead.
            val part = bifFor ?: return
            if (!noThumbs || singleFailed) return
            val i = (t / 10).toInt()
            if (i == thumbIdx || thumbJob?.isActive == true) return
            thumbIdx = i
            thumbJob = lifecycleScope.launch {
                val img = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        Net.http.newCall(okhttp3.Request.Builder().url("${Net.server}/api/plex/thumb/$part/${i * 10000L}").build()).execute().use { r ->
                            if (!r.isSuccessful) null else r.body?.bytes()?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                        }
                    }.getOrNull()
                }
                if (img != null) { if (st.scrub != null) st.thumb = img }
                else if (!singleFailed) {
                    singleFailed = true
                    Toast.makeText(this@PlayerActivity, "No preview thumbnails for this video. In Plex, turn on \"Generate video preview thumbnails\" for the library (Manage Library → Edit → Advanced).", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val i = b.indexAt(t)
        if (i == thumbIdx || thumbJob?.isActive == true) return
        thumbIdx = i
        thumbJob = lifecycleScope.launch { b.image(i)?.let { if (st.scrub != null) st.thumb = it } }
    }

    // ---------- subtitle sync ----------
    private var pickSynced = false

    private fun syncPanel(src: Source, parent: Panel): Panel {
        val steps = listOf(-5000, -3000, -2000, -1500, -1000, -750, -500, -250, 0, 250, 500, 750, 1000, 1500, 2000, 3000, 5000)
        val items = steps.map { ms ->
            val label = when { ms == 0 -> "In sync (0 s)"; ms < 0 -> "Show ${"%.2f".format(-ms / 1000.0).trimEnd('0').trimEnd('.')} s earlier"; else -> "Show ${"%.2f".format(ms / 1000.0).trimEnd('0').trimEnd('.')} s later" }
            PanelItem(label, st.subOffsetMs == ms) { applySubtitleSync(src, ms) }
        }
        return sub(parent, "Subtitle sync", items)
    }

    /** Re-load the subtitles shifted by [ms] (the server shifts the cue times), keeping the playback position. */
    private fun applySubtitleSync(src: Source, ms: Int) {
        val d = desc ?: return
        if (!d.method.startsWith("direct") && src.isPlex) {
            Toast.makeText(this, "These subtitles are burned into the video while transcoding, so they can't be shifted. Set Quality to Auto/Original to sync them.", Toast.LENGTH_LONG).show()
            return
        }
        val base = d.subtitleUrl ?: src.subtitles.firstOrNull { it.selected }?.let { Net.url("/api/plex/subtitle/${it.id}") }
        if (base == null) { Toast.makeText(this, "Choose a subtitle first (Audio & subtitles).", Toast.LENGTH_LONG).show(); return }
        val u = Uri.parse(base).buildUpon().clearQuery().apply {
            Uri.parse(base).queryParameterNames.filter { it != "offset" }.forEach { k -> appendQueryParameter(k, Uri.parse(base).getQueryParameter(k)) }
            if (ms != 0) appendQueryParameter("offset", ms.toString())
        }.build()
        st.subOffsetMs = ms
        val item = MediaItem.Builder().setUri(Uri.parse(d.url)).apply {
            when (d.type) { "hls" -> setMimeType(MimeTypes.APPLICATION_M3U8); "mpegts" -> setMimeType(MimeTypes.VIDEO_MP2T) }
            setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(u).setId("lumen-sync").setLabel("Subtitles (synced)")
                .setMimeType(MimeTypes.TEXT_VTT).setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED).build()))
        }.build()
        val pos = exo.currentPosition
        pickSynced = true
        exo.setMediaItem(item, pos)
        exo.prepare()
        exo.playWhenReady = true
        Toast.makeText(this, if (ms == 0) "Subtitle offset cleared" else "Subtitles shifted ${if (ms > 0) "+" else ""}${ms} ms", Toast.LENGTH_SHORT).show()
    }

    private fun subtitleSizePanel(parent: Panel): Panel {
        val opts = listOf(0.75f to "Small", 1f to "Normal", 1.25f to "Large", 1.5f to "Extra large")
        return sub(parent, "Subtitle size", opts.map { (v, l) -> PanelItem(l, subScale == v) {
            subScale = v
            val base = AppState.uiNum("subtitleSize", 100.0).toFloat() / 100f
            view.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * base * v)
        } })
    }

    /** Technical details of what's playing (like Plex's "Playback info" / TiviMate's stream info). */
    private fun infoPanel(parent: Panel): Panel {
        val v = exo.videoFormat; val a = exo.audioFormat; val d = desc
        val lines = listOfNotNull(
            "Method: ${d?.method ?: "?"}",
            v?.let { "Video: ${it.width}×${it.height}${if (it.frameRate > 0) " @ ${"%.2f".format(it.frameRate)} fps" else ""} · ${it.sampleMimeType?.substringAfter('/') ?: it.codecs ?: "?"}" },
            v?.bitrate?.takeIf { it > 0 }?.let { "Video bitrate: ${it / 1000} kbps" },
            a?.let { "Audio: ${it.sampleMimeType?.substringAfter('/') ?: "?"} · ${it.channelCount} ch · ${it.sampleRate} Hz" },
            d?.raw?.optString("quality")?.takeIf { it.isNotBlank() }?.let { "Quality setting: $it" },
            d?.raw?.optString("network")?.takeIf { it.isNotBlank() }?.let { "Network: $it" },
            "Buffered: ${"%.1f".format((exo.totalBufferedDuration) / 1000.0)} s",
            "Dropped frames: ${exo.videoDecoderCounters?.droppedBufferCount ?: 0}",
            d?.url?.let { "Stream: ${Uri.parse(it).path}" },
        )
        return Panel("Playback info", lines.map { PanelItem(it) }, 0, parent)
    }

    /** Plex player ⚙ menu. */
    private fun vodSettings(src: Source): Panel {
        lateinit var me: Panel
        val items = mutableListOf<PanelItem>()
        if (src.isPlex) items += PanelItem("Quality  ›") { st.panel = qualityPanel(src).copy(parent = me) }
        items += PanelItem("Audio & subtitles  ›") { st.panel = tracksPanel(src).copy(parent = me) }
        items += PanelItem("Subtitle size  ›") { st.panel = subtitleSizePanel(me) }
        items += PanelItem("Subtitle sync  ·  ${if (st.subOffsetMs == 0) "0 s" else "${st.subOffsetMs} ms"}  ›") { st.panel = syncPanel(src, me) }
        items += PanelItem("Playback speed  ·  ${if (speed == 1f) "Normal" else "${speed}×"}  ›") { st.panel = speedPanel(me) }
        items += PanelItem("Aspect ratio  ›") { st.panel = aspectPanel(me) }
        items += PanelItem("Sleep timer  ›") { st.panel = sleepPanel(me) }
        if (src.chapters.size > 1) items += PanelItem("Chapters  ›") { st.panel = chaptersPanel(src).copy(parent = me) }
        if (src is PlexSource && src.versions.size > 1) items += PanelItem("Version  ·  ${src.versions.getOrNull(src.versionIndex) ?: ""}  ›") {
            st.panel = sub(me, "Version", src.versions.mapIndexed { i, v -> PanelItem(v, i == src.versionIndex) { src.chooseVersion(i); start(src, st.pos, keepState = true) } })
        }
        if (src is PlexSource) items += PanelItem("Audio boost  ·  ${if (src.audioBoost == 100) "Off" else "${src.audioBoost}%"}  ›") {
            st.panel = sub(me, "Audio boost (Plex transcodes the audio)", listOf(100 to "Off", 150 to "Small (150%)", 200 to "Medium (200%)", 300 to "Large (300%)").map { (v, l) ->
                PanelItem(l, src.audioBoost == v) { src.chooseBoost(v); start(src, st.pos, keepState = true) }
            })
        }
        items += PanelItem("Playback info  ›") { st.panel = infoPanel(me) }
        me = Panel("Settings", items, 0)
        return me
    }

    private fun chaptersPanel(src: Source): Panel {
        val pos = st.pos
        val ch = src.chapters
        val cur = ch.indexOfLast { pos >= it.start }
        return Panel("Chapters", ch.mapIndexed { i, c -> PanelItem("${fmtClock(c.start)}   ${c.type}", i == cur) { seekAbs(c.start) } }, cur.coerceAtLeast(0))
    }

    private suspend fun episodesPanel(src: Source): Panel? {
        val eps = src.siblings()
        if (eps.isEmpty()) { Toast.makeText(this, "No other episodes", Toast.LENGTH_SHORT).show(); return null }
        val cur = eps.indexOfFirst { it.ratingKey == src.ratingKey }
        return Panel(eps.first().parentTitle ?: "Episodes", eps.mapIndexed { i, e ->
            PanelItem("${e.index ?: i + 1}.  ${e.title}${if (e.watched) "   ✓" else if (e.progress > 0) "   ◐" else ""}", i == cur) { start(PlexSource(e.ratingKey), if (e.viewOffsetMs > 0) e.resumeSec else 0.0) }
        }, cur.coerceAtLeast(0))
    }

    /** TiviMate-style options for live TV (MENU, or long-press OK). */
    private fun liveOptions(src: Source): Panel {
        lateinit var me: Panel
        val ch = (src as? LiveSource)?.channel
        val p = st.channel?.onAir
        val items = mutableListOf<PanelItem>()
        items += PanelItem("Programme guide  ›") { lifecycleScope.launch { programmesPanel()?.let { st.panel = it.copy(parent = me); poke() } } }
        items += PanelItem("Channel list  ›") { st.chList = true; st.controls = false }
        if (ch != null && p != null && ch.archive) items += PanelItem("Watch from start") { start(CatchupSource(ch.providerId, ch.id, p.start, p.stop, p.title, ch.name), 0.0) }
        if (ch != null && p != null) items += PanelItem("Record this programme") {
            lifecycleScope.launch { runCatching { Repo.record(ch, p) }.onSuccess { Toast.makeText(this@PlayerActivity, it, Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(this@PlayerActivity, it.message, Toast.LENGTH_LONG).show() } }
        }
        if (ch != null) items += PanelItem(if (ch.isFav) "Remove from favourites" else "Add to favourites") {
            lifecycleScope.launch { runCatching { Repo.toggleFavorite(ch) }.onSuccess { added -> Toast.makeText(this@PlayerActivity, if (added) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show() } }
        }
        prevChannel?.let { pc -> items += PanelItem("Previous channel: ${pc.name}") { Playback.zapList.indexOfFirst { it.id == pc.id }.takeIf { it >= 0 }?.let { zapTo(it) } } }
        items += PanelItem("Audio track  ›") { st.panel = tracksPanel(src).copy(parent = me) }
        items += PanelItem("Subtitles  ›") { st.panel = tracksPanel(src).copy(parent = me) }
        items += PanelItem("Video track  ›") { st.panel = videoPanel(me) }
        items += PanelItem("Aspect ratio  ›") { st.panel = aspectPanel(me) }
        items += PanelItem("Sleep timer  ›") { st.panel = sleepPanel(me) }
        items += PanelItem("Reload stream") { start(src, 0.0) }
        items += PanelItem("Stream info  ›") { st.panel = infoPanel(me) }
        me = Panel(ch?.name ?: "Options", items, 0)
        return me
    }

    private fun videoPanel(parent: Panel): Panel {
        val items = mutableListOf(PanelItem("Auto", exo.trackSelectionParameters.overrides.isEmpty()) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        })
        exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                items += PanelItem("${f.width}×${f.height}${if (f.bitrate > 0) " · ${f.bitrate / 1000} kbps" else ""}", g.isTrackSelected(i) && items.size > 0 && !exo.trackSelectionParameters.overrides.isEmpty()) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }
        return sub(parent, "Video track", items)
    }

    /** Programme list for the current channel (▶ while watching): now, upcoming and catch-up. */
    private suspend fun programmesPanel(): Panel? {
        val ch = st.channel ?: return null
        val n = nowS()
        val from = if (ch.archive) n - 3 * 86400 else n - 3600
        val list = runCatching { Net.arr("/api/iptv/epg/${ch.providerId}/${Uri.encode(ch.id)}?from=$from&to=${n + 24 * 3600}") }.getOrNull()?.objects()?.mapNotNull { Programme.from(it) }
        if (list.isNullOrEmpty()) { Toast.makeText(this, "No programme information for ${ch.name}", Toast.LENGTH_SHORT).show(); return null }
        val items = mutableListOf<PanelItem>()
        var lastDay = ""
        val dayFmt = java.text.SimpleDateFormat("EEEE d MMM", Locale.getDefault())
        var curIdx = 0
        list.filter { it.stop > from }.forEach { p ->
            val day = dayFmt.format(java.util.Date(p.start * 1000))
            if (day != lastDay) { items += PanelItem(day, header = true); lastDay = day }
            val state = when { p.isNow -> "  ● NOW"; p.stop <= n -> if (ch.archive) "  ⟲" else ""; else -> "" }
            if (p.isNow) curIdx = items.size
            items += PanelItem("${fmtTime(p.start)}  ${p.title}$state", p.isNow) {
                when {
                    p.isNow -> start(LiveSource(ch), 0.0)
                    p.stop <= n && ch.archive -> start(CatchupSource(ch.providerId, ch.id, p.start, p.stop, p.title, ch.name), 0.0)
                    p.stop <= n -> Toast.makeText(this, "No catch-up on this channel", Toast.LENGTH_SHORT).show()
                    else -> lifecycleScope.launch { runCatching { Repo.record(ch, p) }.onSuccess { Toast.makeText(this@PlayerActivity, it, Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(this@PlayerActivity, it.message, Toast.LENGTH_LONG).show() } }
                }
            }
        }
        return Panel("${ch.name} · OK: watch, catch-up (⟲) or record", items, curIdx)
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

    private fun liveAction(i: Int) {
        val ch = st.channel
        val p = ch?.onAir
        val src = source
        when (i) {
            0 -> zap(-1)
            4 -> zap(1)
            2 -> togglePause()
            1, 3 -> {
                val back = i == 1
                if (exo.isCurrentMediaItemSeekable) {
                    val target = (exo.currentPosition + if (back) -10_000 else 10_000).coerceAtLeast(0)
                    exo.seekTo(target)
                } else if (back && ch != null && p != null && ch.archive) {
                    // No time-shift buffer: rewind into catch-up, 30 s behind live.
                    start(CatchupSource(ch.providerId, ch.id, p.start, p.stop, p.title, ch.name), (nowS() - p.start - 30).toDouble().coerceAtLeast(0.0))
                } else Toast.makeText(this, if (back) "Rewind needs catch-up on this channel" else "Already live", Toast.LENGTH_SHORT).show()
            }
            5 -> if (ch != null && p != null && ch.archive) start(CatchupSource(ch.providerId, ch.id, p.start, p.stop, p.title, ch.name), 0.0)
                 else Toast.makeText(this, "This channel has no catch-up", Toast.LENGTH_SHORT).show()
            6 -> { if (src is LiveSource) { exo.seekToDefaultPosition(); exo.play() } else ch?.let { start(LiveSource(it), 0.0) } }
            7 -> if (ch != null && p != null) lifecycleScope.launch {
                runCatching { Repo.record(ch, p) }.onSuccess { Toast.makeText(this@PlayerActivity, it, Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(this@PlayerActivity, it.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** FHD / 60 FPS / STEREO badges from the stream that is actually playing. */
    private fun updateBadges() {
        val v = exo.videoFormat; val a = exo.audioFormat
        val l = mutableListOf<String>()
        v?.height?.takeIf { it > 0 }?.let { h -> l += when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; else -> "SD" } }
        v?.frameRate?.takeIf { it > 1 }?.let { l += "${Math.round(it)} FPS" }
        a?.channelCount?.takeIf { it > 0 }?.let { c -> l += when (c) { 1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; else -> "${c}CH" } }
        if (!st.live) desc?.method?.let { m -> l += if (m.startsWith("direct")) "DIRECT PLAY" else if (m.contains("copy") || m.contains("remux")) "DIRECT STREAM" else "TRANSCODE" }
        if (l != st.badges) st.badges = l
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
                back || code == KeyEvent.KEYCODE_DPAD_LEFT -> st.panel = p.parent?.let { it.copy() }
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

    private fun openLiveOverlay() { st.liveBtn = 2; st.liveZone = 0; st.liveOverlay = true; poke() }

    private fun liveKey(code: Int, repeat: Int, back: Boolean): Boolean {
        val ov = st.controls && st.liveOverlay
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            st.digits = (st.digits + (code - KeyEvent.KEYCODE_0)).takeLast(5); digitsAt = System.currentTimeMillis(); poke(); return true
        }
        when {
            back -> if (st.controls) st.controls = false else finish()
            // Overlay open (OK): ▲ ▼ move between the seek bar and the buttons instead of changing channel.
            ov && code == KeyEvent.KEYCODE_DPAD_UP -> { st.liveZone = 1; poke() }
            ov && code == KeyEvent.KEYCODE_DPAD_DOWN -> { st.liveZone = 0; poke() }
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP -> zap(-1)
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN -> zap(1)
            // Seek bar selected: ◀ ▶ rewind / forward, OK play / pause.
            ov && st.liveZone == 1 && code == KeyEvent.KEYCODE_DPAD_LEFT -> { liveAction(1); poke() }
            ov && st.liveZone == 1 && code == KeyEvent.KEYCODE_DPAD_RIGHT -> { liveAction(3); poke() }
            ov && st.liveZone == 1 && isOk(code) -> { if (repeat == 0) togglePause(); poke() }
            // Buttons selected: ◀ ▶ move between them, OK presses the selected one (like TiviMate).
            ov && code == KeyEvent.KEYCODE_DPAD_LEFT -> { st.liveBtn = (st.liveBtn - 1).coerceAtLeast(0); poke() }
            ov && code == KeyEvent.KEYCODE_DPAD_RIGHT -> { st.liveBtn = (st.liveBtn + 1).coerceAtMost(7); poke() }
            ov && isOk(code) -> { if (repeat == 0) liveAction(st.liveBtn); poke() }
            // Overlay closed (or just the banner after a channel change): OK opens it, ◀ channel list, ▶ programme guide.
            isOk(code) -> { if (repeat == 0) openLiveOverlay() }
            code == KeyEvent.KEYCODE_DPAD_LEFT -> { if (repeat == 0 && Playback.zapList.size > 1) { st.chList = true; st.controls = false } }
            code == KeyEvent.KEYCODE_LAST_CHANNEL -> prevChannel?.let { pc -> Playback.zapList.indexOfFirst { it.id == pc.id }.takeIf { it >= 0 }?.let { zapTo(it) } }
            code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS -> source?.let { st.panel = liveOptions(it); st.controls = true; poke() }
            code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_GUIDE || code == KeyEvent.KEYCODE_TV_INPUT -> lifecycleScope.launch { programmesPanel()?.let { st.panel = it; poke() } }
            code == KeyEvent.KEYCODE_INFO -> if (st.controls) st.controls = false else poke()
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
            code == KeyEvent.KEYCODE_CAPTIONS -> source?.let { st.panel = tracksPanel(it); poke() }
            code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS -> source?.let { st.panel = vodSettings(it); poke() }
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
        playerActions = null
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
        // Back on the Live TV screen, keep watching this channel in the preview window.
        if (src is LiveSource && (Nav.current == Screen.Live || Nav.current == Screen.Guide)) Preview.channel = src.channel
        if (src is WebSource) PlayerBus.closed(if (src.isLive) 0.0 else absPos() - (d?.startOffset ?: 0.0))
        Net.clearCache()
        exo.stop()
    }
}
