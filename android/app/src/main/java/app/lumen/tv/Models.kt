package app.lumen.tv

import org.json.JSONObject

/** A Plex movie / show / season / episode / collection / playlist, as returned by the Lumen API. */
data class PlexItem(
    val ratingKey: String,
    val type: String,
    val title: String,
    val year: Int?,
    val summary: String?,
    val thumb: String?,
    val art: String?,
    val grandparentTitle: String?,
    val grandparentRatingKey: String?,
    val grandparentThumb: String?,
    val grandparentArt: String?,
    val parentRatingKey: String?,
    val parentThumb: String?,
    val parentTitle: String?,
    val index: Int?,
    val parentIndex: Int?,
    val durationMs: Long,
    val viewOffsetMs: Long,
    val viewCount: Int,
    val leafCount: Int,
    val viewedLeafCount: Int,
    val childCount: Int,
    val contentRating: String?,
    val audienceRating: Double?,
    val rating: Double?,
    val genres: List<String>,
    val tagline: String?,
    val studio: String?,
    val composite: String?,
    val raw: JSONObject,
) {
    val isEpisode get() = type == "episode"
    val isShowLike get() = type == "show" || type == "season"
    val watched get() = if (type == "show" || type == "season") leafCount > 0 && viewedLeafCount >= leafCount else viewCount > 0
    val progress: Float get() = if (durationMs > 0 && viewOffsetMs > 0) (viewOffsetMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val resumeSec: Double get() = viewOffsetMs / 1000.0

    /** Backdrop: the item's own art, or its show's. */
    val backdrop: String? get() = art ?: grandparentArt
    val poster: String? get() = if (type == "episode") (grandparentThumb ?: parentThumb ?: thumb) else if (type == "season") (thumb ?: parentThumb) else (thumb ?: composite)

    val episodeLabel: String? get() = if (type == "episode" && parentIndex != null && index != null) "S${parentIndex} E${index}" else null

    /** One-line metadata (year · rating · runtime · genres). */
    fun metaLine(): String = listOfNotNull(
        year?.toString(),
        contentRating,
        if (type == "movie" || type == "episode") durationMs.takeIf { it > 0 }?.let { fmtRuntime(it / 1000) } else null,
        if (type == "show") childCount.takeIf { it > 0 }?.let { "$it Season${if (it > 1) "s" else ""}" } else null,
        audienceRating?.let { "★ ${"%.1f".format(it)}" } ?: rating?.let { "★ ${"%.1f".format(it)}" },
        genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")

    companion object {
        fun from(j: JSONObject): PlexItem = PlexItem(
            ratingKey = j.optString("ratingKey"),
            type = j.optString("type"),
            title = j.optString("title"),
            year = j.num("year")?.toInt(),
            summary = j.str("summary"),
            thumb = j.str("thumb"),
            art = j.str("art"),
            grandparentTitle = j.str("grandparentTitle"),
            grandparentRatingKey = j.str("grandparentRatingKey"),
            grandparentThumb = j.str("grandparentThumb"),
            grandparentArt = j.str("grandparentArt"),
            parentRatingKey = j.str("parentRatingKey"),
            parentThumb = j.str("parentThumb"),
            parentTitle = j.str("parentTitle"),
            index = j.num("index")?.toInt(),
            parentIndex = j.num("parentIndex")?.toInt(),
            durationMs = j.num("duration")?.toLong() ?: 0L,
            viewOffsetMs = j.num("viewOffset")?.toLong() ?: 0L,
            viewCount = j.num("viewCount")?.toInt() ?: 0,
            leafCount = j.num("leafCount")?.toInt() ?: 0,
            viewedLeafCount = j.num("viewedLeafCount")?.toInt() ?: 0,
            childCount = j.num("childCount")?.toInt() ?: 0,
            contentRating = j.str("contentRating"),
            audienceRating = j.num("audienceRating"),
            rating = j.num("rating"),
            genres = j.optJSONArray("Genre").objects().mapNotNull { it.str("tag") },
            tagline = j.str("tagline"),
            studio = j.str("studio"),
            composite = j.str("composite"),
            raw = j,
        )
    }
}

data class Hub(val id: String, val title: String, val type: String, val items: List<PlexItem>) {
    /** Episode/clip rows look better as wide thumbnails. */
    val wide get() = type == "episode" || type == "clip" || id.contains("ondeck", true) || id.contains("continue", true)

    companion object {
        fun from(j: JSONObject) = Hub(
            id = j.optString("hubIdentifier"),
            title = j.optString("title"),
            type = j.optString("type"),
            items = j.optJSONArray("Metadata").objects().map { PlexItem.from(it) },
        )
    }
}

data class Library(val key: String, val title: String, val type: String) {
    companion object {
        fun from(j: JSONObject) = Library(j.optString("key"), j.optString("title"), j.optString("type"))
    }
}

data class Programme(val start: Long, val stop: Long, val title: String, val descr: String?, val subTitle: String?, val category: String?, val episode: String?) {
    val isNow get() = nowS() in start until stop
    val progress: Float get() = if (stop > start) ((nowS() - start).toFloat() / (stop - start)).coerceIn(0f, 1f) else 0f

    companion object {
        fun from(j: JSONObject?): Programme? {
            if (j == null) return null
            return Programme(
                start = j.num("start")?.toLong() ?: 0, stop = j.num("stop")?.toLong() ?: 0,
                title = j.optString("title"), descr = j.str("descr"), subTitle = j.str("sub_title"), category = j.str("category"), episode = j.str("episode"),
            )
        }
    }
}

data class Channel(
    val providerId: Int,
    val id: String,
    val num: Int?,
    val name: String,
    val logo: String?,
    val categoryId: String?,
    val archive: Boolean,
    val favLists: List<Int>,
    val now: Programme?,
    val next: Programme?,
    val programmes: List<Programme> = emptyList(),
) {
    val isFav get() = favLists.isNotEmpty()
    /** The programme on air now (now/next can begin in the future when there's a gap). */
    val onAir: Programme? get() = now?.takeIf { it.isNow }

    companion object {
        fun from(j: JSONObject, providerId: Int? = null): Channel = Channel(
            providerId = providerId ?: j.num("provider_id")?.toInt() ?: 0,
            id = j.optString("id"),
            num = j.num("num")?.toInt(),
            name = j.optString("name"),
            logo = j.str("logo"),
            categoryId = j.str("category_id"),
            archive = (j.num("archive") ?: 0.0) > 0,
            favLists = j.optJSONArray("fav_lists")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList(),
            now = Programme.from(j.optJSONObject("now")),
            next = Programme.from(j.optJSONObject("next")),
            programmes = j.optJSONArray("programmes").objects().mapNotNull { Programme.from(it) },
        )
    }
}

data class Provider(val id: Int, val name: String, val enabled: Boolean) {
    companion object {
        fun from(j: JSONObject) = Provider(j.optInt("id"), j.optString("name"), j.optBoolean("enabled", true))
    }
}

/** A Live TV group: favourite list, recently watched, all channels or a provider category. */
data class Group(val id: String, val name: String, val count: Int? = null)

fun nowS(): Long = System.currentTimeMillis() / 1000

fun fmtRuntime(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun fmtClock(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

private val timeFmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
fun fmtTime(epochSec: Long): String = timeFmt.format(java.util.Date(epochSec * 1000))

/** Plex's title-logo artwork ("clearLogo") for a movie or show, like Plex/Plezy show in the banner. */
fun PlexItem.clearLogoPath(): String? {
    raw.optJSONArray("Image").objects().firstOrNull { it.optString("type") == "clearLogo" }?.str("url")?.let { return it }
    val key = when (type) { "episode" -> grandparentRatingKey; "season" -> parentRatingKey; "movie", "show" -> ratingKey; else -> null } ?: return null
    return "/library/metadata/$key/clearLogo"
}

/** Banner metadata line: S1 E4 • 77% • TV-14 • 48min • October 20, 2026 */
fun PlexItem.heroMeta(): String {
    val date = raw.str("originallyAvailableAt")?.let { d ->
        runCatching { java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault()).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d)!!) }.getOrNull()
    }
    return listOfNotNull(
        episodeLabel?.replace(" ", " "),
        (audienceRating ?: rating)?.let { "${(it * 10).toInt()}%" },
        contentRating,
        if (type == "show") childCount.takeIf { it > 0 }?.let { "$it Season${if (it > 1) "s" else ""}" } else durationMs.takeIf { it > 0 }?.let { "${it / 60000}min" },
        if (isEpisode) date else year?.toString(),
        if (!isEpisode) genres.take(2).joinToString(", ").takeIf { it.isNotBlank() } else null,
    ).joinToString("  •  ")
}
