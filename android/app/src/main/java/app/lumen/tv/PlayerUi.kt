package app.lumen.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun PlayerOverlay(st: PlayerState, view: PlayerView) {
    var clock by remember { mutableLongStateOf(nowS()) }
    LaunchedEffect(Unit) { while (true) { clock = nowS(); delay(15_000) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

        if (st.buffering && st.error == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(44.dp))
        }
        // Paused with the controls hidden: a small reminder.
        if (!st.playing && !st.buffering && !st.controls && !st.live && st.error == null && st.panel == null) {
            Box(Modifier.align(Alignment.TopStart).padding(28.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Ico.Pause, null, tint = Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); T("Paused", 13.sp, weight = FontWeight.SemiBold) }
            }
        }

        AnimatedVisibility(st.controls && st.error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (st.live) LiveBanner(st, clock) else VodControls(st, clock)
        }

        // Skip intro / credits
        st.skip?.let { m ->
            if (st.error == null && st.upNext == null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = if (st.controls) 150.dp else 48.dp)
                    .clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp)) {
                    T(if (m.type == "intro") "Skip intro" else "Skip credits", 15.sp, Color.Black, FontWeight.Bold)
                }
            }
        }
        // Up next
        st.upNext?.let { left ->
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = if (st.controls) 150.dp else 48.dp).width(300.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.82f)).padding(16.dp)) {
                T("UP NEXT", 11.sp, C.accent, FontWeight.Bold)
                T(st.upNextLabel, 15.sp, weight = FontWeight.SemiBold, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                T("Playing in $left s", 13.sp, C.text2)
                Spacer(Modifier.height(10.dp))
                Row {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 14.dp, vertical = 6.dp)) { T("OK  Play now", 12.sp, Color.Black, FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f)).padding(horizontal = 14.dp, vertical = 6.dp)) { T("BACK  Cancel", 12.sp, weight = FontWeight.SemiBold) }
                }
            }
        }

        // Live channel list (OK / Left while watching)
        AnimatedVisibility(st.chList, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut(), modifier = Modifier.fillMaxHeight()) {
            ChannelListPanel(st)
        }
        // Audio / subtitles / quality
        AnimatedVisibility(st.panel != null, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
            st.panel?.let { SidePanel(it) }
        }

        // Channel number being typed
        if (st.digits.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopEnd).padding(28.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 20.dp, vertical = 8.dp)) {
                T(st.digits + "_", 34.sp, weight = FontWeight.Bold)
            }
        }
        st.message?.let { msg ->
            Box(Modifier.align(Alignment.TopCenter).padding(top = 28.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 18.dp, vertical = 8.dp)) {
                T(msg, 13.sp, weight = FontWeight.Medium)
            }
        }
        st.error?.let { e ->
            Column(Modifier.align(Alignment.Center).width(520.dp).clip(RoundedCornerShape(14.dp)).background(C.bg2.copy(alpha = 0.94f)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                T("Can't play this right now", 20.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                T(e, 13.sp, C.text2, maxLines = 4)
                Spacer(Modifier.height(18.dp))
                Row {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 18.dp, vertical = 8.dp)) { T("OK  Try again", 13.sp, Color.Black, FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f)).padding(horizontal = 18.dp, vertical = 8.dp)) { T("BACK  Close", 13.sp, weight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun VodControls(st: PlayerState, clock: Long) {
    Box(Modifier.fillMaxSize()) {
        // top
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))))
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 28.dp)) {
            Column(Modifier.weight(1f)) {
                T(st.title, 24.sp, weight = FontWeight.Bold)
                if (st.subtitle.isNotBlank()) T(st.subtitle, 14.sp, C.text2)
            }
            T(fmtTime(clock), 18.sp, weight = FontWeight.SemiBold)
        }
        // bottom
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 40.dp, end = 40.dp, bottom = 26.dp)) {
            val dur = st.dur
            val shown = st.scrub ?: st.pos
            SeekBar(if (dur > 0) (shown / dur).toFloat() else 0f, if (dur > 0) (st.pos / dur).toFloat() else 0f, st.zone == 0, st.scrub != null, fmtClock(shown), st.thumb)
            Spacer(Modifier.height(6.dp))
            Row {
                T(fmtClock(shown), 13.sp, C.text, FontWeight.SemiBold)
                Grow()
                if (dur > 0) {
                    val ends = nowS() + (dur - shown).toLong()
                    T("−${fmtClock(dur - shown)}   ·   Ends at ${fmtTime(ends)}", 13.sp, C.text2)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                st.buttons.forEachIndexed { i, b ->
                    val sel = st.zone == 1 && st.btn == i
                    val icon: ImageVector = when (b.id) {
                        "play" -> if (st.playing) Ico.Pause else Icons.Filled.PlayArrow
                        "back" -> Ico.Rewind
                        "fwd" -> Ico.Forward
                        "restart" -> Ico.Restart
                        "tracks" -> Ico.Subtitles
                        "quality", "settings" -> Icons.Filled.Settings
                        "next" -> Ico.SkipNext
                        "prev" -> Ico.SkipPrev
                        "episodes" -> Ico.Channels
                        "chapters" -> Ico.Guide
                        else -> Icons.Filled.List
                    }
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color.White.copy(alpha = 0.12f)).padding(horizontal = if (sel) 14.dp else 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = if (sel) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                        if (sel) { Spacer(Modifier.width(8.dp)); T(b.label, 13.sp, Color.Black, FontWeight.SemiBold) }
                    }
                }
                Grow()
                T(if (st.zone == 0) "◀ ▶ seek   ·   ▼ more options" else "▲ back to the timeline", 11.sp, C.text3)
            }
        }
    }
}

@Composable
private fun SeekBar(fraction: Float, played: Float, focused: Boolean, scrubbing: Boolean, label: String, thumb: androidx.compose.ui.graphics.ImageBitmap? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
        val w = maxWidth
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(if (focused) 6.dp else 4.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(played.coerceIn(0f, 1f)).background(C.accent))
        }
        val x = w * fraction.coerceIn(0f, 1f)
        Box(Modifier.align(Alignment.CenterStart).offset(x = x - 8.dp).size(if (focused) 16.dp else 10.dp).clip(CircleShape).background(Color.White))
        if (scrubbing) {
            if (thumb != null) {
                // Preview thumbnail above the playhead, like Plex.
                androidx.compose.foundation.Image(thumb, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.offset(x = (x - 96.dp).coerceIn(0.dp, w - 192.dp), y = (-140).dp).size(192.dp, 108.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black).border(2.dp, Color.White, RoundedCornerShape(8.dp)))
            }
            Box(Modifier.offset(x = (x - 30.dp).coerceIn(0.dp, w - 60.dp), y = (-30).dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 3.dp)) {
                T(label, 12.sp, Color.Black, FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LiveBanner(st: PlayerState, clock: Long) {
    val ch = st.channel
    val p = ch?.onAir
    val dateFmt = remember { java.text.SimpleDateFormat("EEE, MMM d, h:mm a", java.util.Locale.getDefault()) }
    Box(Modifier.fillMaxSize()) {
        // ---- top: provider • group   |   date and time ----
        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 34.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            T(listOf(st.providerName, st.group).filter { it.isNotBlank() }.joinToString("  •  "), 15.sp, C.text, FontWeight.Medium)
            Grow()
            T(dateFmt.format(java.util.Date(clock * 1000)), 15.sp, C.text, FontWeight.Medium)
        }
        // ---- bottom panel ----
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(300.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.75f), 1f to Color.Black.copy(alpha = 0.95f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 34.dp, end = 34.dp, bottom = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // big channel logo
                Box(Modifier.size(118.dp, 82.dp), contentAlignment = Alignment.Center) {
                    if (ch?.logo != null) Img(Net.url(ch.logo), Modifier.fillMaxSize(), ContentScale.Fit)
                    else T(ch?.name ?: st.title, 15.sp, weight = FontWeight.Bold, maxLines = 2)
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    T(p?.title ?: (ch?.name ?: st.title), 22.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p != null) {
                            T("${fmtTime(p.start)} – ${fmtTime(p.stop)}", 13.sp, C.text)
                            Spacer(Modifier.width(10.dp))
                            ProgressLine(p.progress, Modifier.width(56.dp), color = Color.White, track = Color.White.copy(alpha = 0.25f))
                            Spacer(Modifier.width(10.dp))
                            T("${((p.stop - nowS()).coerceAtLeast(0) / 60)} min", 13.sp, C.text)
                            Spacer(Modifier.width(18.dp))
                        }
                        T(listOfNotNull(ch?.num?.toString(), ch?.name).joinToString("  "), 13.sp, C.text, FontWeight.SemiBold)
                        st.badges.forEach { b ->
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 5.dp, vertical = 1.dp)) { T(b, 10.sp, Color.Black, FontWeight.Bold) }
                        }
                    }
                    ch?.next?.takeIf { it != p }?.let { n ->
                        Spacer(Modifier.height(4.dp))
                        Row { T("${fmtTime(n.start)} – ${fmtTime(n.stop)}", 13.sp, C.text2); Spacer(Modifier.width(14.dp)); T(n.title, 13.sp, C.text2) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // programme progress bar (full width)
            val frac = p?.progress ?: 0f
            BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp)) {
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(C.accent))
                }
                Box(Modifier.align(Alignment.CenterStart).offset(x = maxWidth * frac - 6.dp).size(12.dp).clip(CircleShape).background(Color.White))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth()) {
                if (p != null) {
                    val el = (nowS() - p.start).coerceAtLeast(0).toDouble()
                    T("${fmtClock(el)} / ${fmtClock((p.stop - p.start).toDouble())}", 15.sp, C.text, FontWeight.Medium, modifier = Modifier.align(Alignment.CenterStart))
                }
                // centre: ⏮ ⏪ ▶ ⏩ ⏭
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveBtn(Ico.SkipPrev, st.liveBtn == 0)
                    LiveBtn(Ico.Rewind, st.liveBtn == 1)
                    LiveBtn(if (st.playing) Ico.Pause else Icons.Filled.PlayArrow, st.liveBtn == 2, big = true)
                    LiveBtn(Ico.Forward, st.liveBtn == 3)
                    LiveBtn(Ico.SkipNext, st.liveBtn == 4)
                }
                // right: restart, LIVE, record
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveBtn(Ico.Restart, st.liveBtn == 5)
                    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(if (st.liveBtn == 6) Color.White else Color.Transparent)
                        .border(1.5.dp, if (st.liveBtn == 6) Color.White else C.text, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                        T("LIVE", 12.sp, if (st.liveBtn == 6) Color.Black else C.text, FontWeight.Bold)
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (st.liveBtn == 7) Color.White else Color.Transparent), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(if (st.liveBtn == 7) C.live else C.text))
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBtn(icon: ImageVector, selected: Boolean, big: Boolean = false) {
    val size = if (big) 46.dp else 36.dp
    Box(Modifier.size(size).clip(CircleShape).background(if (selected) Color.White else Color.Transparent), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(if (big) 26.dp else 22.dp))
    }
}

@Composable
private fun ChannelListPanel(st: PlayerState) {
    val list = Playback.zapList
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (st.chIndex - 4).coerceAtLeast(0))
    LaunchedEffect(st.chIndex) {
        val first = state.firstVisibleItemIndex
        val visible = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(6)
        if (st.chIndex < first + 1 || st.chIndex > first + visible - 2) state.scrollToItem((st.chIndex - visible / 2).coerceAtLeast(0))
    }
    Column(Modifier.width(380.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.8f)))).padding(top = 24.dp)) {
        Row(Modifier.padding(start = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            T(st.group.ifBlank { "Channels" }, 17.sp, weight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            T("${list.size}", 12.sp, C.text3)
        }
        LazyColumn(state = state, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 40.dp)) {
            itemsIndexed(list, key = { i, c -> "${c.id}:$i" }) { i, c ->
                val sel = i == st.chIndex
                val playing = c.id == st.channel?.id
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp)).background(if (sel) Color.White else Color.Transparent).padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(c.num?.toString() ?: "", 12.sp, if (sel) Color.DarkGray else C.text3, FontWeight.SemiBold, modifier = Modifier.width(34.dp))
                    Box(Modifier.size(44.dp, 28.dp), contentAlignment = Alignment.Center) { c.logo?.let { Img(Net.url(it), Modifier.fillMaxSize(), ContentScale.Fit) } }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        T(c.name, 13.sp, if (sel) Color.Black else C.text, FontWeight.SemiBold)
                        T(c.onAir?.title ?: "", 11.sp, if (sel) Color.DarkGray else C.text2)
                    }
                    if (playing) Box(Modifier.size(8.dp).clip(CircleShape).background(C.live))
                }
            }
        }
    }
}

@Composable
private fun SidePanel(p: Panel) {
    val state = rememberLazyListState()
    LaunchedEffect(p.index) { if (p.index > 6) state.scrollToItem(p.index - 4) else if (p.index < state.firstVisibleItemIndex) state.scrollToItem(p.index) }
    Column(Modifier.width(360.dp).fillMaxHeight().background(C.bg2.copy(alpha = 0.96f)).padding(top = 28.dp, start = 14.dp, end = 14.dp)) {
        T(p.title, 17.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(p.items) { i, it ->
                if (it.header) T(it.label.uppercase(), 11.sp, C.accent, FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp))
                else {
                    val sel = i == p.index
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp)).background(if (sel) Color.White else Color.Transparent).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(it.label, 14.sp, if (sel) Color.Black else C.text, if (it.selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (it.selected) T("✓", 14.sp, if (sel) Color.Black else C.accent, FontWeight.Bold)
                    }
                }
            }
        }
        T(if (p.parent != null) "▲▼ choose · OK select · BACK back" else "▲▼ choose · OK select · BACK close", 11.sp, C.text3, modifier = Modifier.padding(8.dp))
    }
}
