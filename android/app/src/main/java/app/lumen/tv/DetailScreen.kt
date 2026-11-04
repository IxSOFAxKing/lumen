package app.lumen.tv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

private class DetailData(val item: PlexItem, val seasons: List<PlexItem>, val onDeck: PlexItem?, val related: List<Hub>)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(s: Screen.Detail) {
    val mem = LocalMemory.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(mem.data["d"] as DetailData?) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.ratingKey, reload, AppState.resumeTick) {
        try {
            val d = coroutineScope {
                val rel = async { runCatching { Repo.related(s.ratingKey) }.getOrDefault(emptyList()) }
                val item = Repo.meta(s.ratingKey)
                val seasons = if (item.type == "show") runCatching { Repo.children(item.ratingKey) }.getOrDefault(emptyList()) else emptyList()
                val onDeck = item.raw.optJSONObject("OnDeck")?.optJSONArray("Metadata").objects().firstOrNull()?.let { PlexItem.from(it) }
                DetailData(item, seasons, onDeck, rel.await())
            }
            data = d; mem.data["d"] = d; error = null
        } catch (e: Exception) { if (data == null) error = e.message }
    }
    val d = data
    if (d == null) {
        if (error != null) Message("Couldn't load this title", error, Modifier.padding(top = 120.dp)) else Loading(Modifier.padding(top = 160.dp))
        return
    }
    val item = d.item
    val isShow = item.type == "show"
    var seasonKey by remember(d) { mutableStateOf(s.seasonKey ?: (mem.data["season"] as? String) ?: d.onDeck?.parentRatingKey ?: d.seasons.firstOrNull { (it.index ?: 0) > 0 }?.ratingKey ?: d.seasons.firstOrNull()?.ratingKey) }
    @Suppress("UNCHECKED_CAST")
    var episodes by remember { mutableStateOf(mem.data["eps"] as List<PlexItem>?) }
    LaunchedEffect(seasonKey, reload, AppState.resumeTick) {
        val k = seasonKey ?: return@LaunchedEffect
        runCatching { Repo.children(k) }.onSuccess { episodes = it; mem.data["eps"] = it; mem.data["season"] = k }
    }
    val playFr = remember { FocusRequester() }
    InitialFocus(playFr)
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        Backdrop(Net.plexImg(item.art ?: item.thumb, 1280, 720), heightFraction = 1f, bleedLeft = 0.dp)
      // Scroll only as much as needed (Android TV's default keeps the focused row a third of the way down,
      // which pushed the title and cover art off the top).
      androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides NoScroll) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 40.dp, bottom = 120.dp)) {
            item(key = "info") {
              // Back on the Play / Mark watched buttons: return to the top so the artwork and title are visible.
              Row(Modifier.padding(start = 48.dp, end = 120.dp).onFocusChanged { if (it.hasFocus) scope.launch { listState.smoothScrollToItem(0) } }) {
                // Cover art
                val cover = if (item.type == "episode") item.grandparentThumb ?: item.thumb else item.thumb
                if (cover != null) {
                    Box(Modifier.width(168.dp).height(252.dp).clip(RoundedCornerShape(10.dp)).background(C.card)) {
                        Img(Net.plexImg(cover, 336, 504), Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(28.dp))
                }
                Column(Modifier.weight(1f).padding(end = 120.dp)) {
                    T(item.title, if (item.title.length > 26) 30.sp else 38.sp, weight = FontWeight.Bold, maxLines = 2, lineHeight = 40.sp)
                    Spacer(Modifier.height(8.dp))
                    T(item.metaLine(), 13.sp, C.text2)
                    item.tagline?.let { Spacer(Modifier.height(6.dp)); T(it, 13.sp, C.text, FontWeight.Medium) }
                    Spacer(Modifier.height(10.dp))
                    item.summary?.let { T(it, 13.sp, C.text2, maxLines = 4, lineHeight = 18.sp) }
                    val people = listOfNotNull(
                        item.raw.optJSONArray("Director").objects().take(2).mapNotNull { it.str("tag") }.takeIf { it.isNotEmpty() }?.let { "Director: ${it.joinToString(", ")}" },
                        item.raw.optJSONArray("Role").objects().take(4).mapNotNull { it.str("tag") }.takeIf { it.isNotEmpty() }?.let { "Starring: ${it.joinToString(", ")}" },
                    )
                    if (people.isNotEmpty()) { Spacer(Modifier.height(8.dp)); people.forEach { T(it, 12.sp, C.text3) } }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (isShow) {
                            val target = d.onDeck
                            val label = when {
                                target == null -> "Play"
                                target.viewOffsetMs > 0 -> "Resume ${target.episodeLabel ?: ""}"
                                else -> "Play ${target.episodeLabel ?: ""}"
                            }
                            PillButton(label.trim(), Icons.Filled.PlayArrow, primary = true, focusRequester = playFr, memKey = "btn/play") {
                                if (target != null) Nav.play(target, resume = true)
                                else scope.launch { runCatching { Repo.leaves(item.ratingKey) }.getOrNull()?.firstOrNull()?.let { Nav.play(it, resume = false) } }
                            }
                        } else {
                            val resume = item.viewOffsetMs > 0
                            PillButton(if (resume) "Resume ${fmtClock(item.resumeSec)}" else "Play", Icons.Filled.PlayArrow, primary = true, focusRequester = playFr, memKey = "btn/play") { Nav.play(item, resume = true) }
                            if (resume) PillButton("Play from beginning", Ico.Restart, memKey = "btn/start") { Nav.play(item, resume = false) }
                        }
                        PillButton(if (item.watched) "Mark unwatched" else "Mark watched", Icons.Filled.Check, memKey = "btn/watched") {
                            scope.launch {
                                runCatching { Repo.setWatched(item.ratingKey, !item.watched) }
                                    .onSuccess { Net.clearCache(); reload++ }
                                    .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                }
              }
            }
            if (isShow && d.seasons.isNotEmpty()) {
                item(key = "seasons") {
androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides EdgeScroll) {
                    LazyRow(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { listState.smoothScrollToItem(1) } }, contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 26.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(d.seasons, key = { _, it -> it.ratingKey }) { _, season ->
                            Chip(season.title, season.ratingKey == seasonKey, memKey = "season/${season.ratingKey}", onFocus = { if (seasonKey != season.ratingKey) seasonKey = season.ratingKey }) { seasonKey = season.ratingKey }
                        }
                    }
                    }
                }
                item(key = "episodes") {
                    val eps = episodes
                    if (eps == null) Loading() else CardRow("", startPad = 48.dp, onRowFocused = { scope.launch { listState.smoothScrollToItem(1) } }) {
                        itemsIndexed(eps, key = { _, it -> it.ratingKey }) { _, ep ->
                            EpisodeCard(ep, onMenu = { Menus.item(ep) }) { Nav.play(ep, resume = true) }
                        }
                    }
                }
            }
            val cast = item.raw.optJSONArray("Role").objects().take(20)
            if (cast.isNotEmpty()) item(key = "cast") { CastRow(cast) { scope.launch { listState.smoothScrollToItem(listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "cast" }?.index ?: return@launch) } } }
            d.related.forEachIndexed { i, hub ->
                item(key = "rel$i") {
                    PlexRow("rel:${hub.id}", hub.title, hub.items, hub.wide, 48.dp, onRowFocused = {
                        scope.launch { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "rel$i" }?.index?.let { listState.smoothScrollToItem(it) } }
                    }) { Nav.openDetails(it) }
                }
            }
        }
      }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val MinimalScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = SmoothScroll
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f
            offset < 0 || size > containerSize -> offset
            else -> trailing - containerSize
        }
    }
}

@Composable
private fun EpisodeCard(ep: PlexItem, onMenu: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.width(236.dp)) {
        Focusable(onClick = onClick, memKey = "ep/${ep.ratingKey}", onMenu = onMenu, modifier = Modifier.fillMaxWidth().height(133.dp), background = C.card) {
            Img(Net.plexImg(ep.thumb, 480, 270), Modifier.fillMaxSize())
            if (ep.progress > 0f) ProgressLine(ep.progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
            if (ep.watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        Spacer(Modifier.height(6.dp))
        T("${ep.index ?: ""}. ${ep.title}", 13.sp, weight = FontWeight.Medium)
        T(listOfNotNull(ep.durationMs.takeIf { it > 0 }?.let { fmtRuntime(it / 1000) }, ep.raw.str("originallyAvailableAt")).joinToString(" · "), 11.sp, C.text3)
        ep.summary?.let { T(it, 11.sp, C.text2, maxLines = 3, lineHeight = 15.sp) }
    }
}

@Composable
private fun CastRow(cast: List<JSONObject>, onRowFocused: () -> Unit) {
    CardRow("Cast", startPad = 48.dp, modifier = Modifier.padding(top = 18.dp), onRowFocused = onRowFocused) {
        itemsIndexed(cast, key = { i, _ -> "cast$i" }) { i, r ->
            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Focusable(onClick = {}, memKey = "cast/$i", shape = CircleShape, modifier = Modifier.size(72.dp), background = C.card) {
                    val thumb = r.str("thumb")
                    if (thumb != null) Img(Net.plexImg(thumb, 160, 160), Modifier.fillMaxSize())
                    else T(r.optString("tag").take(1), 24.sp, C.text2, FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.height(6.dp))
                T(r.optString("tag"), 11.sp, C.text)
                r.str("role")?.let { T(it, 10.sp, C.text3) }
            }
        }
    }
}

