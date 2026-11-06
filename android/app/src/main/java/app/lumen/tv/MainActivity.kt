package app.lumen.tv

import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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
        // Light status-bar icons over the app's dark screens, whatever the phone's light/dark setting.
        if (!AppState.isTv) enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        Nav.appContext = applicationContext
        // Plex sign-in is all the app needs; the Lumen server is optional.
        if (!Plex.signedIn || !Plex.hasServer) { startActivity(Intent(this, SetupActivity::class.java)); finish(); return }
        Net.onUnauthorized = {
            // The Lumen server signed this device out (removed in Settings → People / Devices): carry on with Plex alone.
            runOnUiThread {
                if (Net.prefs.isPaired) {
                    Net.prefs.clearPairing()
                    android.widget.Toast.makeText(this, "Signed out of the Lumen server. Plex still works; reconnect in Settings.", android.widget.Toast.LENGTH_LONG).show()
                    AppState.loaded = false
                }
            }
        }
        scale.intValue = Net.prefs.uiScale
        incoming(intent)?.let { Links.pending = it }
        // Opening the app (even if Android kept it in memory) asks "Who's watching?".
        if (AppState.loaded) AppState.profileCheckTick++
        setContent {
            LumenTheme(scale.intValue) { Shell(onExit = { moveTaskToBack(true) }) }
        }
    }

    /**
     * Holding a direction on the remote sends a key every ~50 ms, faster than a list can scroll and lay out the
     * next row, which made lists hang. Repeats are paced to one every 90 ms (single presses are never delayed).
     */
    private var lastRepeat = 0L
    private var backLong = false
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // The remote's search key: Search, listening straight away.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 && AppState.loaded) { Nav.root(Screen.Search); VoiceSearch.listenTick++ }
            return true
        }
        // Hold Back in Live TV / the Guide: return to full screen on the channel that's playing in the preview.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
                if (!backLong && (Nav.current == Screen.Live || Nav.current == Screen.Guide) && Preview.channel != null) { backLong = true; Nav.resumeLive() }
                if (backLong) return true
            }
            if (event.action == android.view.KeyEvent.ACTION_UP && backLong) { backLong = false; return true }
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount > 0 && event.keyCode in DPAD_KEYS) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastRepeat < 90) return true
            lastRepeat = now
        }
        return super.dispatchKeyEvent(event)
    }
    private val DPAD_KEYS = setOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)

    /** A lumen:// link (TV home screen rows) or a search (Google Assistant / the system). */
    private fun incoming(i: Intent?): android.net.Uri? = when (i?.action) {
        Intent.ACTION_VIEW -> i.data?.takeIf { it.scheme == "lumen" }
        Intent.ACTION_SEARCH, "com.google.android.gms.actions.SEARCH_ACTION" -> i.getStringExtra(android.app.SearchManager.QUERY)?.let { q ->
            android.net.Uri.Builder().scheme("lumen").authority("search").appendQueryParameter("q", q).build()
        }
        else -> null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming(intent)?.let { Links.pending = it; return }
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
            // Fast start: show what the app had last time straight away; fresh data follows in the background.
            if (first && AppState.loadSnapshot()) {
                AppState.startApplied = true
                AppState.checkingProfile = true
                AppScope.launch { runCatching { AppState.refresh() }; TvHome.update(Nav.appContext, force = true) }
                AppScope.launch { runCatching { AppState.checkProfile() }; AppState.checkingProfile = false }
                return@LaunchedEffect
            }
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
                    "guide" -> if (AppState.providers.isNotEmpty()) Nav.root(if (AppState.isTv) Screen.Guide else Screen.Live)
                }
            }
        }
    }
    // Look for a newer app version when the app opens (offered in a small dialog).
    LaunchedEffect(AppState.loaded) { if (AppState.loaded) { Updater.autoCheck(); TvHome.update(Nav.appContext) } }
    // Links from the TV home screen and searches from Google Assistant, once the app is ready.
    LaunchedEffect(AppState.loaded, AppState.checkingProfile, AppState.askProfile, Links.pending) {
        val p = Links.pending
        if (p != null && AppState.loaded && !AppState.checkingProfile && AppState.askProfile == null) { Links.pending = null; Links.handle(p) }
    }
    // "Now available": requested titles that arrived (Overseerr), checked every few minutes.
    LaunchedEffect(AppState.loaded) {
        if (!AppState.loaded) return@LaunchedEffect
        while (true) {
            if (AppState.discoverOn) runCatching {
                val since = Net.prefs.alertsSince
                val j = Net.obj("/api/discover/alerts?since=$since")
                Net.prefs.alertsSince = j.optLong("now", System.currentTimeMillis())
                if (since > 0) j.optJSONArray("alerts").objects().take(3).forEach { a ->
                    AppState.alert = a
                    kotlinx.coroutines.delay(9000)
                    AppState.alert = null
                    kotlinx.coroutines.delay(400)
                }
            }
            kotlinx.coroutines.delay(5 * 60_000)
        }
    }
    // Not connected to Lumen yet (e.g. it wasn't found during setup)? Look for it once in the background.
    LaunchedEffect(AppState.loaded) {
        if (AppState.loaded && !AppState.hasLumen && !LumenLink.tried) {
            LumenLink.tried = true
            if (LumenLink.autoConnect()) AppState.loaded = false
        }
    }
    LaunchedEffect(AppState.profileCheckTick) { if (AppState.profileCheckTick > 0) AppState.checkProfile() }
    if (!AppState.loaded || AppState.checkingProfile) { Box(Modifier.fillMaxSize().background(C.bg)) { Loading(Modifier.align(Alignment.Center)) }; return }
    AppState.askProfile?.let { pu -> WhoIsWatching(pu, onExit) { AppState.askProfile = null; Net.clearCache(); Nav.memory.clear(); AppState.resumeTick++ }; return }
    if (!AppState.plexOnline && AppState.providers.isEmpty()) {
        // Nothing to show: Plex can't be reached (and there's no Live TV from a Lumen server).
        Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Message("Can't reach your Plex server", AppState.plexError ?: Plex.serverName)
                Row {
                    PillButton("Retry", primary = true) { AppState.loaded = false }
                    Spacer(Modifier.width(12.dp))
                    PillButton("Sign in again") {
                        Plex.signOut(); Net.prefs.forgetServer()
                        Nav.appContext.startActivity(Intent(Nav.appContext, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
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

    val portraitNow = LocalPortrait.current
    BackHandler {
        when {
            Nav.pop() -> {}
            !railFocus.value && screen.topLevel && !portraitNow -> {
                val id = entries.firstOrNull { it.screen == screen }?.id ?: if (screen == Screen.Settings) "settings" else entries.firstOrNull()?.id
                runCatching { id?.let { railRequesters[it] }?.requestFocus() }
            }
            screen != Screen.Home -> Nav.root(Screen.Home)
            else -> onExit()
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        val portrait = LocalPortrait.current
        val showRail = screen.topLevel && !portrait
        val showBottomNav = screen.topLevel && portrait
        // Phones draw edge-to-edge: keep content clear of Android's navigation / gesture bar.
        val navPad = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues()
        val navInset = navPad.calculateBottomPadding()
        val navLeft = navPad.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
        val navRight = navPad.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
        // Phone screens without a full-bleed banner also keep clear of the status bar at the top.
        val statusTop = topSafe()
        val padTop = if (portrait && screen.topLevel && screen != Screen.Home && screen !is Screen.Lib && screen != Screen.Discover) statusTop + 8.dp else 0.dp
        Box(Modifier.fillMaxSize().padding(start = (if (showRail) 52.dp else 0.dp) + navLeft, end = navRight, top = padTop, bottom = if (showBottomNav) 64.dp + navInset else navInset)) {
            androidx.compose.animation.AnimatedContent(
                targetState = screen, contentKey = { it.key }, label = "screen",
                transitionSpec = {
                    // A plain cross-fade: scaling the whole screen as well was too heavy for TV hardware.
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 30)) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) using
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
                            Screen.MultiView -> MultiViewScreen()
                            Screen.Recordings -> RecordingsScreen()
                            Screen.Settings -> SettingsScreen()
                            is Screen.Lib -> LibraryScreen(s.lib)
                            is Screen.Detail -> DetailScreen(s)
                            is Screen.Grid -> GridScreen(s)
                            Screen.Discover -> DiscoverScreen()
                            is Screen.DiscoverItem -> DiscoverItemScreen(s)
                        }
                    }
                }
            }
        }
        if (showRail) Rail(entries, screen, railFocus, railRequesters) { e -> Nav.root(e) }
        if (showBottomNav) BottomNav(screen, Modifier.align(Alignment.BottomCenter))
        MenuHost()
        AlertBanner(Modifier.align(Alignment.TopCenter))
    }
}

/** "Now available": a requested title arrived in Plex (tap on a phone to open it). */
@Composable
private fun AlertBanner(modifier: Modifier) {
    var last by remember { mutableStateOf<org.json.JSONObject?>(null) }
    AppState.alert?.let { last = it }
    androidx.compose.animation.AnimatedVisibility(AppState.alert != null, modifier,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
        exit = androidx.compose.animation.fadeOut()) {
        val a = last ?: return@AnimatedVisibility
        Row(
            Modifier.padding(top = if (LocalPortrait.current) topSafe() + 8.dp else 20.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(C.bg2.copy(alpha = 0.97f))
                .then(Modifier.clickable(interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(), indication = null) {
                    val key = a.str("ratingKey") ?: return@clickable
                    AppState.alert = null
                    AppScope.launch { runCatching { Repo.meta(key) }.getOrNull()?.let { Nav.openDetails(it) } }
                })
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            a.str("poster")?.let { Img(it, Modifier.width(40.dp).height(60.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))); Spacer(Modifier.width(12.dp)) }
            Column {
                T("Now available", 12.sp, C.accent, FontWeight.SemiBold)
                T(a.optString("title") + (a.str("year")?.let { " ($it)" } ?: ""), 16.sp, weight = FontWeight.Bold)
                a.str("requestedBy")?.let { T("Requested by $it", 12.sp, C.text2) }
            }
        }
    }
}

private fun railEntries(): List<RailEntry> {
    val list = mutableListOf(RailEntry("search", "Search", Icons.Filled.Search, Screen.Search), RailEntry("home", "Home", Icons.Filled.Home, Screen.Home))
    if (AppState.providers.isNotEmpty()) {
        list += RailEntry("live", "Live TV", Ico.LiveTv, Screen.Live)
        if (AppState.isTv) list += RailEntry("guide", "Guide", Ico.Guide, Screen.Guide)
        if (AppState.isTv) list += RailEntry("multiview", "Multi-View", Ico.Grid, Screen.MultiView)
        list += RailEntry("dvr", "Recordings", Ico.Rec, Screen.Recordings)
    }
    if (AppState.discoverOn) list += RailEntry("discover", "Discover", Ico.Compass, Screen.Discover)
    for (l in AppState.libraries) list += RailEntry("lib:${l.key}", l.title, if (l.type == "movie") Ico.Movie else Ico.Tv, Screen.Lib(l))
    // Same order / visibility as the web app's sidebar (Settings → Appearance → Sidebar).
    val hidden = AppState.uiList("navHidden").toSet()
    return AppState.applyOrder(list, AppState.uiList("navOrder")) { it.id }.filter { it.id !in hidden || it.id == "home" }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Rail(entries: List<RailEntry>, screen: Screen, railFocus: androidx.compose.runtime.MutableState<Boolean>, requesters: Map<String, FocusRequester>, onSelect: (Screen) -> Unit) {
    val expanded = railFocus.value
    val width by animateDpAsState(if (expanded) 210.dp else 52.dp, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "rail")
    val currentId = entries.firstOrNull { it.screen == screen }?.id ?: if (screen == Screen.Settings) "settings" else null
    Box(
        Modifier.fillMaxHeight().width(if (expanded) 420.dp else 52.dp)
            .background(if (expanded) Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.75f), Color.Transparent)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
    ) {
        // Every item in menu order, so ▲ / ▼ always move through the menu.
        val order = entries.map { it.id } + "settings"
        val focusedIdx = remember { mutableIntStateOf(-1) }
        Column(
            Modifier.fillMaxHeight().width(width).padding(vertical = 18.dp, horizontal = 5.dp)
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
            Spacer(Modifier.height(30.dp))
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
