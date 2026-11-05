package app.lumen.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------------------------------------
// Phone (portrait) layout, in the style of mobile Plex clients: bottom navigation, a big swipeable
// featured banner, and rows below.
// ------------------------------------------------------------------------------------------------

private fun Modifier.tapped(onTap: () -> Unit) = this.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onTap)

private data class NavItem(val label: String, val icon: ImageVector, val screen: Screen, val selected: (Screen) -> Boolean)

@Composable
fun BottomNav(current: Screen, modifier: Modifier = Modifier) {
    val items = buildList {
        add(NavItem("Home", Icons.Filled.Home, Screen.Home) { it == Screen.Home })
        AppState.libraries.firstOrNull()?.let { l -> add(NavItem("Libraries", Ico.Library, Screen.Lib(l)) { it is Screen.Lib }) }
        if (AppState.providers.isNotEmpty()) {
            add(NavItem("Live TV", Ico.LiveTv, Screen.Live) { it == Screen.Live || it == Screen.Recordings })
            add(NavItem("Guide", Ico.Guide, Screen.Guide) { it == Screen.Guide })
        }
        add(NavItem("Search", Icons.Filled.Search, Screen.Search) { it == Screen.Search })
        add(NavItem("Settings", Icons.Filled.Settings, Screen.Settings) { it == Screen.Settings })
    }
    Row(modifier.fillMaxWidth().height(64.dp).background(C.bg2.copy(alpha = 0.97f)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { n ->
            val sel = n.selected(current)
            Column(Modifier.weight(1f).tapped { Nav.root(n.screen) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(n.icon, n.label, tint = if (sel) Color.White else C.text3, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(3.dp))
                T(n.label, 11.sp, if (sel) Color.White else C.text3, if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Title + quick actions shown over the top of phone screens. */
@Composable
fun MobileTopBar(title: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Row(modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        T(title, 24.sp, weight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.Refresh, "Refresh", tint = Color.White, modifier = Modifier.size(40.dp).clip(CircleShape).tapped {
            Net.clearCache(); AppState.resumeTick++
        }.padding(8.dp))
        if (AppState.plexOnline) Icon(Icons.Filled.Person, "Switch user", tint = Color.White, modifier = Modifier.size(40.dp).clip(CircleShape).tapped {
            scope.launch { AppState.checkProfile(force = true) }
        }.padding(8.dp))
    }
}

private fun typeLabel(m: PlexItem) = when (m.type) { "movie" -> "Movie"; "show" -> "TV Show"; "episode" -> "Episode"; "season" -> "Season"; else -> null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PortraitHome(d: HomeData) {
    val feat = d.featured.ifEmpty { (d.rows.firstOrNull() as? HomeRow.Plex)?.items?.take(6).orEmpty() }
    val showHero = feat.isNotEmpty() && !AppState.uiList("homeHidden").contains("hero")
    val pager = rememberPagerState { feat.size }
    var auto by remember { mutableStateOf(AppState.uiBool("heroAutoplay", true)) }
    LaunchedEffect(auto, pager.currentPage, pager.isScrollInProgress) {
        if (!auto || feat.size < 2 || pager.isScrollInProgress) return@LaunchedEffect
        delay((AppState.uiNum("heroInterval", 8.0) * 1000).toLong().coerceAtLeast(4000))
        pager.animateScrollToPage((pager.currentPage + 1) % feat.size)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "hero") {
            if (showHero) Box(Modifier.fillMaxWidth().height(520.dp)) {
                HorizontalPager(pager, Modifier.fillMaxSize()) { i -> HeroPage(feat[i]) }
                MobileTopBar("Home")
            } else MobileTopBar("Home", Modifier.padding(bottom = 12.dp))
        }
        if (showHero && feat.size > 1) item(key = "dots") {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (auto) Ico.Pause else Icons.Filled.PlayArrow, if (auto) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(28.dp).tapped { auto = !auto }.padding(4.dp))
                Spacer(Modifier.width(10.dp))
                feat.indices.forEach { i ->
                    val on = i == pager.currentPage
                    Box(Modifier.padding(horizontal = 3.dp).size(if (on) 22.dp else 7.dp, 7.dp).clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.White.copy(alpha = 0.4f)))
                }
            }
        }
        itemsIndexed(d.rows, key = { _, r -> r.key }) { _, row ->
            Box(Modifier.padding(top = 14.dp)) {
                when (row) {
                    is HomeRow.Plex -> PlexRow(row.key, "${row.title}  ›", row.items, row.wide, 16.dp) { if (it.type == "playlist") Nav.openDetails(it) else Nav.open(it) }
                    is HomeRow.Channels -> CardRow("${row.title}  ›", startPad = 16.dp) {
                        itemsIndexed(row.channels.distinctBy { "${it.providerId}:${it.id}" }, key = { _, c -> "${c.providerId}:${c.id}" }) { _, ch ->
                            ChannelCard(ch, "${row.key}/${ch.providerId}:${ch.id}", onMenu = { Menus.channel(ch, row.channels, row.title) }) { Nav.playLive(ch, row.channels, row.title) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPage(m: PlexItem) {
    Box(Modifier.fillMaxSize().tapped { Nav.openDetails(m) }) {
        Img(Net.plexImg(m.art ?: m.grandparentArt ?: m.thumb, 1280, 720), Modifier.fillMaxSize(), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.22f to Color.Transparent, 0.55f to Color.Transparent, 1f to C.bg)))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val name = if (m.isEpisode) m.grandparentTitle ?: m.title else m.title
            TitleLogo(m.clearLogoPath()?.let { Net.plexImg(it, 800, 320) }, name, 84.dp, Alignment.Center) {
                T(name, 30.sp, weight = FontWeight.Bold, maxLines = 2, lineHeight = 34.sp, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            T(listOfNotNull(typeLabel(m), m.contentRating, m.year?.toString()).joinToString(" • "), 14.sp, C.text, FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            // Play pill: shows how much is left when you've started it
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White).tapped {
                    if (m.isShowLike) Nav.openDetails(m) else Nav.play(m, resume = true)
                }.padding(horizontal = 26.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                if (m.progress > 0f) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Black.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(m.progress).height(4.dp).background(Color.Black))
                    }
                    Spacer(Modifier.width(10.dp))
                    T("${((m.durationMs - m.viewOffsetMs) / 60000).coerceAtLeast(1)} min left", 15.sp, Color.Black, FontWeight.Bold)
                } else {
                    Spacer(Modifier.width(8.dp))
                    T(if (m.isShowLike) "Watch" else "Play", 15.sp, Color.Black, FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
            val summary = m.summary
            if (!summary.isNullOrBlank()) {
                val prefix = if (m.isEpisode) "S${m.parentIndex}, E${m.index}: " else ""
                T(prefix + summary, 13.sp, C.text2, maxLines = 2, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
            }
        }
    }
}

/** Phone: switch between Plex libraries at the top of the Libraries tab. */
@Composable
fun LibrarySwitcher(current: Library) {
    if (AppState.libraries.size < 2) return
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(AppState.libraries, key = { _, l -> l.key }) { _, l ->
            Chip(l.title, l.key == current.key) { if (l.key != current.key) Nav.root(Screen.Lib(l)) }
        }
    }
}
