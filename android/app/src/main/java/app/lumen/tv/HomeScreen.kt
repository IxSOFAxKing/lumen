package app.lumen.tv

import androidx.compose.animation.Crossfade
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What the big area at the top of the screen shows: the featured title, or whatever card has focus. */
data class Hero(val title: String, val kicker: String?, val meta: String?, val summary: String?, val backdrop: String?, val logo: String? = null, val item: PlexItem? = null) {
    companion object {
        fun of(m: PlexItem, kicker: String? = null): Hero {
            val isEp = m.isEpisode
            return Hero(
                title = if (isEp) (m.grandparentTitle ?: m.title) else m.title,
                kicker = kicker ?: if (isEp) listOfNotNull(m.episodeLabel, m.title).joinToString(" · ") else null,
                meta = m.metaLine(),
                summary = m.summary ?: m.tagline,
                backdrop = Net.plexImg(m.backdrop ?: m.thumb, 1280, 720),
                item = m,
            )
        }
        fun of(ch: Channel): Hero {
            val p = ch.onAir
            return Hero(
                title = p?.title ?: ch.name,
                kicker = "LIVE  ·  ${listOfNotNull(ch.num?.toString(), ch.name).joinToString(" ")}",
                meta = p?.let { "${fmtTime(it.start)} – ${fmtTime(it.stop)}" + (ch.next?.let { n -> "   ·   Next: ${n.title}" } ?: "") },
                summary = p?.descr, backdrop = null, logo = Net.url(ch.logo),
            )
        }
    }
}

sealed class HomeRow(val key: String, val title: String) {
    class Plex(key: String, title: String, val items: List<PlexItem>, val wide: Boolean) : HomeRow(key, title)
    class Channels(key: String, title: String, val channels: List<Channel>) : HomeRow(key, title)
}

class HomeData(val rows: List<HomeRow>, val featured: List<PlexItem>)

private suspend fun loadHome(): HomeData = coroutineScope {
    val sections = homeSections()
    val ids = sections.map { it.id }.toSet()
    val hasIptv = AppState.providers.isNotEmpty()
    val plexJob = async { if (AppState.plexOnline) runCatching { Repo.plexHome() }.getOrNull() else null }
    val favJob = async { if (hasIptv && "liveFavorites" in ids) runCatching { Repo.favorites() }.getOrNull() else null }
    val histJob = async { if (hasIptv && "recentChannels" in ids) runCatching { Repo.history() }.getOrNull() else null }
    val pinJobs = sections.filter { it.pin != null }.associate { s -> s.id to async { runCatching { loadPin(s.pin!!) }.getOrNull() } }

    val plex = plexJob.await()
    val hubs = plex?.optJSONArray("hubs").objects().map { Hub.from(it) }
    val cont = hubs.firstOrNull { Regex("continue|ondeck", RegexOption.IGNORE_CASE).containsMatchIn(it.id) }
    val hiddenHubs = AppState.ui.optJSONArray("hiddenHubs").objects().map { it.optString("id") }.toSet()

    val rows = mutableListOf<HomeRow>()
    for (s in sections) {
        when (s.id) {
            "continue" -> cont?.takeIf { it.items.isNotEmpty() }?.let { rows += HomeRow.Plex("continue", it.title.ifBlank { "Continue Watching" }, it.items, true) }
            "recentMovies" -> plex?.optJSONArray("recentMovies").objects().map { PlexItem.from(it) }.takeIf { it.isNotEmpty() }?.let { rows += HomeRow.Plex("recentMovies", "Recently Added Movies", it, false) }
            "recentShows" -> plex?.optJSONArray("recentShows").objects().map { PlexItem.from(it) }.takeIf { it.isNotEmpty() }?.let { rows += HomeRow.Plex("recentShows", "Recently Added TV Shows", it, false) }
            "plexHubs" -> hubs.filter { it !== cont && it.items.isNotEmpty() && it.id !in hiddenHubs }.forEach { h -> rows += HomeRow.Plex("hub:${h.id}", h.title, h.items, h.wide) }
            "playlists" -> plex?.optJSONArray("playlists").objects().map { PlexItem.from(it) }.takeIf { it.isNotEmpty() }?.let { rows += HomeRow.Plex("playlists", "Playlists", it, true) }
            "liveFavorites" -> favJob.await()?.takeIf { it.isNotEmpty() }?.let { rows += HomeRow.Channels("liveFavorites", "Live TV · Favorites", it) }
            "recentChannels" -> histJob.await()?.takeIf { it.isNotEmpty() }?.let { rows += HomeRow.Channels("recentChannels", "Recently Watched Channels", it.take(20)) }
            else -> pinJobs[s.id]?.await()?.let { rows += it }
        }
    }

    // Featured banner, per Settings → Appearance → Home → Featured banner shows.
    val featured: List<PlexItem> = when (AppState.uiStr("heroSource", "recent")) {
        "continue" -> cont?.items.orEmpty().filter { it.backdrop != null }
        "random" -> (plex?.optJSONArray("recentMovies").objects() + plex?.optJSONArray("recentShows").objects()).map { PlexItem.from(it) }.filter { it.art != null }.shuffled().take(8)
        "collection" -> AppState.uiStr("heroCollection").takeIf { it.isNotBlank() }?.let { k -> runCatching { Repo.collection(k) }.getOrNull() }.orEmpty().filter { it.art != null }.take(10)
        else -> plex?.optJSONArray("featured").objects().map { PlexItem.from(it) }
    }
    HomeData(rows, featured)
}

private suspend fun loadPin(p: JSONObject): HomeRow? {
    val key = "pin:${p.optString("id")}"
    val title = p.optString("title")
    return when (p.optString("type")) {
        "favList" -> Repo.favorites(p.optInt("listId")).takeIf { it.isNotEmpty() }?.let { HomeRow.Channels(key, title, it) }
        "iptvGroup" -> Repo.channelsIn(p.optInt("pid"), p.optString("cat")).takeIf { it.isNotEmpty() }?.let { HomeRow.Channels(key, title, it.take(60)) }
        "plexCollection" -> Repo.collection(p.optString("key")).takeIf { it.isNotEmpty() }?.let { HomeRow.Plex(key, title, it, false) }
        "plexPlaylist" -> Repo.playlist(p.optString("key")).takeIf { it.isNotEmpty() }?.let { HomeRow.Plex(key, title, it, it.all { i -> i.isEpisode }) }
        "plexGenre" -> Repo.libraryItems(p.optString("section"), "addedAt:desc", 0, 30, genre = p.optString("genre")).first.takeIf { it.isNotEmpty() }?.let { HomeRow.Plex(key, title, it, false) }
        else -> null
    }
}

@Composable
fun HomeScreen() {
    val mem = LocalMemory.current
    var data by remember { mutableStateOf(mem.data["home"] as HomeData?) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(AppState.resumeTick) {
        try { val d = loadHome(); data = d; mem.data["home"] = d; error = null }
        catch (e: Exception) { if (data == null) error = e.message }
    }
    val d = data
    when {
        d == null && error != null -> Message("Couldn't load Home", error)
        d == null -> Loading(Modifier.padding(top = 160.dp))
        d.rows.isEmpty() && d.featured.isEmpty() -> Message(
            "Welcome to Lumen",
            if (!AppState.plexConfigured && AppState.providers.isEmpty()) "Connect Plex and add an IPTV provider in Settings → Server settings." else if (AppState.plexConfigured && !AppState.plexOnline) "Your Plex server is unreachable: ${AppState.plexError ?: ""}" else "Nothing to show yet.",
            Modifier.padding(top = 120.dp),
        )
        else -> if (LocalPortrait.current) PortraitHome(d) else HomeContent(d)
    }
}

@Composable
private fun HomeContent(d: HomeData) {
    val mem = LocalMemory.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val showBillboard = d.featured.isNotEmpty() && !AppState.uiList("homeHidden").contains("hero")
    var featIndex by remember { mutableStateOf(mem.data["featIndex"] as? Int ?: 0) }
    var billboardFocused by remember { mutableStateOf(showBillboard && !mem.restorePending) }
    val focusTarget = remember { mutableStateOf<Hero?>(null) }
    val hero = remember { mutableStateOf(d.featured.getOrNull(featIndex)?.let { Hero.of(it) } ?: d.rows.firstOrNull()?.let { r -> (r as? HomeRow.Plex)?.items?.firstOrNull()?.let { Hero.of(it) } }) }
    val playFr = remember { FocusRequester() }
    val firstCardFr = remember { FocusRequester() }
    InitialFocus(if (showBillboard) playFr else firstCardFr)
    // Once you move down into the rows the banner shrinks, giving the rows more room (like Netflix).
    var rowIdx by remember { mutableIntStateOf(0) }
    var bbBtn by remember { mutableIntStateOf(0) }
    val compact = !billboardFocused
    val heroH by androidx.compose.animation.core.animateDpAsState(if (compact) 178.dp else if (showBillboard) 236.dp else 215.dp, tween(380), label = "heroH")
    val bbH by androidx.compose.animation.core.animateDpAsState(if (compact) 0.dp else 36.dp, tween(320), label = "bbH")

    // Debounced: the backdrop only changes once you settle on a card.
    // Only the banner reads these, so moving between cards doesn't redraw the rows.
    LaunchedEffect(Unit) { snapshotFlow { focusTarget.value }.collectLatest { t -> if (t != null) { delay(320); hero.value = t } } }
    // The featured banner rotates while it has focus.
    LaunchedEffect(billboardFocused, featIndex) {
        if (!billboardFocused || d.featured.size < 2 || !AppState.uiBool("heroAutoplay", true)) return@LaunchedEffect
        delay((AppState.uiNum("heroInterval", 8.0) * 1000).toLong().coerceAtLeast(4000))
        featIndex = (featIndex + 1) % d.featured.size
        mem.data["featIndex"] = featIndex
        hero.value = Hero.of(d.featured[featIndex])
    }

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(hero)
        Column(Modifier.fillMaxSize()) {
            HeroInfoOf(hero, compact, Modifier.fillMaxWidth().height(heroH).padding(start = 32.dp, top = if (compact) 22.dp else 34.dp, end = if (LocalPortrait.current) 20.dp else 360.dp))
            if (showBillboard) {
                val alpha by animateFloatAsState(if (billboardFocused) 1f else 0f, label = "bb")
                Row(
                    Modifier.padding(start = 32.dp).height(bbH).clipToBounds().alpha(alpha)
                        // Remote: ▶ past "More info" / ◀ before "Play" steps through the featured titles.
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown || d.featured.size < 2) return@onPreviewKeyEvent false
                            val code = ev.nativeKeyEvent.keyCode
                            val step = when {
                                code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && bbBtn == 1 -> 1
                                code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && bbBtn == 0 && featIndex > 0 -> -1
                                else -> 0
                            }
                            if (step == 0) false else {
                                featIndex = (featIndex + step + d.featured.size) % d.featured.size
                                mem.data["featIndex"] = featIndex
                                hero.value = Hero.of(d.featured[featIndex])
                                true
                            }
                        }
                        .onFocusChanged {
                            billboardFocused = it.hasFocus
                            if (it.hasFocus) { focusTarget.value = null; d.featured.getOrNull(featIndex)?.let { f -> hero.value = Hero.of(f) }; scope.launch { listState.smoothScrollToItem(0) } }
                        },
                ) {
                    val f = d.featured.getOrNull(featIndex)
                    PillButton(if (f?.isShowLike == true) "Watch" else if ((f?.viewOffsetMs ?: 0) > 0) "Resume" else "Play", Icons.Filled.PlayArrow, primary = true, focusRequester = playFr, memKey = "billboard/play", small = true, onFocus = { bbBtn = 0 }) {
                        f?.let { if (it.isShowLike) Nav.openDetails(it) else Nav.play(it, resume = true) }
                    }
                    Spacer(Modifier.width(10.dp))
                    PillButton("More info", Icons.Filled.Info, memKey = "billboard/info", small = true, onFocus = { bbBtn = 1 }) { f?.let { Nav.openDetails(it) } }
                    if (d.featured.size > 1) {
                        Spacer(Modifier.width(16.dp))
                        Row(Modifier.align(Alignment.CenterVertically)) {
                            d.featured.indices.forEach { i -> Box(Modifier.padding(horizontal = 3.dp).size(if (i == featIndex) 16.dp else 6.dp, 4.dp).background(if (i == featIndex) Color.White else Color.White.copy(alpha = 0.35f))) }
                        }
                    }
                }
            }
            // The focused row is snapped to the top of this area (onRowFocused); nothing else scrolls it.
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides NoScroll) {
            LazyColumn(
                state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 200.dp),
                modifier = Modifier.weight(1f).onPreviewKeyEvent { ev ->
                    // ▲ from the first row goes back to the banner's buttons.
                    if (showBillboard && rowIdx == 0 && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        runCatching { playFr.requestFocus() }; true
                    } else false
                },
            ) {
                itemsIndexed(d.rows, key = { _, r -> r.key }) { idx, row ->
                    val onRowFocused: () -> Unit = { rowIdx = idx; scope.launch { listState.smoothScrollToItem(idx) } }
                    when (row) {
                        is HomeRow.Plex -> PlexRow(
                            row.key, row.title, row.items, row.wide, 32.dp, onRowFocused,
                            firstFocus = if (idx == 0) firstCardFr else null,
                            onItemFocus = { focusTarget.value = Hero.of(it) },
                        ) { if (it.type == "playlist") Nav.openDetails(it) else Nav.open(it) }
                        is HomeRow.Channels -> CardRow(row.title, startPad = 32.dp, onRowFocused = onRowFocused) {
                            itemsIndexed(row.channels.distinctBy { "${it.providerId}:${it.id}" }, key = { _, c -> "${c.providerId}:${c.id}" }) { i, ch ->
                                ChannelCard(ch, "${row.key}/${ch.providerId}:${ch.id}", onFocus = { focusTarget.value = Hero.of(ch) }, onMenu = { Menus.channel(ch, row.channels, row.title) }, fr = if (idx == 0 && i == 0) firstCardFr else null) {
                                    Nav.playLive(ch, row.channels, row.title)
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
fun HeroBackdrop(h: androidx.compose.runtime.State<Hero?>, heightFraction: Float = 0.78f) = Backdrop(h.value?.backdrop, heightFraction)

@Composable
fun HeroInfoOf(h: androidx.compose.runtime.State<Hero?>, compact: Boolean, modifier: Modifier) = HeroInfo(h.value, modifier, compact)

/** Full-bleed artwork in the top-right, fading into the background like Netflix. */
@Composable
fun Backdrop(url: String?, heightFraction: Float = 0.78f, bleedLeft: androidx.compose.ui.unit.Dp = 64.dp) {
    // Extends under the (transparent) side menu, so artwork runs to the edge of the screen.
    Box(Modifier.fillMaxSize().layout { m, c ->
        val extra = bleedLeft.roundToPx()
        val w = c.maxWidth + extra
        val p = m.measure(c.copy(minWidth = w, maxWidth = w))
        layout(c.maxWidth, p.height) { p.place(-extra, 0) }
    }) {
        Crossfade(targetState = url, animationSpec = tween(450), label = "backdrop", modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.80f).fillMaxHeight(heightFraction)) { u ->
            Box(Modifier.fillMaxSize()) {
                Img(u, Modifier.fillMaxSize(), ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to C.bg, 0.35f to C.bg.copy(alpha = 0.55f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to C.bg)))
            }
        }
    }
}

@Composable
fun HeroInfo(hero: Hero?, modifier: Modifier = Modifier, compact: Boolean = false) {
    // New title slides up and fades in; the old one fades out.
    androidx.compose.animation.AnimatedContent(
        targetState = hero, modifier = modifier, label = "hero",
        contentKey = { it?.title + "|" + it?.kicker },
        transitionSpec = {
            (androidx.compose.animation.fadeIn(tween(380, delayMillis = 60)) + androidx.compose.animation.slideInVertically(tween(380)) { it / 10 }) togetherWith
                androidx.compose.animation.fadeOut(tween(160))
        },
    ) { h -> HeroBody(h, compact) }
}

@Composable
private fun HeroBody(h: Hero?, compact: Boolean) {
    Column {
        if (h == null) return@Column
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (h.logo != null) { Img(h.logo, Modifier.height(22.dp).width(48.dp), ContentScale.Fit); Spacer(Modifier.width(8.dp)) }
            h.kicker?.let { T(it.uppercase(), 11.sp, if (it.startsWith("LIVE")) C.live else C.accent, FontWeight.Bold) }
        }
        Spacer(Modifier.height(4.dp))
        T(h.title, if (compact) 24.sp else if (h.title.length > 28) 26.sp else 32.sp, weight = FontWeight.Bold, maxLines = if (compact) 1 else 2, lineHeight = 34.sp)
        Spacer(Modifier.height(6.dp))
        h.meta?.takeIf { it.isNotBlank() }?.let { T(it, 13.sp, C.text2) }
        Spacer(Modifier.height(8.dp))
        h.summary?.let { T(it, 13.sp, C.text2, maxLines = 3, lineHeight = 18.sp) }
    }
}
