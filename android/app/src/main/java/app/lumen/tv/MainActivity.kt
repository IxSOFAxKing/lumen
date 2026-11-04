package app.lumen.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The native Lumen interface for Android TV (Jetpack Compose). Data comes from the paired Lumen server;
 * advanced server configuration opens the web settings in [WebActivity].
 */
class MainActivity : ComponentActivity() {
    companion object {
        /** Bumped to re-apply the interface size after it's changed in Settings. */
        var scale = mutableIntStateOf(100)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.init(this)
        Nav.appContext = applicationContext
        if (!Net.prefs.isPaired) { startActivity(Intent(this, SetupActivity::class.java)); finish(); return }
        Net.onUnauthorized = {
            runOnUiThread {
                if (Net.prefs.isPaired) {
                    Net.prefs.clearPairing()
                    startActivity(Intent(this, SetupActivity::class.java).putExtra("server", Net.prefs.server))
                }
            }
        }
        scale.intValue = Net.prefs.uiScale
        // Opening the app (even if Android kept it in memory) asks "Who's watching?".
        if (AppState.loaded) AppState.profileCheckTick++
        setContent {
            LumenTheme(scale.intValue) { Shell(onExit = { moveTaskToBack(true) }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Back from pairing with a (new) server: reload everything.
        Net.clearCache()
        Nav.root(Screen.Home)
        AppState.loaded = false
    }

    override fun onResume() {
        super.onResume()
        Preview.pausedForPlayer = false
        Net.clearCache() // watch progress may have changed in the player
        // Rows reload with fresh progress; put focus back on the card you were on.
        if (AppState.loaded) Nav.mem(Nav.current).restorePending = true
        AppState.resumeTick++
    }
}

private data class RailEntry(val id: String, val label: String, val icon: ImageVector, val screen: Screen)

@Composable
fun Shell(onExit: () -> Unit) {
    LaunchedEffect(AppState.loaded) {
        if (!AppState.loaded) {
            val first = !AppState.startApplied
            if (first) AppState.checkingProfile = true
            AppState.refresh()
            // "Who's watching?" every time the app opens.
            if (first) {
                AppState.checkProfile()
                AppState.checkingProfile = false
            }
            // Start page from Settings → Appearance (shared with the web app) — only when the app starts.
            if (!AppState.startApplied) {
                AppState.startApplied = true
                when (AppState.uiStr("startPage", "home")) {
                    "live" -> if (AppState.providers.isNotEmpty()) Nav.root(Screen.Live)
                    "guide" -> if (AppState.providers.isNotEmpty()) Nav.root(Screen.Guide)
                }
            }
        }
    }
    LaunchedEffect(AppState.profileCheckTick) { if (AppState.profileCheckTick > 0) AppState.checkProfile() }
    if (!AppState.loaded || AppState.checkingProfile) { Box(Modifier.fillMaxSize().background(C.bg)) { Loading(Modifier.align(Alignment.Center)) }; return }
    AppState.askProfile?.let { pu -> WhoIsWatching(pu, onExit) { AppState.askProfile = null; Net.clearCache(); Nav.memory.clear(); AppState.resumeTick++ }; return }
    if (AppState.loadError != null && AppState.providers.isEmpty() && !AppState.plexConfigured) {
        Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Message("Can't reach your Lumen server", "${Net.prefs.serverName ?: ""} ${Net.server}")
                Row {
                    PillButton("Retry", primary = true) { AppState.loaded = false }
                    Spacer(Modifier.width(12.dp))
                    PillButton("Change server") { Nav.appContext.startActivity(Intent(Nav.appContext, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        return
    }

    val entries = remember(AppState.libraries, AppState.providers, AppState.ui) { railEntries() }
    val screen = Nav.current
    val railFocus = remember { mutableStateOf(false) }
    val railRequesters = remember(entries) { entries.associate { it.id to FocusRequester() } + ("settings" to FocusRequester()) }
    val holder = rememberSaveableStateHolder()

    BackHandler {
        when {
            Nav.pop() -> {}
            !railFocus.value && screen.topLevel -> {
                val id = entries.firstOrNull { it.screen == screen }?.id ?: if (screen == Screen.Settings) "settings" else entries.firstOrNull()?.id
                runCatching { id?.let { railRequesters[it] }?.requestFocus() }
            }
            screen != Screen.Home -> Nav.root(Screen.Home)
            else -> onExit()
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        val showRail = screen.topLevel
        Box(Modifier.fillMaxSize().padding(start = if (showRail) 64.dp else 0.dp)) {
            // Screens cross-fade with a slight zoom.
            androidx.compose.animation.AnimatedContent(
                targetState = screen, contentKey = { it.key }, label = "screen",
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 40)) +
                        androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(300), initialScale = 0.97f)) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) using
                        androidx.compose.animation.SizeTransform(clip = false) // artwork may extend under the side menu
                },
            ) { s ->
                CompositionLocalProvider(LocalMemory provides Nav.mem(s)) {
                    holder.SaveableStateProvider(s.key) {
                        when (s) {
                            Screen.Home -> HomeScreen()
                            Screen.Search -> SearchScreen()
                            Screen.Live -> LiveScreen()
                            Screen.Guide -> GuideScreen()
                            Screen.Recordings -> RecordingsScreen()
                            Screen.Settings -> SettingsScreen()
                            is Screen.Lib -> LibraryScreen(s.lib)
                            is Screen.Detail -> DetailScreen(s)
                            is Screen.Grid -> GridScreen(s)
                        }
                    }
                }
            }
        }
        if (showRail) Rail(entries, screen, railFocus, railRequesters) { e -> Nav.root(e) }
        MenuHost()
    }
}

private fun railEntries(): List<RailEntry> {
    val list = mutableListOf(RailEntry("search", "Search", Icons.Filled.Search, Screen.Search), RailEntry("home", "Home", Icons.Filled.Home, Screen.Home))
    if (AppState.providers.isNotEmpty()) {
        list += RailEntry("live", "Live TV", Ico.LiveTv, Screen.Live)
        list += RailEntry("guide", "Guide", Ico.Guide, Screen.Guide)
        list += RailEntry("dvr", "Recordings", Ico.Rec, Screen.Recordings)
    }
    for (l in AppState.libraries) list += RailEntry("lib:${l.key}", l.title, if (l.type == "movie") Ico.Movie else Ico.Tv, Screen.Lib(l))
    // Same order / visibility as the web app's sidebar (Settings → Appearance → Sidebar).
    val hidden = AppState.uiList("navHidden").toSet()
    return AppState.applyOrder(list, AppState.uiList("navOrder")) { it.id }.filter { it.id !in hidden || it.id == "home" }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Rail(entries: List<RailEntry>, screen: Screen, railFocus: androidx.compose.runtime.MutableState<Boolean>, requesters: Map<String, FocusRequester>, onSelect: (Screen) -> Unit) {
    val expanded = railFocus.value
    val width by animateDpAsState(if (expanded) 210.dp else 64.dp, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "rail")
    val currentId = entries.firstOrNull { it.screen == screen }?.id ?: if (screen == Screen.Settings) "settings" else null
    Box(
        Modifier.fillMaxHeight().width(if (expanded) 420.dp else 64.dp)
            .background(if (expanded) Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.75f), Color.Transparent)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
    ) {
        // Every item in menu order, so ▲ / ▼ always move through the menu.
        val order = entries.map { it.id } + "settings"
        val focusedIdx = remember { mutableIntStateOf(-1) }
        Column(
            Modifier.fillMaxHeight().width(width).padding(vertical = 18.dp, horizontal = 10.dp)
                // Entering the menu (◀ from the content) lands on the current section, like Netflix.
                .focusProperties { enter = { currentId?.let { requesters[it] } ?: FocusRequester.Default } }
                .focusGroup()
                .onFocusChanged { f -> railFocus.value = f.hasFocus; if (!f.hasFocus) focusedIdx.intValue = -1 }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = ev.nativeKeyEvent.keyCode
                    if (code != android.view.KeyEvent.KEYCODE_DPAD_DOWN && code != android.view.KeyEvent.KEYCODE_DPAD_UP) return@onPreviewKeyEvent false
                    val cur = focusedIdx.intValue.takeIf { it >= 0 } ?: order.indexOf(currentId).coerceAtLeast(0)
                    val next = (cur + if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(0, order.size - 1)
                    runCatching { requesters[order[next]]?.requestFocus() }
                    true
                },
        ) {
            Row(Modifier.padding(start = 8.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(C.accent), contentAlignment = Alignment.Center) { T("▶", 12.sp, Color.Black, FontWeight.Bold) }
                if (expanded) { Spacer(Modifier.width(10.dp)); T("Lumen", 18.sp, weight = FontWeight.Bold) }
            }
            entries.forEachIndexed { i, e -> RailItem(e.label, e.icon, e.id == currentId, expanded, requesters[e.id], { focusedIdx.intValue = i }) { onSelect(e.screen) } }
            Spacer(Modifier.weight(1f))
            RailItem("Settings", Icons.Filled.Settings, currentId == "settings", expanded, requesters["settings"], { focusedIdx.intValue = order.size - 1 }) { onSelect(Screen.Settings) }
        }
    }
}

@Composable
private fun RailItem(label: String, icon: ImageVector, selected: Boolean, expanded: Boolean, fr: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit) {
    Focusable(
        onClick = onClick, focusRequester = fr, onFocus = onFocus, shape = RoundedCornerShape(10.dp), focusedScale = 1f, outline = false,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), background = Color.Transparent, focusedBackground = Color.White,
    ) { f ->
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (f) Color.Black else if (selected) C.text else C.text3, modifier = Modifier.size(22.dp))
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                T(label, 15.sp, if (f) Color.Black else if (selected) C.text else C.text2, if (selected) FontWeight.Bold else FontWeight.Medium)
            }
        }
        if (selected && !expanded) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).width(14.dp).height(2.dp).background(C.accent))
    }
}
