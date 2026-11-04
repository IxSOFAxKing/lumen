package app.lumen.tv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Live TV, TiviMate style: groups │ channels with what's on │ details of the highlighted channel. */
@Composable
fun LiveScreen() {
    val prov = AppState.provider
    if (prov == null) { Message("No IPTV provider", "Add one in Settings → Server settings."); return }
    val mem = LocalMemory.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("UNCHECKED_CAST")
    var groups by remember { mutableStateOf(mem.data["groups"] as List<Group>?) }
    var group by remember { mutableStateOf(mem.data["group"] as String? ?: Net.prefs.liveGroup ?: AppState.favLists.firstOrNull()?.id ?: "__all") }
    @Suppress("UNCHECKED_CAST")
    var channels by remember { mutableStateOf(mem.data["channels"] as List<Channel>?) }
    var loadingGroup by remember { mutableStateOf<String?>(null) }
    var focusedCh by remember { mutableStateOf<Channel?>(null) }
    var groupsFocused by remember { mutableStateOf(false) }

    LaunchedEffect(prov.id) {
        runCatching { Repo.groups(prov.id) }.onSuccess { g -> groups = g; mem.data["groups"] = g; if (g.none { it.id == group }) group = "__all" }
    }
    LaunchedEffect(group, prov.id, AppState.resumeTick) {
        loadingGroup = group
        // Short pause so scrolling through groups doesn't fire a request for each one.
        delay(if (channels == null) 0 else 250)
        val g = group
        runCatching { Repo.channelsIn(prov.id, g) }.onSuccess { if (g == group) { channels = it; mem.data["channels"] = it; mem.data["group"] = g; Net.prefs.liveGroup = g } }
            .onFailure { if (g == group) channels = emptyList() }
        loadingGroup = null
    }
    val groupName = groups?.firstOrNull { it.id == group }?.name?.removePrefix("★  ") ?: "Channels"
    val firstChFr = remember { FocusRequester() }
    InitialFocus(firstChFr)
    val chState = rememberLazyListState()

    Row(Modifier.fillMaxSize().padding(top = 20.dp)) {
        // ---- groups ----
        Column(Modifier.width(if (groupsFocused) 200.dp else 150.dp).fillMaxHeight().onFocusChanged { groupsFocused = it.hasFocus }) {
            T("Live TV", 20.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            if (AppState.providers.size > 1) {
                Box(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                    Chip(prov.name, true, memKey = "prov") {
                        val list = AppState.providers
                        val next = list[(list.indexOf(prov) + 1) % list.size]
                        mem.data.clear(); groups = null; channels = null; group = "__all"
                        AppState.selectProvider(next.id)
                    }
                }
            }
            val gl = groups
            if (gl == null) Loading() else LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 60.dp)) {
                itemsIndexed(gl, key = { _, g -> g.id }) { _, g ->
                    Focusable(
                        onClick = { group = g.id; scope.launch { delay(50); runCatching { firstChFr.requestFocus() } } },
                        onFocus = { group = g.id },
                        memKey = "group/${g.id}", shape = RoundedCornerShape(8.dp), focusedScale = 1f, outline = false,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        background = if (g.id == group) Color.White.copy(alpha = 0.10f) else Color.Transparent, focusedBackground = Color.White,
                    ) { f ->
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(g.name, 13.sp, if (f) Color.Black else if (g.id == group) C.text else C.text2, if (g.id == group) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            g.count?.takeIf { it > 0 }?.let { T(it.toString(), 11.sp, if (f) Color.DarkGray else C.text3) }
                        }
                    }
                }
            }
        }
        // ---- channels ----
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.padding(start = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                T(groupName, 16.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                channels?.let { T("${it.size} channels", 12.sp, C.text3) }
                if (loadingGroup != null) { Spacer(Modifier.width(10.dp)); T("Loading…", 12.sp, C.text3) }
            }
            val list = channels
            when {
                list == null -> Loading()
                list.isEmpty() -> Message("No channels here", if (group.startsWith("__fav")) "Press MENU (or hold OK) on a channel to add it to your favourites." else null)
                else -> LazyColumn(state = chState, contentPadding = PaddingValues(start = 4.dp, end = 8.dp, bottom = 80.dp)) {
                    itemsIndexed(list, key = { i, c -> "${c.providerId}:${c.id}:$i" }) { i, ch ->
                        ChannelLine(ch, fr = if (i == 0) firstChFr else null, onFocus = { focusedCh = ch },
                            onMenu = {
                                scope.launch {
                                    runCatching { Repo.toggleFavorite(ch) }.onSuccess { added ->
                                        Toast.makeText(ctx, if (added) "Added ${ch.name} to favourites" else "Removed ${ch.name} from favourites", Toast.LENGTH_SHORT).show()
                                        AppState.refresh()
                                        val upd = list.map { c -> if (c.id == ch.id && c.providerId == ch.providerId) c.copy(favLists = if (added) listOf(1) else emptyList()) else c }
                                        channels = if (!added && group.startsWith("__fav")) upd.filter { it.id != ch.id } else upd
                                    }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
                                }
                            }) { Nav.playLive(ch, list, groupName) }
                    }
                }
            }
        }
        // ---- details ----
        Column(Modifier.width(300.dp).fillMaxHeight().padding(start = 12.dp, end = 24.dp, top = 30.dp)) {
            val ch = focusedCh
            if (ch != null) ChannelDetails(ch)
        }
    }
}

@Composable
fun ChannelLine(ch: Channel, fr: FocusRequester? = null, onFocus: () -> Unit = {}, onMenu: (() -> Unit)? = null, onClick: () -> Unit) {
    Focusable(
        onClick = onClick, onFocus = onFocus, onMenu = onMenu, focusRequester = fr, memKey = "ch/${ch.providerId}:${ch.id}",
        shape = RoundedCornerShape(8.dp), focusedScale = 1.02f, outline = false,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), background = Color.White.copy(alpha = 0.04f), focusedBackground = Color.White.copy(alpha = 0.95f),
    ) { f ->
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (AppState.uiBool("chShowNumbers", true)) T(ch.num?.toString() ?: "", 13.sp, if (f) Color.DarkGray else C.text3, FontWeight.SemiBold, modifier = Modifier.width(38.dp))
            if (AppState.uiBool("chShowLogos", true)) {
                Box(Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(5.dp)).background(if (f) Color(0xFF1C212D) else Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    if (ch.logo != null) Img(Net.url(ch.logo), Modifier.fillMaxSize().padding(4.dp), ContentScale.Fit)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(ch.name, 14.sp, if (f) Color.Black else C.text, FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    if (ch.isFav) { Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Star, null, tint = C.accent, modifier = Modifier.size(12.dp)) }
                    if (ch.archive) { Spacer(Modifier.width(6.dp)); T("CATCH-UP", 8.sp, if (f) Color.DarkGray else C.text3, FontWeight.Bold) }
                }
                val p = ch.onAir
                T(p?.let { "${fmtTime(it.start)}  ${it.title}" } ?: "No programme info", 12.sp, if (f) Color.DarkGray else C.text2)
                if (p != null && AppState.uiBool("chShowProgress", true)) {
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(p.progress, Modifier.fillMaxWidth(0.6f), color = if (f) C.live else C.accent, track = if (f) Color.LightGray else Color.White.copy(alpha = 0.15f))
                }
            }
        }
    }
}

@Composable
fun ChannelDetails(ch: Channel) {
    Column {
        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(C.card), contentAlignment = Alignment.Center) {
            if (ch.logo != null) Img(Net.url(ch.logo), Modifier.fillMaxSize().padding(22.dp), ContentScale.Fit)
            else T(ch.name, 20.sp, weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        T(listOfNotNull(ch.num?.toString(), ch.name).joinToString("  "), 13.sp, C.text2, FontWeight.SemiBold)
        val p = ch.onAir
        if (p != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(3.dp)).background(C.live).padding(horizontal = 5.dp, vertical = 1.dp)) { T("NOW", 9.sp, Color.White, FontWeight.Bold) }
                Spacer(Modifier.width(8.dp))
                T("${fmtTime(p.start)} – ${fmtTime(p.stop)}", 12.sp, C.text2)
            }
            Spacer(Modifier.height(4.dp))
            T(p.title, 17.sp, weight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            ProgressLine(p.progress, Modifier.fillMaxWidth(), color = C.live)
            p.descr?.let { Spacer(Modifier.height(8.dp)); T(it, 12.sp, C.text2, maxLines = 6, lineHeight = 16.sp) }
        } else {
            Spacer(Modifier.height(8.dp)); T("No programme information", 13.sp, C.text3)
        }
        ch.next?.takeIf { it != ch.onAir }?.let { n ->
            Spacer(Modifier.height(14.dp))
            T("NEXT  ·  ${fmtTime(n.start)}", 11.sp, C.text3, FontWeight.Bold)
            T(n.title, 14.sp, C.text, FontWeight.Medium, maxLines = 2)
        }
        Spacer(Modifier.height(18.dp))
        T("OK  Watch     MENU  Favourite", 11.sp, C.text3)
    }
}
