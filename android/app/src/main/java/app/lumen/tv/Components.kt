package app.lumen.tv

import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import android.view.KeyEvent as AKey
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Focus memory: when you come back to a screen, focus returns to the card you left from.
// ---------------------------------------------------------------------------------------------
class ScreenMemory {
    var focusKey: String? = null
    var restorePending = false
    val data = HashMap<String, Any?>()
}

val LocalMemory = staticCompositionLocalOf { ScreenMemory() }

/**
 * Puts focus on [fr] when a screen opens (unless focus is being restored to where you were).
 * Retries until the element exists, since content usually arrives a moment later.
 */
@Composable
fun InitialFocus(fr: FocusRequester) {
    val mem = LocalMemory.current
    LaunchedEffect(Unit) {
        if (mem.restorePending) {
            delay(1500)
            if (!mem.restorePending) return@LaunchedEffect
            mem.restorePending = false
        }
        repeat(60) {
            delay(80)
            if (mem.restorePending) return@LaunchedEffect
            if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/** Text with sensible TV defaults. */
@Composable
fun T(
    text: String,
    size: TextUnit = 14.sp,
    color: Color = C.text,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    align: androidx.compose.ui.text.style.TextAlign? = null,
    shadow: Boolean = false,
) {
    Text(text, modifier = modifier, fontSize = size, color = color, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = lineHeight, textAlign = align,
        style = if (shadow) androidx.compose.ui.text.TextStyle(shadow = TEXT_SHADOW) else androidx.compose.ui.text.TextStyle.Default)
}

/** Keeps light text readable over bright artwork. */
val TEXT_SHADOW = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(0f, 2f), 8f)

/**
 * The basic remote-friendly element: focusable, OK to activate, grows and gets a white outline when focused.
 * [memKey] lets the screen restore focus to this element when you come back to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    focusedScale: Float = 1.07f,
    outline: Boolean = true,
    memKey: String? = null,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    background: Color = Color.Transparent,
    focusedBackground: Color = background,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    // Springy zoom + soft glow on focus.
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "focusScale")
    val ring by animateFloatAsState(if (focused && outline) 1f else 0f, tween(180), label = "ring")
    val mem = LocalMemory.current
    val fr = focusRequester ?: remember { FocusRequester() }
    if (memKey != null) {
        LaunchedEffect(memKey) {
            if (mem.restorePending && mem.focusKey == memKey) {
                delay(60)
                if (runCatching { fr.requestFocus() }.isSuccess) mem.restorePending = false
            }
        }
    }
    Box(
        modifier
            .focusRequester(fr)
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused) {
                        if (memKey != null) mem.focusKey = memKey
                        onFocus?.invoke()
                    }
                }
            }
            .onPreviewKeyEvent { ev ->
                if (onMenu == null) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                val ok = code == AKey.KEYCODE_DPAD_CENTER || code == AKey.KEYCODE_ENTER || code == AKey.KEYCODE_NUMPAD_ENTER
                when {
                    // Holding OK: the remote repeats the key — open the options menu once, and swallow the rest.
                    ok && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount > 0 -> { if (!longFired) { longFired = true; onMenu() }; true }
                    ok && ev.type == KeyEventType.KeyDown -> { longFired = false; false }
                    ok && ev.type == KeyEventType.KeyUp && longFired -> { longFired = false; true }
                    (code == AKey.KEYCODE_MENU || code == AKey.KEYCODE_INFO) && ev.type == KeyEventType.KeyUp -> { onMenu(); true }
                    else -> false
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onMenu, // touch screens: long-press opens the menu
                onClick = { if (!longFired) onClick() },
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) focusedBackground else background)
            .then(if (outline && ring > 0.01f) Modifier.border(2.5.dp, Color.White.copy(alpha = ring), shape) else Modifier),
    ) { content(focused) }
}

/** Netflix-style pill button: white with black text when focused. */
@Composable
fun PillButton(
    label: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    memKey: String? = null,
    onFocus: (() -> Unit)? = null,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    Focusable(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), focusedScale = 1.05f, outline = false,
        focusRequester = focusRequester, memKey = memKey, onFocus = onFocus,
        background = if (primary) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f), focusedBackground = Color.White,
    ) { f ->
        Row(Modifier.padding(horizontal = if (small) 12.dp else 18.dp, vertical = if (small) 5.dp else 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = if (f) Color.Black else C.text, modifier = Modifier.size(if (small) 14.dp else 18.dp))
                Spacer(Modifier.width(if (small) 6.dp else 8.dp))
            }
            T(label, if (small) 12.sp else 14.sp, if (f) Color.Black else C.text, FontWeight.SemiBold)
        }
    }
}

@Composable
fun Img(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, alpha: Float = 1f) {
    if (url == null) { Box(modifier); return }
    AsyncImage(model = url, contentDescription = null, modifier = modifier, contentScale = scale, alpha = alpha)
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = C.accent, track: Color = Color.White.copy(alpha = 0.25f)) {
    Box(modifier.height(3.dp).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = C.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
}

@Composable
fun Message(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T(title, 20.sp, weight = FontWeight.SemiBold)
        if (detail != null) { Spacer(Modifier.height(6.dp)); T(detail, 14.sp, C.text2, maxLines = 4) }
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------
val POSTER_W = 118.dp
val WIDE_W = 214.dp

@Composable
fun PosterCard(item: PlexItem, memKey: String, onFocus: () -> Unit = {}, onMenu: (() -> Unit)? = null, width: Dp = POSTER_W, fr: FocusRequester? = null, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Focusable(onClick = onClick, memKey = memKey, focusRequester = fr, onFocus = onFocus, onMenu = onMenu, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f), background = C.card) {
            Img(Net.plexImg(item.poster, 240, 360), Modifier.fillMaxSize())
            if (item.poster == null) T(item.title, 13.sp, C.text2, maxLines = 3, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            if (item.watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(5.dp))
            if (item.progress > 0f) ProgressLine(item.progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
        if (AppState.uiBool("showCardTitles", true)) {
            Spacer(Modifier.height(6.dp))
            T(item.title, 11.sp, C.text2)
        }
    }
}

@Composable
fun WatchedBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.65f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

/** 16:9 card for Continue Watching, episodes and playlists. */
@Composable
fun WideCard(item: PlexItem, memKey: String, onFocus: () -> Unit = {}, onMenu: (() -> Unit)? = null, width: Dp = WIDE_W, title: String? = null, sub: String? = null, fr: FocusRequester? = null, onClick: () -> Unit) {
    val img = when (item.type) {
        "episode" -> item.thumb ?: item.grandparentArt
        "playlist" -> item.composite ?: item.thumb
        else -> item.art ?: item.thumb
    }
    Column(Modifier.width(width)) {
        Focusable(onClick = onClick, memKey = memKey, focusRequester = fr, onFocus = onFocus, onMenu = onMenu, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), background = C.card) {
            Img(Net.plexImg(img, 480, 270), Modifier.fillMaxSize())
            if (item.progress > 0f) ProgressLine(item.progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
            if (item.watched && item.type != "playlist") WatchedBadge(Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        Spacer(Modifier.height(6.dp))
        T(title ?: if (item.isEpisode) (item.grandparentTitle ?: item.title) else item.title, 12.sp, C.text, FontWeight.Medium)
        val s = sub ?: if (item.isEpisode) listOfNotNull(item.episodeLabel, item.title).joinToString(" · ") else item.year?.toString()
        if (!s.isNullOrBlank()) T(s, 11.sp, C.text2)
    }
}

/** Live channel card: logo, what's on now and how far through it is. */
@Composable
fun ChannelCard(ch: Channel, memKey: String, onFocus: () -> Unit = {}, onMenu: (() -> Unit)? = null, fr: FocusRequester? = null, onClick: () -> Unit) {
    Column(Modifier.width(196.dp).height(196.dp)) {
        Focusable(onClick = onClick, memKey = memKey, focusRequester = fr, onFocus = onFocus, onMenu = onMenu, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), background = C.card, focusedBackground = C.cardHi) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.cardHi, C.bg2))))
            if (ch.logo != null) Img(Net.url(ch.logo), Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 18.dp), ContentScale.Fit)
            else T(ch.name, 16.sp, weight = FontWeight.Bold, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(10.dp))
            ch.onAir?.let { ProgressLine(it.progress, Modifier.align(Alignment.BottomStart).fillMaxWidth(), color = C.live) }
            Row(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(3.dp)).background(C.live).padding(horizontal = 5.dp, vertical = 1.dp)) {
                T("LIVE", 8.sp, Color.White, FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        T(listOfNotNull(ch.num?.toString(), ch.name).joinToString("  "), 12.sp, C.text, FontWeight.Medium)
        T(ch.onAir?.title ?: "No programme info", 11.sp, C.text2, maxLines = 2, lineHeight = 14.sp)
        ch.onAir?.let { T("${fmtTime(it.start)} – ${fmtTime(it.stop)}", 10.sp, C.text3) }
    }
}

/** Row of cards with a title, used on Home, libraries and details. Reports focus so the screen can scroll it into place. */
@Composable
fun CardRow(
    title: String,
    modifier: Modifier = Modifier,
    startPad: Dp = 0.dp,
    onRowFocused: () -> Unit = {},
    state: androidx.compose.foundation.lazy.LazyListState? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    Column(modifier.onFocusChanged { if (it.hasFocus && !hasFocus) onRowFocused(); hasFocus = it.hasFocus }) {
        val titleColor by androidx.compose.animation.animateColorAsState(if (hasFocus) C.text else C.text2, tween(200), label = "rowTitle")
        if (title.isNotEmpty()) T(title, 15.sp, titleColor, FontWeight.SemiBold, modifier = Modifier.padding(start = startPad, bottom = 2.dp))
        // Horizontal scrolling keeps the focused card fully on screen with a margin (not TV's default pivot).
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides EdgeScroll) {
            LazyRow(
                state = state ?: rememberLazyListState(),
                contentPadding = PaddingValues(start = startPad, end = 48.dp, top = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

/** Shorthand for a row of Plex items. */
@Composable
fun PlexRow(rowKey: String, title: String, items: List<PlexItem>, wide: Boolean, startPad: Dp, onRowFocused: () -> Unit = {}, firstFocus: FocusRequester? = null, onItemFocus: (PlexItem) -> Unit = {}, onOpen: (PlexItem) -> Unit) {
    val unique = remember(items) { items.distinctBy { it.ratingKey } }
    CardRow(title, startPad = startPad, onRowFocused = onRowFocused) {
        itemsIndexed(unique, key = { _, it -> it.ratingKey }) { i, it ->
          Box {
            val fr = if (i == 0) firstFocus else null
            if (wide) WideCard(it, "$rowKey/${it.ratingKey}", onFocus = { onItemFocus(it) }, onMenu = { Menus.item(it, inContinue = rowKey == "continue") }, fr = fr) { onOpen(it) }
            else PosterCard(it, "$rowKey/${it.ratingKey}", onFocus = { onItemFocus(it) }, onMenu = { Menus.item(it) }, fr = fr) { onOpen(it) }
          }
        }
    }
}

/** Lists whose rows are snapped into place by the screen itself: no automatic scrolling. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val SmoothScroll: androidx.compose.animation.core.AnimationSpec<Float> = tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)
/** Short and linear: keeps up when a direction is held down (each repeat starts a new scroll). */
val QuickScroll: androidx.compose.animation.core.AnimationSpec<Float> = tween(110, easing = androidx.compose.animation.core.LinearEasing)

/** Scroll a list so [index] sits at the top, with a smooth eased animation. */
suspend fun androidx.compose.foundation.lazy.LazyListState.smoothScrollToItem(index: Int) {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (info != null) animateScrollBy((info.offset + layoutInfo.viewportStartOffset).toFloat(), SmoothScroll)
    else animateScrollToItem(index)
}

/** Vertical lists (Settings, channel lists): scroll smoothly, only when the focus gets near the top or bottom. */
val CalmScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = SmoothScroll
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = (containerSize * 0.18f).coerceAtMost(containerSize / 2 - size / 2).coerceAtLeast(0f)
        val trailing = offset + size
        return when {
            offset < margin -> offset - margin
            trailing > containerSize - margin -> trailing - (containerSize - margin)
            else -> 0f
        }
    }
}

/** Standard list scrolling: only as far as needed to show the focused row, with the platform's default animation. */
val PlainScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = QuickScroll
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f
            offset < 0 || size > containerSize -> offset
            else -> trailing - containerSize
        }
    }
}

val NoScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = SmoothScroll
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Keep the focused item fully visible with some breathing room (room for the focus zoom). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val EdgeScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Standard scrolling: move only as far as needed to show the focused card (plus room for its zoom).
        val margin = size * 0.06f
        val trailing = offset + size
        return when {
            offset < margin -> offset - margin
            trailing > containerSize - margin -> trailing - (containerSize - margin)
            else -> 0f
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, memKey: String? = null, focusRequester: FocusRequester? = null, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    Focusable(
        onClick = onClick, shape = RoundedCornerShape(50), focusedScale = 1.05f, outline = false, memKey = memKey, focusRequester = focusRequester, onFocus = onFocus,
        background = if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f), focusedBackground = Color.White,
    ) { f ->
        T(label, 13.sp, if (f) Color.Black else if (selected) C.text else C.text2, if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))
