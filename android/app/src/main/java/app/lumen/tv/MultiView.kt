package app.lumen.tv

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Up to four live channels at once (TV). Sound follows the selected tile. */
object MultiView {
    val slots = mutableStateListOf<Channel?>(null, null, null, null)

    /** Put a channel in the first empty tile (or the last one when all are full). */
    fun add(ch: Channel) {
        if (slots.any { it?.id == ch.id && it.providerId == ch.providerId }) return
        val i = slots.indexOfFirst { it == null }.takeIf { it >= 0 } ?: (slots.size - 1)
        slots[i] = ch
    }
}

@Composable
fun MultiViewScreen() {
    var audio by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val firstFr = remember { FocusRequester() }
    InitialFocus(firstFr)
    val filled = MultiView.slots.count { it != null }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            T("Multi-View", 22.sp, weight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            T("OK: full screen or add a channel  ·  MENU / hold OK: change or remove  ·  Sound follows the selected tile", 12.sp, C.text3)
        }
        if (filled > 1) T("Each tile uses one connection to your IPTV provider.", 11.sp, C.text3, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (r in 0 until 2) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in 0 until 2) {
                    val i = r * 2 + c
                    val ch = MultiView.slots[i]
                    Focusable(
                        onClick = { if (ch != null) Nav.playLive(ch, MultiView.slots.filterNotNull(), "Multi-View") else picking = i },
                        onMenu = { picking = i }, onFocus = { audio = i },
                        focusRequester = if (i == 0) firstFr else null,
                        shape = RoundedCornerShape(10.dp), focusedScale = 1f,
                        modifier = Modifier.weight(1f).fillMaxHeight(), background = Color.Black,
                    ) { f ->
                        if (ch != null) {
                            MultiTile(ch, muted = audio != i, Modifier.fillMaxSize())
                            Row(Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (audio == i) { T("🔊", 11.sp); Spacer(Modifier.width(6.dp)) }
                                T(ch.name, 12.sp, weight = FontWeight.SemiBold)
                            }
                        } else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            T("+", 34.sp, if (f) Color.White else C.text3, FontWeight.Light)
                            T("Add a channel", 13.sp, if (f) Color.White else C.text3)
                        }
                    }
                }
            }
        }
    }
    picking?.let { slot -> ChannelPicker(slot, onDone = { picking = null }) }
}

/** Choose a channel for a tile: favourites first, then recently watched. */
@Composable
private fun ChannelPicker(slot: Int, onDone: () -> Unit) {
    var list by remember { mutableStateOf<List<Channel>?>(null) }
    LaunchedEffect(Unit) {
        val favs = runCatching { Repo.favorites() }.getOrDefault(emptyList())
        val hist = runCatching { Repo.history() }.getOrDefault(emptyList())
        list = (favs + hist).distinctBy { "${it.providerId}:${it.id}" }
    }
    BackHandler { onDone() }
    val fr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp).fillMaxHeight(0.86f).background(C.bg2, RoundedCornerShape(14.dp)).padding(16.dp)) {
            T("Channel for tile ${slot + 1}", 18.sp, weight = FontWeight.Bold)
            T("Your favourites and recently watched channels", 12.sp, C.text3)
            Spacer(Modifier.height(10.dp))
            val l = list
            if (l == null) Loading() else LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                if (MultiView.slots[slot] != null) item {
                    Focusable(onClick = { MultiView.slots[slot] = null; onDone() }, focusRequester = fr, shape = RoundedCornerShape(8.dp), focusedScale = 1f, outline = false,
                        modifier = Modifier.fillMaxWidth(), background = Color.Transparent, focusedBackground = Color.White) { f ->
                        T("Remove from this tile", 14.sp, if (f) Color.Black else Color(0xFFFF7A85), FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
                    }
                }
                itemsIndexed(l, key = { _, c -> "${c.providerId}:${c.id}" }) { i, ch ->
                    Focusable(onClick = { MultiView.slots[slot] = ch; onDone() }, focusRequester = if (i == 0 && MultiView.slots[slot] == null) fr else null,
                        shape = RoundedCornerShape(8.dp), focusedScale = 1f, outline = false, modifier = Modifier.fillMaxWidth(),
                        background = Color.Transparent, focusedBackground = Color.White) { f ->
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp, 28.dp), contentAlignment = Alignment.Center) { ch.logo?.let { Img(Net.url(it), Modifier.fillMaxSize(), ContentScale.Fit) } }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                T(ch.name, 14.sp, if (f) Color.Black else C.text, FontWeight.SemiBold)
                                ch.onAir?.let { T(it.title, 12.sp, if (f) Color.DarkGray else C.text3) }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(l) { if (l != null) { delay(80); runCatching { fr.requestFocus() } } }
        }
    }
}

/** One live tile. Stops while full screen is open or the app is in the background. */
@OptIn(UnstableApi::class)
@Composable
private fun MultiTile(ch: Channel, muted: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) started = false
            if (e == Lifecycle.Event.ON_START) started = true
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    if (!started || Preview.pausedForPlayer) { Box(modifier.background(Color.Black)); return }
    val exo = remember(ch.providerId, ch.id) { buildExo(ctx) }
    var desc by remember(exo) { mutableStateOf<Desc?>(null) }
    var failed by remember(exo) { mutableStateOf(false) }
    DisposableEffect(exo) {
        onDispose {
            exo.release()
            desc?.let { d -> AppScope.launch { Playback.release(d) } }
        }
    }
    LaunchedEffect(exo) {
        val d = runCatching { LiveSource(ch).open(0.0) }.getOrNull() ?: run { failed = true; return@LaunchedEffect }
        desc = d
        exo.setMediaItem(mediaItemFor(d))
        exo.prepare()
        exo.playWhenReady = true
    }
    LaunchedEffect(exo) { while (true) { delay(20_000); desc?.let { Playback.ping(it) } } }
    LaunchedEffect(exo, muted) { exo.volume = if (muted) 0f else 1f }
    Box(modifier) {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false; isFocusable = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
            update = { it.player = exo },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) T("Couldn't open ${ch.name}", 13.sp, C.text2, modifier = Modifier.align(Alignment.Center))
    }
}

