package app.lumen.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

// ------------------------------------------------------------------------------------------------
// Phone (portrait) "More info" page: a tall artwork banner with the title logo, then centred chips for
// the facts, ratings and genres, a row of round action buttons, the overview, episodes as a list and
// the cast as large photos.
// ------------------------------------------------------------------------------------------------

private fun Modifier.tap(onTap: () -> Unit) = this.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onTap)

private val Pill = Color.White.copy(alpha = 0.09f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortraitDetail(
    item: PlexItem,
    seasons: List<PlexItem>,
    onDeck: PlexItem?,
    related: List<Hub>,
    seasonKey: String?,
    onSeason: (String) -> Unit,
    episodes: List<PlexItem>?,
    onPlay: () -> Unit,
    onRestart: (() -> Unit)?,
    playLabel: String?,
    onToggleWatched: () -> Unit,
) {
    val isShow = item.type == "show"
    LazyColumn(Modifier.fillMaxSize().background(C.bg), contentPadding = PaddingValues(bottom = 40.dp)) {
        // ---- banner ----
        item(key = "banner") {
            Box(Modifier.fillMaxWidth().height(470.dp)) {
                Img(Net.plexImg(item.art ?: item.grandparentArt ?: item.thumb, 1280, 720), Modifier.fillMaxSize(), ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.5f), 0.22f to Color.Transparent, 0.5f to Color.Transparent, 0.85f to C.bg.copy(alpha = 0.85f), 1f to C.bg,
                )))
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier
                    .padding(start = 8.dp, top = topSafe() + 4.dp).size(44.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.25f)).tap { Nav.pop() }.padding(10.dp))
                val name = if (item.isEpisode) item.grandparentTitle ?: item.title else item.title
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    TitleLogo(item.clearLogoPath()?.let { Net.plexImg(it, 800, 320) }, name, 100.dp, Alignment.Center) {
                        T(name, 32.sp, weight = FontWeight.Bold, maxLines = 3, lineHeight = 36.sp, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center, shadow = true)
                    }
                }
            }
        }
        // ---- facts, ratings, genres ----
        item(key = "facts") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(14.dp))
                val facts = listOfNotNull(
                    item.year?.toString(), item.contentRating,
                    if (isShow) item.childCount.takeIf { it > 0 }?.let { "$it Season${if (it > 1) "s" else ""}" } else item.durationMs.takeIf { it > 0 }?.let { fmtRuntime(it / 1000) },
                    resolutionLabel(item),
                )
                CenterChips(facts.map { { T(it, 14.sp, C.text, FontWeight.SemiBold) } })
                val ratings = ratingsOf(item)
                if (ratings.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.clip(RoundedCornerShape(50)).background(Pill).padding(horizontal = 14.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ratings.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(r.bg).padding(horizontal = 5.dp, vertical = 1.dp)) { T(r.badge, 11.sp, r.fg, FontWeight.Black) }
                                Spacer(Modifier.width(6.dp))
                                T(r.value, 14.sp, C.text, FontWeight.SemiBold)
                            }
                        }
                    }
                }
                if (item.genres.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    CenterChips(item.genres.take(6).map { g -> { T(g, 14.sp, C.text, FontWeight.Medium) } })
                }
                // ---- actions ----
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.height(50.dp).clip(RoundedCornerShape(50)).background(Color.White).tap(onPlay).padding(horizontal = if (playLabel != null) 22.dp else 30.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(26.dp))
                        if (playLabel != null) { Spacer(Modifier.width(8.dp)); T(playLabel, 16.sp, Color.Black, FontWeight.Bold) }
                    }
                    if (onRestart != null) RoundAction(Ico.Restart, "Play from the beginning", onRestart)
                    RoundAction(Icons.Filled.Check, if (item.watched) "Mark unwatched" else "Mark watched", onToggleWatched, on = item.watched)
                    RoundAction(Icons.Filled.MoreVert, "More") { Menus.item(item) }
                }
            }
        }
        // ---- overview ----
        item.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            item(key = "overview") {
                var open by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 26.dp).tap { open = !open }) {
                    T("Overview", 22.sp, weight = FontWeight.Bold)
                    item.tagline?.let { Spacer(Modifier.height(6.dp)); T(it, 14.sp, C.text2, FontWeight.Medium) }
                    Spacer(Modifier.height(8.dp))
                    T(summary, 15.sp, C.text.copy(alpha = 0.9f), maxLines = if (open) 50 else 6, lineHeight = 23.sp)
                }
            }
        }
        // ---- episodes ----
        if (isShow && seasons.isNotEmpty()) {
            item(key = "eps-head") {
                Column(Modifier.padding(top = 26.dp)) {
                    T("Episodes", 22.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
                    Spacer(Modifier.height(12.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(seasons, key = { _, s -> s.ratingKey }) { _, s ->
                            val sel = s.ratingKey == seasonKey
                            Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color.White else Pill).tap { onSeason(s.ratingKey) }.padding(horizontal = 18.dp, vertical = 9.dp)) {
                                T(s.title, 15.sp, if (sel) Color.Black else C.text, FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (episodes == null) item(key = "eps-loading") { Loading(Modifier.padding(24.dp)) }
            else items(episodes, key = { "ep" + it.ratingKey }) { ep -> EpisodeRow(ep) }
        }
        // ---- cast ----
        val cast = item.raw.optJSONArray("Role").objects().take(20)
        if (cast.isNotEmpty()) item(key = "cast") {
            Column(Modifier.padding(top = 26.dp)) {
                T("Cast", 22.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
                Spacer(Modifier.height(12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(cast, key = { i, _ -> "c$i" }) { _, r -> CastCard(r) }
                }
            }
        }
        // ---- more like this ----
        related.forEachIndexed { i, hub ->
            item(key = "rel$i") {
                Box(Modifier.padding(top = 14.dp)) { PlexRow("rel:${hub.id}", hub.title, hub.items, hub.wide, 18.dp, onRowFocused = {}) { Nav.openDetails(it) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CenterChips(chips: List<@Composable () -> Unit>) {
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { c -> Box(Modifier.clip(RoundedCornerShape(50)).background(Pill).padding(horizontal = 14.dp, vertical = 7.dp)) { c() } }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit, on: Boolean = false) {
    Icon(icon, label, tint = if (on) C.accent else Color.White, modifier = Modifier.size(50.dp).clip(CircleShape).background(Pill).tap(onClick).padding(13.dp))
}

@Composable
private fun EpisodeRow(ep: PlexItem) {
    Row(Modifier.fillMaxWidth().tap { Nav.play(ep, resume = true) }.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(C.card)) {
            Img(Net.plexImg(ep.thumb, 400, 225), Modifier.fillMaxSize())
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(6.dp))
            if (ep.progress > 0f) ProgressLine(ep.progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
            if (ep.watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T("${ep.index ?: ""}. ${ep.title}", 15.sp, weight = FontWeight.SemiBold, maxLines = 2)
            ep.summary?.let { Spacer(Modifier.height(3.dp)); T(it, 13.sp, C.text2, maxLines = 3, lineHeight = 17.sp) }
            Spacer(Modifier.height(4.dp))
            val date = ep.raw.str("originallyAvailableAt")?.let { d ->
                runCatching { java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d)!!) }.getOrNull()
            }
            T(listOfNotNull(ep.durationMs.takeIf { it > 0 }?.let { fmtRuntime(it / 1000) }, date).joinToString(" • "), 12.sp, C.text3)
        }
    }
}

@Composable
private fun CastCard(r: JSONObject) {
    Column(Modifier.width(130.dp)) {
        Box(Modifier.size(130.dp).clip(RoundedCornerShape(12.dp)).background(C.card), contentAlignment = Alignment.Center) {
            val thumb = r.str("thumb")
            if (thumb != null) Img(Net.plexImg(thumb, 300, 300), Modifier.fillMaxSize())
            else T(r.optString("tag").take(1), 36.sp, C.text2, FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        T(r.optString("tag"), 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
        r.str("role")?.let { T(it, 12.sp, C.text3, maxLines = 1) }
    }
}

private fun resolutionLabel(m: PlexItem): String? {
    val r = m.raw.optJSONArray("Media").objects().firstOrNull()?.str("videoResolution")?.lowercase() ?: return null
    return when (r) { "4k" -> "4K"; "sd" -> "SD"; else -> if (r.all { it.isDigit() }) "${r}p" else r.uppercase() }
}

private class RatingBadge(val badge: String, val value: String, val bg: Color, val fg: Color)

/** IMDb / Rotten Tomatoes / TMDB scores from Plex's rating sources. */
private fun ratingsOf(m: PlexItem): List<RatingBadge> {
    fun pct(v: Double) = "${(v * 10).toInt()}%"
    val out = mutableListOf<RatingBadge>()
    val seen = mutableSetOf<String>()
    fun add(image: String?, v: Double?) {
        if (image == null || v == null || v <= 0) return
        val b = when {
            image.startsWith("imdb") -> RatingBadge("IMDb", "%.1f".format(v), Color(0xFFF5C518), Color.Black)
            image.startsWith("rottentomatoes") && image.contains("upright") || image.contains("spilled") -> RatingBadge("Audience", pct(v), Color(0xFFFA320A), Color.White)
            image.startsWith("rottentomatoes") -> RatingBadge("RT", pct(v), Color(0xFFFA320A), Color.White)
            image.startsWith("themoviedb") -> RatingBadge("TMDB", pct(v), Color(0xFF01B4E4), Color.Black)
            else -> return
        }
        if (seen.add(b.badge)) out += b
    }
    m.raw.optJSONArray("Rating").objects().forEach { add(it.str("image"), it.num("value")) }
    add(m.raw.str("ratingImage"), m.rating)
    add(m.raw.str("audienceRatingImage"), m.audienceRating)
    return out
}
