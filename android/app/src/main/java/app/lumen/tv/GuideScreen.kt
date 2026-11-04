package app.lumen.tv

import android.view.KeyEvent as AKey
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HALF_HOUR = 1800L
private const val SPAN = 5400L          // 1.5 hours visible across the grid
private val CH_W = 168.dp
private val ROW_H = 48.dp

private fun floor30(t: Long) = t - (t % HALF_HOUR)

/** TV Guide: a programme grid you move around with the remote. OK watches (or catch-up), MENU records. */
@Composable
fun GuideScreen() {
    val prov = AppState.provider
    if (prov == null) { Message("No IPTV provider", "Add one in Settings → Server settings."); return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var group by remember { mutableStateOf(Net.prefs.liveGroup ?: "__all") }
    val channels = remember { mutableStateListOf<Channel>() }
    var total by remember { mutableIntStateOf(-1) }
    var gen by remember { mutableIntStateOf(0) }
    var busyGen by remember { mutableIntStateOf(-1) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val loadFrom = remember { floor30(nowS()) - 3 * 3600 }
    val loadTo = remember { nowS() + 14 * 3600 }
    var winStart by remember { mutableLongStateOf(floor30(nowS())) }
    var selRow by remember { mutableIntStateOf(0) }
    var selTime by remember { mutableLongStateOf(nowS()) }
    var clock by remember { mutableLongStateOf(nowS()) }
    var gridFocused by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    val gridFr = remember { FocusRequester() }
    val listState = remember(group, prov.id) { LazyListState() }

    LaunchedEffect(Unit) { while (true) { delay(30_000); clock = nowS() } }
    LaunchedEffect(prov.id) { runCatching { Repo.groups(prov.id) }.onSuccess { g -> groups = g; if (g.none { it.id == group }) group = "__all" } }

    // Each group change starts a new "generation"; stale loads can't block or overwrite the current one.
    suspend fun loadMore() {
        val my = gen
        if (busyGen == my || (total >= 0 && channels.size >= total)) return
        busyGen = my
        val g = group
        try {
            val (list, t) = Repo.guide(prov.id, g, loadFrom, loadTo, channels.size, 80)
            if (my == gen) { channels.addAll(list); total = t; loadError = null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (my == gen && total < 0) { total = channels.size; loadError = e.message ?: "Couldn't load the guide" }
        } finally {
            if (busyGen == my) busyGen = -1
        }
    }
    LaunchedEffect(group, prov.id, retry) {
        gen++
        channels.clear(); total = -1; selRow = 0; loadError = null
        loadMore()
    }
    InitialFocus(gridFr)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Preview.channel = null } }

    val ch = channels.getOrNull(selRow)
    val prog = ch?.programmes?.firstOrNull { selTime >= it.start && selTime < it.stop }

    fun ensureVisible(t: Long) {
        if (t < winStart) winStart = floor30(t)
        else if (t >= winStart + SPAN - HALF_HOUR / 2) winStart = floor30(t) - HALF_HOUR
    }
    fun moveRow(d: Int) {
        if (channels.isEmpty()) return
        selRow = (selRow + d).coerceIn(0, channels.size - 1)
        if (selRow > channels.size - 15) scope.launch { loadMore() }
        scope.launch {
            val first = listState.firstVisibleItemIndex
            val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(5)
            if (selRow < first + 1 || selRow > first + visible - 2) listState.animateScrollToItem((selRow - 2).coerceAtLeast(0))
        }
    }
    fun activate(menu: Boolean) {
        val c = ch ?: return
        val p = prog
        val n = nowS()
        when {
            menu && p != null && p.stop > n -> scope.launch { runCatching { Repo.record(c, p) }.onSuccess { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() } }
            menu -> scope.launch {
                runCatching { Repo.toggleFavorite(c) }.onSuccess { Toast.makeText(ctx, if (it) "Added ${c.name} to favourites" else "Removed ${c.name} from favourites", Toast.LENGTH_SHORT).show() }
            }
            p != null && p.stop <= n && c.archive -> Nav.playCatchup(c, p)
            p != null && p.stop <= n -> Toast.makeText(ctx, "This programme has finished (no catch-up on this channel)", Toast.LENGTH_SHORT).show()
            p != null && p.start > n -> scope.launch { runCatching { Repo.record(c, p) }.onSuccess { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() } }
            else -> Nav.playLive(c, channels.toList(), groups.firstOrNull { it.id == group }?.name?.removePrefix("★  ") ?: "Guide")
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 18.dp)) {
        // ---- header: selected programme ----
        Row(Modifier.fillMaxWidth().height(116.dp).padding(start = 24.dp, end = 24.dp)) {
            val pc = Preview.channel
            if (pc != null) {
                // Keep watching the last channel while you browse the guide.
                Box(Modifier.size(176.dp, 99.dp).clip(RoundedCornerShape(8.dp))) {
                    LivePreview(pc, Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(3.dp)).background(C.live).padding(horizontal = 4.dp, vertical = 1.dp)) { T("LIVE", 8.sp, Color.White, FontWeight.Bold) }
                }
            } else Box(Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(8.dp)).background(C.card), contentAlignment = Alignment.Center) {
                ch?.logo?.let { Img(Net.url(it), Modifier.fillMaxSize().padding(10.dp), ContentScale.Fit) } ?: ch?.let { T(it.name, 13.sp, maxLines = 2) }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                T(listOfNotNull(ch?.num?.toString(), ch?.name).joinToString("  "), 12.sp, C.text2, FontWeight.SemiBold)
                T(prog?.title ?: if (ch != null) "No programme information" else "", 20.sp, weight = FontWeight.Bold)
                prog?.let { p ->
                    val n = nowS()
                    val state = when { p.stop <= n -> if (ch?.archive == true) "  ·  Catch-up available" else "  ·  Ended"; p.start > n -> "  ·  Upcoming (MENU or OK to record)"; else -> "  ·  On now" }
                    T("${fmtTime(p.start)} – ${fmtTime(p.stop)}$state", 12.sp, if (p.isNow) C.live else C.text2)
                    p.descr?.let { T(it, 12.sp, C.text2, maxLines = 2, lineHeight = 16.sp) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                T(fmtTime(clock), 22.sp, weight = FontWeight.Bold)
                T(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(clock * 1000)), 12.sp, C.text3)
            }
        }
        // ---- groups ----
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(groups, key = { _, g -> g.id }) { _, g ->
                Chip(g.name, g.id == group, memKey = "g/${g.id}") { if (group != g.id) { group = g.id; Net.prefs.liveGroup = g.id }; scope.launch { delay(60); runCatching { gridFr.requestFocus() } } }
            }
        }
        // ---- grid ----
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(top = 4.dp)
                .onFocusChanged { gridFocused = it.isFocused }
                .onKeyEvent { ev ->
                    val code = ev.nativeKeyEvent.keyCode
                    if (channels.isEmpty()) return@onKeyEvent false
                    val isOk = code == AKey.KEYCODE_DPAD_CENTER || code == AKey.KEYCODE_ENTER || code == AKey.KEYCODE_MENU
                    if (ev.type == KeyEventType.KeyUp) {
                        if (isOk && okDown) { okDown = false; activate(code == AKey.KEYCODE_MENU); return@onKeyEvent true }
                        return@onKeyEvent false
                    }
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (code) {
                        AKey.KEYCODE_DPAD_DOWN -> { moveRow(1); true }
                        AKey.KEYCODE_DPAD_UP -> if (selRow == 0) false else { moveRow(-1); true }
                        AKey.KEYCODE_PAGE_DOWN, AKey.KEYCODE_CHANNEL_DOWN -> { moveRow(8); true }
                        AKey.KEYCODE_PAGE_UP, AKey.KEYCODE_CHANNEL_UP -> { moveRow(-8); true }
                        AKey.KEYCODE_DPAD_RIGHT, AKey.KEYCODE_MEDIA_FAST_FORWARD -> {
                            val t = prog?.stop ?: (selTime + HALF_HOUR)
                            if (t < loadTo) { selTime = t; ensureVisible(t) }
                            true
                        }
                        AKey.KEYCODE_DPAD_LEFT, AKey.KEYCODE_MEDIA_REWIND -> {
                            val p = prog
                            val t = if (p == null) selTime - HALF_HOUR else p.start - 1
                            val limit = if (ch?.archive == true) loadFrom else floor30(nowS()) - HALF_HOUR
                            if (t < limit) false // let focus move to the side menu
                            else { selTime = t; ensureVisible(t); true }
                        }
                        AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_MENU -> { okDown = true; true }
                        else -> false
                    }
                }
                .focusRequester(gridFr)
                .focusable()
                // Touch: swipe left/right to move through time.
                .pointerInput(Unit) {
                    var acc = 0f
                    detectHorizontalDragGestures(onDragEnd = { acc = 0f }) { _, dx ->
                        acc += dx
                        val step = 90.dp.toPx()
                        while (acc <= -step) { acc += step; winStart += HALF_HOUR; selTime = maxOf(selTime, winStart) }
                        while (acc >= step) { acc -= step; winStart = maxOf(floor30(loadFrom), winStart - HALF_HOUR); selTime = minOf(selTime, winStart + SPAN - 1) }
                    }
                },
        ) {
            val areaW: Dp = maxWidth - CH_W
            val dpPerSec = areaW.value / SPAN
            fun x(t: Long): Dp = ((t - winStart) * dpPerSec).dp
            Column(Modifier.fillMaxSize()) {
                // time ruler
                Box(Modifier.fillMaxWidth().height(22.dp)) {
                    Box(Modifier.padding(start = CH_W).fillMaxSize().clipToBounds()) {
                        var t = winStart
                        while (t < winStart + SPAN) {
                            T(fmtTime(t), 11.sp, C.text3, FontWeight.SemiBold, modifier = Modifier.offset(x = x(t) + 6.dp))
                            t += HALF_HOUR
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (channels.isEmpty()) {
                        when {
                            loadError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Message("Couldn't load the guide", loadError)
                                PillButton("Try again", primary = true) { retry++ }
                            }
                            total == 0 -> Message("No channels in this group")
                            else -> Loading()
                        }
                    } else LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
                        itemsIndexed(channels, key = { i, c -> "${c.id}:$i" }) { i, c ->
                            GuideRow(c, i == selRow && gridFocused, i == selRow, selTime, winStart, dpPerSec) { t ->
                                // Touch: first tap selects, tapping the selected programme again opens it.
                                val same = selRow == i && c.programmes.firstOrNull { selTime >= it.start && selTime < it.stop }?.let { t >= it.start && t < it.stop } == true
                                selRow = i; selTime = t
                                runCatching { gridFr.requestFocus() }
                                if (same) activate(false)
                            }
                        }
                    }
                    // "now" line
                    val n = clock
                    if (n in winStart until winStart + SPAN) Box(Modifier.padding(start = CH_W).offset(x = x(n)).width(2.dp).fillMaxHeight().background(C.live.copy(alpha = 0.8f)))
                }
            }
        }
    }
}

@Composable
private fun GuideRow(c: Channel, focused: Boolean, selectedRow: Boolean, selTime: Long, winStart: Long, dpPerSec: Float, onTap: (Long) -> Unit = {}) {
    val winEnd = winStart + SPAN
    val n = nowS()
    Row(Modifier.fillMaxWidth().height(ROW_H).padding(vertical = 1.5.dp)) {
        Row(
            Modifier.width(CH_W).fillMaxHeight().padding(end = 3.dp).clip(RoundedCornerShape(6.dp))
                .background(if (selectedRow) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(c.num?.toString() ?: "", 11.sp, C.text3, FontWeight.SemiBold, modifier = Modifier.width(30.dp))
            Box(Modifier.size(40.dp, 26.dp), contentAlignment = Alignment.Center) { c.logo?.let { Img(Net.url(it), Modifier.fillMaxSize(), ContentScale.Fit) } }
            Spacer(Modifier.width(8.dp))
            T(c.name, 12.sp, C.text, FontWeight.Medium)
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val visible = c.programmes.filter { it.stop > winStart && it.start < winEnd }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(end = 2.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = if (focused) 0.9f else 0.05f)).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    T("No information", 12.sp, if (focused) Color.Black else C.text3)
                }
            }
            visible.forEach { p ->
                val s = maxOf(p.start, winStart); val e = minOf(p.stop, winEnd)
                val isSel = focused && selTime >= p.start && selTime < p.stop
                val bg = when {
                    isSel -> Color.White
                    p.start <= n && p.stop > n -> C.cardHi
                    p.stop <= n -> Color.White.copy(alpha = 0.04f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
                Box(
                    Modifier.offset(x = ((s - winStart) * dpPerSec).dp)
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onTap(maxOf(p.start, nowS().coerceAtMost(p.stop - 1)).let { if (p.start > nowS() || p.stop <= nowS()) p.start else it }) }.width((((e - s) * dpPerSec).dp - 2.dp).coerceAtLeast(1.dp)).fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp)).background(bg)
                        .then(if (selectedRow && !isSel && p.start <= n && p.stop > n) Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(5.dp)) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Column {
                        T(p.title, 12.sp, if (isSel) Color.Black else if (p.stop <= n) C.text3 else C.text, FontWeight.SemiBold)
                        T("${fmtTime(p.start)} – ${fmtTime(p.stop)}", 10.sp, if (isSel) Color.DarkGray else C.text3)
                    }
                    if (p.stop <= n && c.archive) Box(Modifier.align(Alignment.TopEnd).size(5.dp).clip(RoundedCornerShape(50)).background(C.accent))
                }
            }
        }
    }
}
