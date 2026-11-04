package app.lumen.tv

import org.json.JSONObject

/** A stream to play, as described by the Lumen server (POST /api/.../play). */
data class Desc(
    val url: String,
    val type: String,               // file | hls | mpegts
    val startPosition: Double,      // seek here after loading (seconds, absolute)
    val startOffset: Double,        // the stream itself starts at this absolute position
    val subtitleUrl: String?,
    val method: String,
    val sessionId: String?,         // Lumen transcoder session
    val session: String?,           // Plex transcode session
    val restartOnSeek: Boolean,
    val duration: Double,
    val raw: JSONObject,
) {
    companion object {
        fun from(j: JSONObject) = Desc(
            url = Net.url(j.optString("url")) ?: "",
            type = j.optString("type", "file"),
            startPosition = j.num("seekTo") ?: j.num("startPosition") ?: 0.0,
            startOffset = j.num("startOffset") ?: 0.0,
            subtitleUrl = Net.url(j.str("subtitleUrl")),
            method = j.optString("method", "direct"),
            sessionId = j.str("sessionId"),
            session = j.str("session"),
            restartOnSeek = j.optBoolean("restartOnSeek"),
            duration = j.num("duration") ?: 0.0,
            raw = j,
        )
    }
}

data class Marker(val type: String, val start: Double, val end: Double)
data class StreamOpt(val id: Int, val label: String, val selected: Boolean)

/** Next more-compatible way to play after an error (same as the web player): direct → remux → transcode. */
fun nextMode(d: Desc): String? {
    val m = d.method
    return when {
        m.startsWith("direct") -> "remux"
        m.startsWith("plex-") -> "builtin"
        m.startsWith("remux") || m == "builtin-remux" -> "transcode"
        else -> null
    }
}

object Playback {
    /** Channels to zap through (set by whoever starts live playback). */
    var zapList: List<Channel> = emptyList()
    var zapTitle: String = ""

    suspend fun release(d: Desc?) {
        if (d == null) return
        d.sessionId?.let { runCatching { Net.delete("/api/stream/session/$it") } }
        d.session?.let { runCatching { Net.post("/api/plex/stop", JSONObject().put("session", it)) } }
    }

    suspend fun ping(d: Desc?) {
        if (d == null) return
        d.sessionId?.let { runCatching { Net.post("/api/stream/session/$it/ping") } }
        d.session?.let { runCatching { Net.post("/api/plex/ping", JSONObject().put("session", it)) } }
    }
}

/** Something the player can play. Each kind knows how to (re)open its stream and report progress. */
abstract class Source {
    open val title: String = ""
    open val subtitle: String = ""
    open val isLive: Boolean = false
    open val duration: Double = 0.0
    open val markers: List<Marker> = emptyList()
    open val hasNext: Boolean = false
    open val nextLabel: String? = null
    /** Plex lets you pick audio/subtitle streams and quality server-side (needed when transcoding). */
    open val audio: List<StreamOpt> = emptyList()
    open val subtitles: List<StreamOpt> = emptyList()
    open val isPlex: Boolean = false

    /** Load/reload the stream. mode = fallback mode after an error (remux/transcode/builtin). */
    abstract suspend fun open(offset: Double, mode: String? = null): Desc
    open suspend fun progress(pos: Double, dur: Double, state: String) {}
    open suspend fun next(): Source? = null
    open suspend fun prepare() {}
    /** Change Plex audio/subtitle/quality and reopen. */
    open fun choose(audioId: Int? = null, subtitleId: Int? = null, quality: String? = null) {}
}

private fun uiPrefs(): JSONObject {
    val u = AppState.ui
    return JSONObject().apply {
        for (k in listOf("audioLang", "subtitleMode", "subtitleLang", "subtitleSize", "qualityLocal", "qualityRemote")) if (u.has(k)) put(k, u.get(k))
    }
}

class PlexSource(val ratingKey: String, private val queue: List<String>? = null, private val queueIndex: Int = 0) : Source() {
    private var meta: PlexItem? = null
    private var last: JSONObject = JSONObject()
    private var current: Desc? = null
    private var quality: String? = null
    private var audioId: Int? = null
    private var subtitleId: Int? = null
    private var forceTranscode = false
    private var transcoder: String? = null
    private var scrobbled = false
    private var nextItem: PlexItem? = null
    private var nextResolved = false

    override val isPlex = true
    override val title get() = meta?.let { if (it.isEpisode) it.grandparentTitle ?: it.title else it.title } ?: last.optString("title")
    override val subtitle get() = meta?.let { m -> if (m.isEpisode) listOfNotNull(m.episodeLabel, m.title).joinToString(" · ") else listOfNotNull(m.year?.toString(), m.contentRating).joinToString(" · ") } ?: ""
    override val duration get() = last.num("duration") ?: ((meta?.durationMs ?: 0) / 1000.0)
    override val markers get() = last.optJSONArray("markers").objects().map { Marker(it.optString("type"), it.optDouble("startTimeOffset") / 1000, it.optDouble("endTimeOffset") / 1000) }
    override val audio get() = last.optJSONArray("audio").objects().map { StreamOpt(it.optInt("id"), it.optString("label"), it.optBoolean("selected")) }
    override val subtitles get() = last.optJSONArray("subtitles").objects().map { StreamOpt(it.optInt("id"), it.optString("label"), it.optBoolean("selected")) }
    override val hasNext get() = nextItem != null
    override val nextLabel get() = nextItem?.let { n -> if (n.isEpisode) listOfNotNull(n.episodeLabel, n.title).joinToString(" · ") else n.title }

    override suspend fun prepare() {
        meta = runCatching { Repo.meta(ratingKey) }.getOrNull()
        if (!nextResolved) {
            nextResolved = true
            nextItem = if (queue != null) queue.getOrNull(queueIndex + 1)?.let { k -> runCatching { Repo.meta(k) }.getOrNull() }
            else meta?.takeIf { it.isEpisode }?.grandparentRatingKey?.let { show ->
                runCatching { Repo.leaves(show) }.getOrNull()?.let { l -> val i = l.indexOfFirst { it.ratingKey == ratingKey }; if (i >= 0) l.getOrNull(i + 1) else null }
            }
        }
    }

    override suspend fun open(offset: Double, mode: String?): Desc {
        when (mode) {
            "remux" -> forceTranscode = true
            "transcode" -> { forceTranscode = true; if (quality == null || quality == "auto" || quality == "original") quality = "20000" }
            "builtin" -> { forceTranscode = true; transcoder = "builtin" }
        }
        val body = JSONObject().put("ratingKey", ratingKey).put("offset", offset).put("client", "native").put("prefs", uiPrefs())
            .put("forceTranscode", forceTranscode)
        quality?.let { body.put("quality", it) }
        audioId?.let { body.put("audioStreamID", it) }
        subtitleId?.let { body.put("subtitleStreamID", it) }
        transcoder?.let { body.put("transcoder", it) }
        val j = Net.post("/api/plex/play", body)
        last = j
        return Desc.from(j).also { current = it }
    }

    override fun choose(audioId: Int?, subtitleId: Int?, quality: String?) {
        if (audioId != null) this.audioId = audioId
        if (subtitleId != null) this.subtitleId = subtitleId
        if (quality != null) { this.quality = quality; if (quality == "auto") { forceTranscode = false; transcoder = null } else forceTranscode = quality != "original" }
        // A different stream needs Plex to transcode (the native player can't switch streams Plex picks).
        if (audioId != null || subtitleId != null) forceTranscode = forceTranscode || (current?.method?.startsWith("direct") == false)
    }

    override suspend fun progress(pos: Double, dur: Double, state: String) {
        runCatching { Net.post("/api/plex/timeline", JSONObject().put("ratingKey", ratingKey).put("state", state).put("time", pos).put("duration", dur).put("session", current?.session ?: JSONObject.NULL)) }
        if (!scrobbled && dur > 60) {
            val w = AppState.ui.opt("watchedAt")?.toString() ?: "90"
            val credits = markers.firstOrNull { it.type == "credits" }
            val reached = if (w == "credits") (credits?.let { pos >= it.start } ?: (pos / dur >= 0.95)) else pos / dur >= ((w.toDoubleOrNull() ?: 90.0) / 100).coerceIn(0.5, 0.99)
            if (reached) { scrobbled = true; runCatching { Repo.setWatched(ratingKey, true) } }
        }
    }

    override suspend fun next(): Source? {
        val n = nextItem ?: return null
        return if (queue != null) PlexSource(n.ratingKey, queue, queueIndex + 1) else PlexSource(n.ratingKey)
    }
}

class LiveSource(val channel: Channel) : Source() {
    override val isLive = true
    override val title get() = channel.name
    override val subtitle get() = channel.onAir?.let { "${fmtTime(it.start)} – ${fmtTime(it.stop)} · ${it.title}" } ?: ""
    override suspend fun open(offset: Double, mode: String?): Desc {
        val body = JSONObject().put("providerId", channel.providerId).put("channelId", channel.id)
        if (mode != null) body.put("mode", mode)
        return Desc.from(Net.post("/api/iptv/play/live", body))
    }
}

class CatchupSource(private val providerId: Int, private val channelId: String, private val start: Long, private val stop: Long, override val title: String, private val channelName: String) : Source() {
    override val subtitle get() = "$channelName · ${fmtTime(start)} – ${fmtTime(stop)} (catch-up)"
    override val duration get() = (minOf(stop, nowS()) - start).toDouble()
    override suspend fun open(offset: Double, mode: String?): Desc {
        val body = JSONObject().put("providerId", providerId).put("channelId", channelId).put("start", start).put("stop", stop).put("offset", offset)
        if (mode == "transcode") body.put("mode", "transcode")
        return Desc.from(Net.post("/api/iptv/play/catchup", body))
    }
}

class RecordingSource(private val id: Int, override val title: String, override val subtitle: String) : Source() {
    private var dur = 0.0
    override val duration get() = dur
    override suspend fun open(offset: Double, mode: String?): Desc {
        val body = JSONObject().put("offset", offset)
        if (mode == "transcode") body.put("mode", "transcode")
        return Desc.from(Net.post("/api/iptv/play/recording/$id", body)).also { dur = it.duration }
    }
}

/** Playback started from the web interface (WebActivity) through the LumenNative bridge. */
class WebSource(private var json: JSONObject) : Source() {
    override val title get() = json.optJSONObject("channel")?.str("name") ?: json.optString("title")
    override val subtitle get() = json.optString("subtitle")
    override val isLive get() = json.optBoolean("live")
    override val duration get() = json.optDouble("duration", 0.0)
    override val hasNext get() = json.optBoolean("hasNext")
    override val markers get() = json.optJSONArray("markers").objects().map { Marker(it.optString("type"), it.optDouble("start"), it.optDouble("end")) }
    /** Fallbacks for web-started playback are handled by the web app (it calls play() again). */
    override suspend fun open(offset: Double, mode: String?): Desc {
        return Desc(
            url = json.optString("url"), type = json.optString("type", "file"), startPosition = json.optDouble("startPosition", 0.0),
            startOffset = json.optDouble("startOffset", 0.0), subtitleUrl = json.str("subtitleUrl"), method = "web", sessionId = null, session = null,
            restartOnSeek = false, duration = json.optDouble("duration", 0.0), raw = json,
        )
    }
    override suspend fun progress(pos: Double, dur: Double, state: String) { PlayerBus.progress(pos - json.optDouble("startOffset", 0.0), dur, state) }
}

fun sourceFromRequest(req: JSONObject): Source? = when (req.optString("kind")) {
    "plex" -> PlexSource(req.optString("ratingKey"), req.optJSONArray("queue")?.strings())
    "live" -> {
        val pid = req.optInt("providerId"); val cid = req.optString("channelId")
        val ch = Playback.zapList.firstOrNull { it.providerId == pid && it.id == cid }
            ?: Channel(pid, cid, null, req.optString("name", "Live TV"), null, null, false, emptyList(), null, null)
        LiveSource(ch)
    }
    "catchup" -> CatchupSource(req.optInt("providerId"), req.optString("channelId"), req.optLong("start"), req.optLong("stop"), req.optString("title"), req.optString("channelName"))
    "recording" -> RecordingSource(req.optInt("id"), req.optString("title"), req.optString("subtitle"))
    else -> null
}
