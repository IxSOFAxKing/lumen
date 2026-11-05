package app.lumen.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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

/** Touch controls (phones/tablets): the player activity implements these; the remote uses key handling instead. */
interface PlayerActions {
    fun tap()
    fun button(id: String)
    fun liveButton(i: Int)
    fun scrubTo(fraction: Float)
    fun commitScrub()
    fun panelPick(i: Int)
    fun channelPick(i: Int)
    fun skip()
    fun upNext()
    fun openChannels()
    fun openGuide()
    fun openOptions()
}
var playerActions: PlayerActions? = null

private fun Modifier.tap(onTap: () -> Unit) = this.clickable(interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(), indication = null, onClick = onTap)

@Composable
fun PlayerOverlay(st: PlayerState, view: PlayerView) {
    var clock by remember { mutableLongStateOf(nowS()) }
    LaunchedEffect(Unit) { while (true) { clock = nowS(); delay(15_000) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        // Tap anywhere: show / hide the controls.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { playerActions?.tap() }) })

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
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = if (st.controls) 190.dp else 48.dp)
                    .clip(RoundedCornerShape(50)).background(Color.White).tap { playerActions?.skip() }.padding(horizontal = 20.dp, vertical = 10.dp)) {
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
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).tap { playerActions?.upNext() }.padding(horizontal = 14.dp, vertical = 6.dp)) { T("OK  Play now", 12.sp, Color.Black, FontWeight.Bold) }
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
    val dateFmt = remember { java.text.SimpleDateFormat("EEE, MMM d, h:mm a", java.util.Locale.getDefault()) }
    Box(Modifier.fillMaxSize()) {
        // ---- top: show • season (or library)   |   date and time ----
        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 34.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            T(st.context, 15.sp, C.text, FontWeight.Medium)
            Grow()
            T(dateFmt.format(java.util.Date(clock * 1000)), 15.sp, C.text, FontWeight.Medium)
        }
        // ---- bottom panel ----
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(if (LocalPortrait.current) 420.dp else 300.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.75f), 1f to Color.Black.copy(alpha = 0.95f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 34.dp, end = 34.dp, bottom = 22.dp)) {
            val dur = st.dur
            val shown = st.scrub ?: st.pos
            Row(verticalAlignment = Alignment.Bottom) {
                st.poster?.let { Img(it, Modifier.size(62.dp, 93.dp).clip(RoundedCornerShape(6.dp))); Spacer(Modifier.width(18.dp)) }
                Column(Modifier.weight(1f)) {
                    T(st.title, 22.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    T(st.metaLine.ifBlank { st.subtitle }, 13.sp, C.text)
                    if (st.badges.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row { st.badges.forEach { b -> Box(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 5.dp, vertical = 1.dp)) { T(b, 10.sp, Color.Black, FontWeight.Bold) } } }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SeekBar(if (dur > 0) (shown / dur).toFloat() else 0f, if (dur > 0) (st.pos / dur).toFloat() else 0f, st.zone == 0, st.scrub != null, fmtClock(shown))
            Spacer(Modifier.height(8.dp))
            val centre = setOf("prev", "back", "play", "fwd", "next")
            SplitBar(
                left = {
                    Column {
                        T("${fmtClock(shown)} / ${fmtClock(dur)}", 15.sp, C.text, FontWeight.Medium)
                        if (dur > 0) T("Ends at ${fmtTime(nowS() + (dur - shown).toLong())}", 11.sp, C.text2)
                    }
                },
                centre = { st.buttons.forEachIndexed { i, b -> if (b.id in centre) VodBtn(st, b, i) } },
                right = { st.buttons.forEachIndexed { i, b -> if (b.id !in centre) VodBtn(st, b, i) } },
            )
            val sel = st.buttons.getOrNull(st.btn)
            T(if (st.zone == 1 && sel != null) sel.label else "◀ ▶ seek   ·   ▼ buttons", 11.sp, C.text3, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
        }
    }
}

/** Time on the left, transport buttons centred, extra buttons on the right — stacked in portrait. */
@Composable
private fun SplitBar(left: @Composable () -> Unit, centre: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit) {
    if (LocalPortrait.current) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            left()
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = centre)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = right)
        }
    } else Box(Modifier.fillMaxWidth()) {
        Box(Modifier.align(Alignment.CenterStart)) { left() }
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, content = centre)
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = right)
    }
}

@Composable
private fun VodBtn(st: PlayerState, b: Btn, i: Int) {
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
    val sel = st.zone == 1 && st.btn == i
    val big = b.id == "play"
    Box(Modifier.size(if (big) 46.dp else 36.dp).clip(CircleShape).background(if (sel) Color.White else Color.Transparent).tap { playerActions?.button(b.id) }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (sel) Color.Black else Color.White, modifier = Modifier.size(if (big) 26.dp else 21.dp))
    }
}

@Composable
private fun SeekBar(fraction: Float, played: Float, focused: Boolean, scrubbing: Boolean, label: String, thumb: androidx.compose.ui.graphics.ImageBitmap? = null) {
    var barW by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)
        .onSizeChanged { barW = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(Unit) {
            detectTapGestures { o -> playerActions?.scrubTo(o.x / barW); playerActions?.commitScrub() }
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(onDragEnd = { playerActions?.commitScrub() }) { change, _ -> playerActions?.scrubTo(change.position.x / barW) }
        }) {
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
            // Touch shortcuts (phones): channel list, programme guide, options.
            Spacer(Modifier.width(14.dp))
            Icon(Ico.Channels, "Channels", tint = Color.White, modifier = Modifier.size(30.dp).clip(CircleShape).tap { playerActions?.openChannels() }.padding(4.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Ico.Guide, "Guide", tint = Color.White, modifier = Modifier.size(30.dp).clip(CircleShape).tap { playerActions?.openGuide() }.padding(4.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Settings, "Options", tint = Color.White, modifier = Modifier.size(30.dp).clip(CircleShape).tap { playerActions?.openOptions() }.padding(4.dp))
        }
        // ---- bottom panel ----
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(if (LocalPortrait.current) 420.dp else 300.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.75f), 1f to Color.Black.copy(alpha = 0.95f))))
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
            BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(if (st.liveOverlay && st.liveZone == 1) 5.dp else 3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(C.accent))
                }
                val onBar = st.liveOverlay && st.liveZone == 1
                Box(Modifier.align(Alignment.CenterStart).offset(x = maxWidth * frac - if (onBar) 9.dp else 6.dp).size(if (onBar) 18.dp else 12.dp).clip(CircleShape)
                    .background(if (onBar) C.accent else Color.White).border(if (onBar) 2.dp else 0.dp, Color.White, CircleShape))
            }
            Spacer(Modifier.height(8.dp))
            SplitBar(
                left = {
                    if (p != null) {
                        val el = (nowS() - p.start).coerceAtLeast(0).toDouble()
                        T("${fmtClock(el)} / ${fmtClock((p.stop - p.start).toDouble())}", 15.sp, C.text, FontWeight.Medium)
                    }
                },
                // centre: ⏮ ⏪ ▶ ⏩ ⏭
                centre = {
                    LiveBtn(Ico.SkipPrev, (st.liveZone == 0 && st.liveBtn == 0), index = 0)
                    LiveBtn(Ico.Rewind, (st.liveZone == 0 && st.liveBtn == 1), index = 1)
                    LiveBtn(if (st.playing) Ico.Pause else Icons.Filled.PlayArrow, (st.liveZone == 0 && st.liveBtn == 2), big = true, index = 2)
                    LiveBtn(Ico.Forward, (st.liveZone == 0 && st.liveBtn == 3), index = 3)
                    LiveBtn(Ico.SkipNext, (st.liveZone == 0 && st.liveBtn == 4), index = 4)
                },
                // right: restart, LIVE, record
                right = {
                    LiveBtn(Ico.Restart, (st.liveZone == 0 && st.liveBtn == 5), index = 5)
                    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(if ((st.liveZone == 0 && st.liveBtn == 6)) Color.White else Color.Transparent).tap { playerActions?.liveButton(6) }
                        .border(1.5.dp, if ((st.liveZone == 0 && st.liveBtn == 6)) Color.White else C.text, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                        T("LIVE", 12.sp, if ((st.liveZone == 0 && st.liveBtn == 6)) Color.Black else C.text, FontWeight.Bold)
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if ((st.liveZone == 0 && st.liveBtn == 7)) Color.White else Color.Transparent).tap { playerActions?.liveButton(7) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(if ((st.liveZone == 0 && st.liveBtn == 7)) C.live else C.text))
                    }
                },
            )
        }
    }
}

@Composable
private fun LiveBtn(icon: ImageVector, selected: Boolean, big: Boolean = false, index: Int = -1) {
    val size = if (big) 46.dp else 36.dp
    Box(Modifier.size(size).clip(CircleShape).background(if (selected) Color.White else Color.Transparent).tap { if (index >= 0) playerActions?.liveButton(index) }, contentAlignment = Alignment.Center) {
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
                    Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp)).background(if (sel) Color.White else Color.Transparent).tap { playerActions?.channelPick(i) }.padding(horizontal = 10.dp, vertical = 7.dp),
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
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp)).background(if (sel) Color.White else Color.Transparent).tap { playerActions?.panelPick(i) }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(it.label, 14.sp, if (sel) Color.Black else C.text, if (it.selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (it.selected) T("✓", 14.sp, if (sel) Color.Black else C.accent, FontWeight.Bold)
                    }
                }
            }
        }
        T(if (p.parent != null) "▲▼ choose · OK select · BACK back" else "▲▼ choose · OK select · BACK close", 11.sp, C.text3, modifier = Modifier.padding(8.dp))
    }
}
