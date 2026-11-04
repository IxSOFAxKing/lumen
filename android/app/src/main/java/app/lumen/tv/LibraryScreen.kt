package app.lumen.tv

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A Plex library as Netflix-style rows (Plex's own hubs: Recently added, Top rated, By genre…). */
@Composable
fun LibraryScreen(lib: Library) {
    val mem = LocalMemory.current
    @Suppress("UNCHECKED_CAST")
    var hubs by remember { mutableStateOf(mem.data["hubs"] as List<Hub>?) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lib.key, AppState.resumeTick) {
        try { val h = Repo.hubsFor(lib.key); hubs = h; mem.data["hubs"] = h } catch (e: Exception) { if (hubs == null) error = e.message }
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusTarget = remember { mutableStateOf<Hero?>(null) }
    val hero = remember { mutableStateOf<Hero?>(null) }
    LaunchedEffect(Unit) { snapshotFlow { focusTarget.value }.collectLatest { t -> if (t != null) { delay(220); hero.value = t } } }
    LaunchedEffect(hubs) { if (hero.value == null) hubs?.firstOrNull()?.items?.firstOrNull()?.let { hero.value = Hero.of(it) } }
    val firstFr = remember { FocusRequester() }
    InitialFocus(firstFr)
    var rowsFocused by remember { mutableStateOf(false) }
    val type = if (lib.type == "movie") "1" else "2"

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(hero)
        Column(Modifier.fillMaxSize()) {
            val heroH by androidx.compose.animation.core.animateDpAsState(if (rowsFocused) 140.dp else 196.dp, androidx.compose.animation.core.tween(380), label = "heroH")
            HeroInfoOf(hero, rowsFocused, Modifier.fillMaxWidth().height(heroH).padding(start = 32.dp, top = if (rowsFocused) 20.dp else 30.dp, end = 360.dp))
            Row(Modifier.padding(start = 32.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                T(lib.title, 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp, top = 6.dp))
                Chip("Browse A–Z", false, memKey = "chip/az", focusRequester = firstFr) { Nav.push(Screen.Grid(lib.title, GridSource.Library(lib.key, type))) }
                Chip("Recently added", false, memKey = "chip/new") { Nav.push(Screen.Grid("${lib.title} · Recently added", GridSource.Library(lib.key, type, "addedAt:desc"))) }
                Chip("Unwatched", false, memKey = "chip/unwatched") { Nav.push(Screen.Grid("${lib.title} · Unwatched", GridSource.Library(lib.key, type, "titleSort", unwatched = true))) }
                Chip("Top rated", false, memKey = "chip/top") { Nav.push(Screen.Grid("${lib.title} · Top rated", GridSource.Library(lib.key, type, "audienceRating:desc"))) }
            }
            when {
                hubs == null && error != null -> Message("Couldn't load ${lib.title}", error)
                hubs == null -> Loading()
                else -> androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides NoScroll) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).onFocusChanged { rowsFocused = it.hasFocus }, contentPadding = PaddingValues(top = 4.dp, bottom = 200.dp)) {
                    itemsIndexed(hubs!!, key = { i, h -> "${h.id}-$i" }) { idx, hub ->
                        PlexRow("hub:${hub.id}", hub.title, hub.items, hub.wide, 32.dp, { scope.launch { listState.animateScrollToItem(idx) } },
                            onItemFocus = { focusTarget.value = Hero.of(it) }) { Nav.open(it) }
                    }
                }
                }
            }
        }
    }
}

/** Grid of Plex items: a whole library (paged), a collection, a playlist or a genre. */
@Composable
fun GridScreen(s: Screen.Grid) {
    val mem = LocalMemory.current
    val items = remember { mutableStateListOf<PlexItem>().also { l -> @Suppress("UNCHECKED_CAST") (mem.data["items"] as? List<PlexItem>)?.let { l.addAll(it) } } }
    var total by remember { mutableIntStateOf(mem.data["total"] as? Int ?: -1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<PlexItem?>(null) }
    val src = s.source
    val pageSize = 70

    suspend fun more() {
        if (loading || (total >= 0 && items.size >= total)) return
        loading = true
        try {
            when (src) {
                is GridSource.Library -> {
                    val (page, t) = Repo.libraryItems(src.lib, src.sort, items.size, pageSize, src.unwatched)
                    items.addAll(page); total = t
                    if (page.isEmpty()) total = items.size
                }
                is GridSource.Genre -> {
                    val (page, t) = Repo.libraryItems(src.lib, "titleSort", items.size, pageSize, genre = src.genre)
                    items.addAll(page); total = t
                    if (page.isEmpty()) total = items.size
                }
                is GridSource.Collection -> { val l = Repo.collection(src.key); items.clear(); items.addAll(l); total = l.size }
                is GridSource.Playlist -> { val l = Repo.playlist(src.key); items.clear(); items.addAll(l); total = l.size }
            }
            mem.data["items"] = items.toList(); mem.data["total"] = total
        } catch (e: Exception) { error = e.message; if (total < 0) total = items.size }
        loading = false
    }
    LaunchedEffect(src) { if (items.isEmpty()) more() }

    val wide = src is GridSource.Playlist && items.isNotEmpty() && items.all { it.isEpisode }
    val firstFr = remember { FocusRequester() }
    InitialFocus(firstFr)
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize().padding(start = 40.dp, top = 28.dp, end = 24.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                T(s.title, 24.sp, weight = FontWeight.Bold)
                val f = focused
                T(if (f != null) listOfNotNull(f.title, f.metaLine().takeIf { it.isNotBlank() }).joinToString("   ·   ") else if (total >= 0) "$total items" else "", 13.sp, C.text2)
            }
            if (src is GridSource.Playlist && items.isNotEmpty()) {
                PillButton("Play all", Icons.Filled.PlayArrow, primary = true) { Nav.play(items.first(), resume = false, queue = items.toList()) }
                Spacer(Modifier.width(8.dp))
                PillButton("Shuffle") { val q = items.shuffled(); Nav.play(q.first(), resume = false, queue = q) }
            }
        }
        Spacer(Modifier.height(14.dp))
        when {
            items.isEmpty() && loading -> Loading()
            items.isEmpty() && error != null -> Message("Couldn't load this list", error)
            items.isEmpty() && total == 0 -> Message("Nothing here")
            else -> androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides EdgeScroll) { LazyVerticalGrid(
                columns = GridCells.Fixed(if (wide) 4 else 7),
                state = gridState,
                contentPadding = PaddingValues(top = 6.dp, bottom = 80.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items, key = { i, it -> "${it.ratingKey}-$i" }) { i, it ->
                    val onFocus: () -> Unit = {
                        focused = it
                        if (i >= items.size - 21) { scope.launch { more() } }
                    }
                    val fr = if (i == 0) firstFr else null
                    if (wide) WideCard(it, "g/${it.ratingKey}-$i", onFocus = onFocus, onMenu = { Nav.openDetails(it) }, width = 200.dp, fr = fr) {
                        if (src is GridSource.Playlist) Nav.play(it, resume = true, queue = items.drop(i)) else Nav.open(it)
                    }
                    else PosterCard(it, "g/${it.ratingKey}-$i", onFocus = onFocus, onMenu = { Nav.openDetails(it) }, width = 112.dp, fr = fr) {
                        if (src is GridSource.Playlist && (it.type == "movie" || it.isEpisode)) Nav.play(it, resume = true, queue = items.drop(i)) else Nav.open(it)
                    }
                }
            } }
        }
    }
}
