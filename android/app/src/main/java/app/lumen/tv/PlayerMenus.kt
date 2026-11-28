package app.lumen.tv

import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import io.github.peerless2012.ass.media.type.AssRenderType
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

// The player's menus: audio & subtitles (and subtitle search), quality, picture, speed, sleep timer, subtitle sync,
// playback info, the VOD and Live TV option lists. Split out of PlayerActivity.kt; they work on its state.
/** Plex looks for subtitles online (OpenSubtitles) in your subtitle language; picking one adds it to the title. */
internal fun PlayerActivity.subtitleSearch(src: PlexSource) {
    val parent = st.panel
    val lang = AppState.uiStr("subtitleLang").ifBlank { "en" }.take(2)
    st.panel = Panel("Search for subtitles", listOf(PanelItem("Searching…")), 0, parent)
    lifecycleScope.launch {
        val found = runCatching { Plex.searchSubtitles(src.ratingKey, lang) }
        val list = found.getOrNull()
        st.panel = when {
            list == null -> Panel("Search for subtitles", listOf(PanelItem("Plex couldn't search: ${found.exceptionOrNull()?.message ?: "error"}")), 0, parent)
            list.isEmpty() -> Panel("Search for subtitles", listOf(PanelItem("Nothing found")), 0, parent)
            else -> Panel("Subtitles found", list.take(40).map { s ->
                val label = listOfNotNull(s.str("displayTitle") ?: s.str("language"), if (s.optBoolean("hearingImpaired")) "SDH" else null,
                    s.str("title")?.take(70), s.str("providerTitle")).joinToString(" · ")
                PanelItem(label) { downloadSubtitle(src, s) }
            }, 0, parent)
        }
    }
}

internal fun PlayerActivity.downloadSubtitle(src: PlexSource, s: JSONObject) {
    st.panel = null
    st.message = "Adding subtitles…"; hideAt = System.currentTimeMillis() + 20_000
    lifecycleScope.launch {
        val before = runCatching { Plex.subtitleIds(src.ratingKey) }.getOrDefault(emptySet())
        val ok = runCatching { Plex.downloadSubtitle(src.ratingKey, s) }
        if (ok.isFailure) { st.message = "Couldn't add them: ${ok.exceptionOrNull()?.message}"; hideAt = System.currentTimeMillis(); return@launch }
        // Plex downloads them in the background: wait for the new track to appear, then switch to it.
        var added: Int? = null
        for (i in 0 until 20) {
            delay(1000)
            added = runCatching { Plex.subtitleIds(src.ratingKey) }.getOrNull()?.firstOrNull { it !in before }
            if (added != null) break
        }
        hideAt = System.currentTimeMillis()
        if (added == null) { st.message = "Plex is still fetching them — they'll be in the subtitle menu shortly"; return@launch }
        st.message = "Subtitles added"
        src.choose(subtitleId = added)
        start(src, st.pos, keepState = true)
    }
}

/** Settings → Subtitles → Subtitles listed: only your subtitle language (default English) and tracks without a language. */
internal fun PlayerActivity.subShown(lang: String?): Boolean {
    if (AppState.uiStr("subtitleList", "mine") == "all") return true
    val l = lang?.lowercase()?.takeIf { it.isNotBlank() && it != "und" } ?: return true
    val want = AppState.uiStr("subtitleLang").ifBlank { "en" }.lowercase().take(2)
    return l.take(2) == want
}

internal fun PlayerActivity.langName(f: Format): String? = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage.takeIf { n -> n.isNotBlank() } ?: it }

internal fun PlayerActivity.tracksPanel(src: Source): Panel {
    val items = mutableListOf<PanelItem>()
    val d = desc
    val serverSide = src.isPlex && d != null && !d.method.startsWith("direct")
    if (serverSide) {
        // Plex is transcoding: it picks the streams, so ask it for a different one.
        items += PanelItem("Audio", header = true)
        src.audio.forEach { a -> items += PanelItem(a.label, a.selected) { src.choose(audioId = a.id); start(src, st.pos, keepState = true) } }
        items += PanelItem("Subtitles", header = true)
        items += PanelItem("Off", src.subtitles.none { it.selected }) { src.choose(subtitleId = 0); start(src, st.pos, keepState = true) }
        src.subtitles.filter { it.selected || subShown(it.lang) }.forEach { s -> items += PanelItem(s.label, s.selected) { src.choose(subtitleId = s.id); start(src, st.pos, keepState = true) } }
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
                if (!g.isTrackSelected(i) && !subShown(f.language)) continue
                // The language first, then the track's own title ("English · SDH"), so a track titled only "SDH" still says which language it is.
                val lang = langName(f) ?: "Unknown language"
                val label = listOfNotNull(lang, f.label?.takeIf { !it.equals(lang, true) },
                    if ((f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 && f.label?.contains("SDH", true) != true) "SDH" else null,
                    if ((f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) "Forced" else null).joinToString(" · ")
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
    if (src is PlexSource) items += PanelItem("Search for subtitles…") { subtitleSearch(src) }
    val first = items.indexOfFirst { !it.header && it.selected }.takeIf { it >= 0 } ?: items.indexOfFirst { !it.header }.coerceAtLeast(0)
    return Panel("Audio & subtitles", items, first)
}

internal fun PlayerActivity.qualityPanel(src: Source): Panel {
    val cur = (desc?.raw?.optString("quality") ?: "auto")
    val opts = listOf("auto" to "Auto (direct play when possible)", "original" to "Original", "20000" to "1080p · 20 Mbps", "12000" to "1080p · 12 Mbps",
        "8000" to "1080p · 8 Mbps", "4000" to "720p · 4 Mbps", "2000" to "720p · 2 Mbps", "720" to "480p · 1.5 Mbps")
    val items = opts.map { (v, l) -> PanelItem(l, v == cur) { src.choose(quality = v); start(src, st.pos, keepState = true) } }
    val method = desc?.method ?: ""
    return Panel("Quality  ·  now: $method", items, opts.indexOfFirst { it.first == cur }.coerceAtLeast(0))
}

internal fun PlayerActivity.sub(parent: Panel, title: String, items: List<PanelItem>): Panel =
    Panel(title, items, items.indexOfFirst { !it.header && it.selected }.takeIf { it >= 0 } ?: items.indexOfFirst { !it.header }.coerceAtLeast(0), parent)

internal fun PlayerActivity.aspectPanel(parent: Panel): Panel {
    val opts = listOf("Fit (original)" to AspectRatioFrameLayout.RESIZE_MODE_FIT, "Stretch to fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL, "Zoom (crop)" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
    return sub(parent, "Aspect ratio", opts.mapIndexed { i, (l, m) -> PanelItem(l, aspect == i) { aspect = i; view.resizeMode = m } })
}

internal fun PlayerActivity.sleepPanel(parent: Panel): Panel {
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

internal fun PlayerActivity.speedPanel(parent: Panel): Panel {
    val opts = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    return sub(parent, "Playback speed", opts.map { v -> PanelItem(if (v == 1f) "Normal" else "${v}×", speed == v) { speed = v; exo.setPlaybackSpeed(v) } })
}

internal fun PlayerActivity.loadThumbnails(src: Source, d: Desc) {
    if (!src.isPlex) { bif = null; bifFor = null; st.thumb = null; return }
    val part = d.raw.str("partId") ?: return
    if (bifFor == part) return
    bifFor = part; bif = null; thumbIdx = -1; st.thumb = null; noThumbs = false; singleFailed = false
    lifecycleScope.launch {
        val b = Bif.load(this@loadThumbnails, part)
        if (bifFor == part) bif = b
        if (b == null) noThumbs = true
    }
}

internal fun PlayerActivity.updateThumb() {
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
                    Net.http.newCall(okhttp3.Request.Builder().url(Plex.thumbUrl(part.toString(), i * 10000L) ?: return@runCatching null).build()).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.bytes()?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    }
                }.getOrNull()
            }
            if (img != null) { if (st.scrub != null) st.thumb = img }
            else if (!singleFailed) {
                singleFailed = true
                Toast.makeText(this@updateThumb, "No preview thumbnails for this video. In Plex, turn on \"Generate video preview thumbnails\" for the library (Manage Library → Edit → Advanced).", Toast.LENGTH_LONG).show()
            }
        }
        return
    }
    val i = b.indexAt(t)
    if (i == thumbIdx || thumbJob?.isActive == true) return
    thumbIdx = i
    thumbJob = lifecycleScope.launch { b.image(i)?.let { if (st.scrub != null) st.thumb = it } }
}

/** Once per stream: reopen it with the sound converted (Plex for the library, the Lumen server for IPTV). */
internal fun PlayerActivity.noSoundFallback() {
    val src = source ?: return
    val d = desc ?: return
    if (noSoundFor === d) return
    noSoundFor = d
    if (src is WebSource) { PlayerBus.error("audio format not supported on this device"); return }
    val mode = nextMode(d) ?: return
    st.message = "This device can't play this sound format — converting it…"
    start(src, if (src.isLive) 0.0 else absPos().coerceAtLeast(d.startPosition), mode, keepState = true)
}

internal fun PlayerActivity.syncPanel(src: Source, parent: Panel): Panel {
    val steps = listOf(-5000, -3000, -2000, -1500, -1000, -750, -500, -250, 0, 250, 500, 750, 1000, 1500, 2000, 3000, 5000)
    val items = steps.map { ms ->
        val label = when { ms == 0 -> "In sync (0 s)"; ms < 0 -> "Show ${"%.2f".format(-ms / 1000.0).trimEnd('0').trimEnd('.')} s earlier"; else -> "Show ${"%.2f".format(ms / 1000.0).trimEnd('0').trimEnd('.')} s later" }
        PanelItem(label, st.subOffsetMs == ms) { applySubtitleSync(src, ms) }
    }
    return sub(parent, "Subtitle sync", items)
}

/** Re-load the subtitles shifted by [ms] (the server shifts the cue times), keeping the playback position. */
internal fun PlayerActivity.applySubtitleSync(src: Source, ms: Int) {
    val d = desc ?: return
    if (!d.method.startsWith("direct") && src.isPlex) {
        Toast.makeText(this, "These subtitles are burned into the video while transcoding, so they can't be shifted. Set Quality to Auto/Original to sync them.", Toast.LENGTH_LONG).show()
        return
    }
    val subId = src.subtitles.firstOrNull { it.selected }?.id
    if (subId == null && d.subtitleUrl == null) { Toast.makeText(this, "Choose a subtitle first (Audio & subtitles).", Toast.LENGTH_LONG).show(); return }
    // The subtitle is fetched from Plex, shifted on this device and loaded next to the video.
    lifecycleScope.launch {
        val file = runCatching { if (subId != null) Plex.subtitleFile(subId, null, ms) else null }.getOrNull()
        if (file == null) { Toast.makeText(this@applySubtitleSync, "Couldn't load these subtitles to shift them.", Toast.LENGTH_LONG).show(); return@launch }
        loadSyncedSubtitle(d, Uri.parse(file), ms)
    }
}

internal fun PlayerActivity.loadSyncedSubtitle(d: Desc, u: Uri, ms: Int) {
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

internal fun PlayerActivity.subtitleSizePanel(parent: Panel): Panel {
    val opts = listOf(0.75f to "Small", 1f to "Normal", 1.25f to "Large", 1.5f to "Extra large")
    return sub(parent, "Subtitle size", opts.map { (v, l) -> PanelItem(l, subScale == v) {
        subScale = v
        val base = AppState.uiNum("subtitleSize", 100.0).toFloat() / 100f
        view.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * base * v)
    } })
}

/** Technical details of what's playing (like Plex's "Playback info" / TiviMate's stream info). */
internal fun PlayerActivity.infoPanel(parent: Panel): Panel {
    val v = exo.videoFormat; val a = exo.audioFormat; val d = desc
    val lines = listOfNotNull(
        "Method: ${d?.method ?: "?"}",
        v?.let { "Video: ${it.width}×${it.height}${if (it.frameRate > 0) " @ ${"%.2f".format(it.frameRate)} fps" else ""} · ${it.sampleMimeType?.substringAfter('/') ?: it.codecs ?: "?"}" },
        v?.bitrate?.takeIf { it > 0 }?.let { "Video bitrate: ${it / 1000} kbps" },
        a?.let { "Audio: ${it.sampleMimeType?.substringAfter('/') ?: "?"} · ${it.channelCount} ch · ${it.sampleRate} Hz" },
        a?.let { "Audio decoder: ${audioDecoder ?: "none — passthrough to the TV / receiver"}" },
        "Audio output: ${AppState.uiStr("audioOutput", "auto").let { if (it == "passthrough") "passthrough" else if (it == "stereo") "stereo (decoded)" else "decoded in the app" }}",
        d?.raw?.optString("quality")?.takeIf { it.isNotBlank() }?.let { "Quality setting: $it" },
        d?.raw?.optString("network")?.takeIf { it.isNotBlank() }?.let { "Network: $it" },
        "Buffered: ${"%.1f".format((exo.totalBufferedDuration) / 1000.0)} s",
        "Dropped frames: ${exo.videoDecoderCounters?.droppedBufferCount ?: 0}",
        d?.url?.let { "Stream: ${Uri.parse(it).path}" },
    )
    return Panel("Playback info", lines.map { PanelItem(it) }, 0, parent)
}

/** Plex player ⚙ menu. */
internal fun PlayerActivity.vodSettings(src: Source): Panel {
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

internal fun PlayerActivity.chaptersPanel(src: Source): Panel {
    val pos = st.pos
    val ch = src.chapters
    val cur = ch.indexOfLast { pos >= it.start }
    return Panel("Chapters", ch.mapIndexed { i, c -> PanelItem("${fmtClock(c.start)}   ${c.type}", i == cur) { seekAbs(c.start) } }, cur.coerceAtLeast(0))
}

/** TiviMate-style options for live TV (MENU, or long-press OK). */
internal fun PlayerActivity.liveOptions(src: Source): Panel {
    lateinit var me: Panel
    val ch = (src as? LiveSource)?.channel
    val p = st.channel?.onAir
    val items = mutableListOf<PanelItem>()
    items += PanelItem("Programme guide  ›") { lifecycleScope.launch { programmesPanel()?.let { st.panel = it.copy(parent = me); poke() } } }
    items += PanelItem("Channel list  ›") { st.chList = true; st.controls = false }
    if (ch != null && p != null && ch.archive) items += PanelItem("Watch from start") { start(CatchupSource(ch.providerId, ch.id, p.start, p.stop, p.title, ch.name), 0.0) }
    if (ch != null && p != null) items += PanelItem("Record this programme") {
        lifecycleScope.launch { runCatching { Repo.record(ch, p) }.onSuccess { Toast.makeText(this@liveOptions, it, Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(this@liveOptions, it.message, Toast.LENGTH_LONG).show() } }
    }
    if (ch != null) items += PanelItem(if (ch.isFav) "Remove from favourites" else "Add to favourites") {
        lifecycleScope.launch { runCatching { Repo.toggleFavorite(ch) }.onSuccess { added -> Toast.makeText(this@liveOptions, if (added) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show() } }
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

internal fun PlayerActivity.videoPanel(parent: Panel): Panel {
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
