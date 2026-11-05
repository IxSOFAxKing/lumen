package app.lumen.tv

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class Opt(val value: Any, val label: String)

private val QUALITIES = listOf(
    Opt("auto", "Auto (direct play)"), Opt("original", "Original"), Opt("20000", "1080p · 20 Mbps"), Opt("12000", "1080p · 12 Mbps"),
    Opt("8000", "1080p · 8 Mbps"), Opt("4000", "720p · 4 Mbps"), Opt("2000", "720p · 2 Mbps"),
)
private val LANGS = listOf(
    Opt("", "Plex default"), Opt("en", "English"), Opt("es", "Spanish"), Opt("fr", "French"), Opt("de", "German"), Opt("it", "Italian"),
    Opt("pt", "Portuguese"), Opt("nl", "Dutch"), Opt("sv", "Swedish"), Opt("pl", "Polish"), Opt("ja", "Japanese"), Opt("ko", "Korean"), Opt("zh", "Chinese"),
)
private val SUB_MODES = listOf(Opt("plex", "Plex default"), Opt("foreign", "Only for foreign audio"), Opt("always", "Always on"), Opt("never", "Off"))
private val START_PAGES = listOf(Opt("home", "Home"), Opt("live", "Live TV"), Opt("guide", "Guide"))
private val SCALES = listOf(80, 90, 100, 110, 125)

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val firstFr = remember { FocusRequester() }
    InitialFocus(firstFr)

    fun save(key: String, value: Any) = scope.launch { AppState.saveUi(JSONObject().put(key, value)); tick++ }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var pinFor by remember { mutableStateOf<Pair<JSONObject, Boolean>?>(null) }
    var plexUsers by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(AppState.plexOnline, tick) { if (AppState.plexOnline) plexUsers = runCatching { Net.obj("/api/plex/users") }.getOrNull() }
    /** OK on a setting opens a menu with every choice. */
    fun cycle(key: String, opts: List<Opt>, def: Any, title: String = "") {
        val cur = AppState.ui.opt(key)?.toString() ?: def.toString()
        picker = Picker(title, opts, cur) { o -> save(key, o.value) }
    }
    fun switchUser(u: JSONObject, makeDefault: Boolean, pin: String? = null) {
        scope.launch {
            runCatching { Net.post("/api/plex/users/switch", JSONObject().put("uuid", u.optString("uuid")).put("makeDefault", makeDefault).apply { if (pin != null) put("pin", pin) }) }
                .onSuccess {
                    android.widget.Toast.makeText(ctx, if (makeDefault) "${u.optString("title")} is now the default for all apps" else "Watching as ${u.optString("title")}", android.widget.Toast.LENGTH_SHORT).show()
                    Net.clearCache(); Nav.memory.clear(); AppState.discoverOn = Discover.configured(); AppState.resumeTick++; tick++
                }
                .onFailure {
                    // Only if Plex wouldn't give Lumen this user's token.
                    if (it is Net.HttpError && it.code == 403 && pin == null) pinFor = u to makeDefault
                    else android.widget.Toast.makeText(ctx, it.message, android.widget.Toast.LENGTH_LONG).show()
                }
        }
    }
    @Composable
    fun tog(title: String, key: String, def: Boolean, hint: String? = null) {
        val on = AppState.uiBool(key, def)
        SettingRow(title, if (on) "On" else "Off", hint = hint) { save(key, !on) }
    }
        fun label(key: String, opts: List<Opt>, def: Any): String {
        val cur = AppState.ui.opt(key)?.toString() ?: def.toString()
        return opts.firstOrNull { it.value.toString() == cur }?.label ?: cur
    }
    fun toggleHidden(id: String) {
        val hidden = AppState.uiList("homeHidden").toMutableList()
        if (id in hidden) hidden.remove(id) else hidden.add(id)
        save("homeHidden", JSONArray(hidden))
    }
    val hidden = AppState.uiList("homeHidden").toSet()

    Box(Modifier.fillMaxSize()) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides CalmScroll) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = if (LocalPortrait.current) 16.dp else 40.dp, end = if (LocalPortrait.current) 16.dp else 260.dp, top = 30.dp, bottom = 100.dp)) {
        item { T("Settings", 26.sp, weight = FontWeight.Bold); T("Changes sync to your Lumen server and every app, except the ones marked “this TV”.", 12.sp, C.text3, maxLines = 2) }

        item { Section("This TV") }
        item {
            SettingRow("Interface size (this TV)", "${Net.prefs.uiScale}%", fr = firstFr, hint = "Make everything bigger or smaller") {
                picker = Picker("Interface size", SCALES.map { Opt(it, "$it%" + if (it == 100) "  (default)" else "") }, Net.prefs.uiScale.toString()) { o ->
                    val v = o.value as Int
                    Net.prefs.uiScale = v
                    MainActivity.scale.intValue = v
                    tick++
                }
            }
        }

        item {
            SettingRow("Theme (this TV)", Themes.current.name) {
                picker = Picker("Theme", Themes.all.map { Opt(it.id, it.name) }, Themes.current.id) { o ->
                    Net.prefs.tvTheme = o.value as String
                    Themes.apply(Net.prefs.tvTheme, Net.prefs.tvAccent)
                }
            }
        }
        item {
            SettingRow("Accent colour (this TV)", Themes.accents.firstOrNull { it.first == (Net.prefs.tvAccent ?: "") }?.second ?: "Custom") {
                picker = Picker("Accent colour", Themes.accents.map { Opt(it.first, it.second) }, Net.prefs.tvAccent ?: "") { o ->
                    Net.prefs.tvAccent = o.value as String
                    Themes.apply(Net.prefs.tvTheme, Net.prefs.tvAccent)
                }
            }
        }

        val pu = plexUsers
        val users = pu?.optJSONArray("users").objects()
        if (users.size > 1) {
            item { Section("Plex Home") }
            item {
                val cur = users.firstOrNull { it.optString("uuid") == pu?.optString("current") }
                SettingRow("Plex user (this TV)", cur?.optString("title") ?: "", hint = "Whose Continue Watching and watched status you see") {
                    picker = Picker("Watch as", users.map { Opt(it.optString("uuid"), it.optString("title")) }, pu?.optString("current") ?: "") { o ->
                        users.firstOrNull { it.optString("uuid") == o.value }?.let { switchUser(it, false) }
                    }
                }
            }
            item { Toggle("Ask \"Who's watching?\" when the app opens", Net.prefs.askProfile) { Net.prefs.askProfile = !Net.prefs.askProfile; tick++ } }
            item {
                val def = users.firstOrNull { it.optString("uuid") == pu?.optString("default") }
                SettingRow("Default Plex user (all apps)", if (pu?.optBoolean("defaultSet") == true) def?.optString("title") ?: "" else "Ask when the app opens", hint = "Used by every app and TV that hasn't picked a user") {
                    val opts = listOf(Opt("", "No default — ask who's watching")) + users.map { Opt(it.optString("uuid"), it.optString("title")) }
                    picker = Picker("Default user for all apps", opts, if (pu?.optBoolean("defaultSet") == true) pu.optString("default") else "") { o ->
                        if (o.value == "") scope.launch { runCatching { Net.post("/api/plex/users/default", JSONObject().put("uuid", JSONObject.NULL)) }; tick++ }
                        else users.firstOrNull { it.optString("uuid") == o.value }?.let { switchUser(it, true) }
                    }
                }
            }
        }

        item { Section("Playback") }
        item { SettingRow("Quality at home", label("qualityLocal", QUALITIES, "auto")) { cycle("qualityLocal", QUALITIES, "auto", "Quality at home") } }
        item { SettingRow("Quality away from home", label("qualityRemote", QUALITIES, "8000")) { cycle("qualityRemote", QUALITIES, "8000", "Quality away from home") } }
        item { SettingRow("Audio language", label("audioLang", LANGS, "")) { cycle("audioLang", LANGS, "", "Audio language") } }
        item { SettingRow("Subtitles", label("subtitleMode", SUB_MODES, "plex")) { cycle("subtitleMode", SUB_MODES, "plex", "Subtitles") } }
        item { SettingRow("Subtitle language", label("subtitleLang", LANGS, "")) { cycle("subtitleLang", LANGS, "", "Subtitle language") } }

        item { Section("Player") }
        item { tog("Hardware decoding", "hwDecoding", true, "Use the TV's video decoder when available") }
        item { tog("Auto picture-in-picture", "autoPip", true, "Keep playing in a small window when you leave the app (phones)") }
        item { tog("Match content frame rate", "matchFrameRate", false, "Switch the display refresh rate to the video's (e.g. 24 fps films)") }
        item { tog("Disable Dolby Vision", "disableDolbyVision", false, "Play the HDR10 / HDR layer instead of Dolby Vision") }

        item { Section("Audio") }
        item { SettingRow("Audio channels", label("audioOutput", AUDIO_OUT, "auto")) { cycle("audioOutput", AUDIO_OUT, "auto", "Audio channels") } }
        item { SettingRow("Maximum volume", label("maxVolume", MAX_VOL, 100)) { cycle("maxVolume", MAX_VOL, 100, "Maximum volume") } }

        item { Section("Subtitles") }
        item { SettingRow("Subtitle size", label("subtitleSize", SUB_SIZE, 100)) { cycle("subtitleSize", SUB_SIZE, 100, "Subtitle size") } }
        item { SettingRow("Subtitle colour", label("subtitleColor", SUB_COLOR, "#ffffff")) { cycle("subtitleColor", SUB_COLOR, "#ffffff", "Subtitle colour") } }
        item { SettingRow("Subtitle background", label("subtitleBg", SUB_BG, 60)) { cycle("subtitleBg", SUB_BG, 60, "Subtitle background") } }

        item { Section("Seek & timing") }
        item { SettingRow("Small skip (◀)", label("seekBack", SKIPS, 10)) { cycle("seekBack", SKIPS, 10, "Small skip duration") } }
        item { SettingRow("Large skip (▶)", label("seekForward", SKIPS, 30)) { cycle("seekForward", SKIPS, 30, "Large skip duration") } }
        item { SettingRow("Rewind on resume", label("rewindOnResume", REWIND, 0)) { cycle("rewindOnResume", REWIND, 0, "Rewind on resume") } }
        item { SettingRow("Default sleep timer", label("defaultSleep", SLEEP, 0)) { cycle("defaultSleep", SLEEP, 0, "Default sleep timer") } }
        item { tog("Chapter marks on the seek bar", "chapterMarks", true, "Show where chapters start") }

        item { Section("Auto-play & skip") }
        item { Toggle("Auto-play next episode", AppState.uiBool("autoplayNext", true)) { save("autoplayNext", !AppState.uiBool("autoplayNext", true)) } }
        item { SettingRow("Play next countdown", label("autoplayCountdown", COUNTDOWN, 10)) { cycle("autoplayCountdown", COUNTDOWN, 10, "Play next countdown") } }
        item { SettingRow("Skip intro", label("skipIntroMode", SKIP_MODES, if (AppState.uiBool("autoSkipIntro", false)) "auto" else "button")) { cycle("skipIntroMode", SKIP_MODES, if (AppState.uiBool("autoSkipIntro", false)) "auto" else "button", "Skip intro") } }
        item { SettingRow("Skip credits", label("skipCreditsMode", SKIP_MODES, if (AppState.uiBool("autoSkipCredits", false)) "auto" else "button")) { cycle("skipCreditsMode", SKIP_MODES, if (AppState.uiBool("autoSkipCredits", false)) "auto" else "button", "Skip credits") } }
        item { SettingRow("Auto skip delay", label("autoSkipDelay", SKIP_DELAY, 0)) { cycle("autoSkipDelay", SKIP_DELAY, 0, "Auto skip delay") } }
        item { tog("Use chapter names for intro/credits", "forceFallbackMarkers", false, "Find intros and credits from chapter titles even when Plex has markers") }

        if (!AppState.isTv) {
            item { Section("Gestures (phone)") }
            item { tog("Brightness swipe", "brightnessSwipe", true, "Swipe up or down on the left side of the video") }
            item { tog("Volume swipe", "volumeSwipe", true, "Swipe up or down on the right side of the video") }
            item { tog("Pinch to zoom", "pinchZoom", true, "Pinch the video to fill / fit the screen") }
        }

        item { Section("Home screen") }
        item { SettingRow("Start on", label("startPage", START_PAGES, "home")) { cycle("startPage", START_PAGES, "home", "Start on") } }
        item { Toggle("Featured banner", "hero" !in hidden) { toggleHidden("hero") } }
        homeSections(includeHidden = true).forEach { s ->
            item(key = "home-${s.id}") { Toggle(if (s.pin != null) "📌 ${s.label}" else s.label, s.id !in hidden) { toggleHidden(s.id) } }
        }
        item { Toggle("Titles under posters", AppState.uiBool("showCardTitles", true)) { save("showCardTitles", !AppState.uiBool("showCardTitles", true)) } }

        item { Section("Live TV") }
        item { Toggle("Channel numbers", AppState.uiBool("chShowNumbers", true)) { save("chShowNumbers", !AppState.uiBool("chShowNumbers", true)) } }
        item { Toggle("Channel logos", AppState.uiBool("chShowLogos", true)) { save("chShowLogos", !AppState.uiBool("chShowLogos", true)) } }
        item { Toggle("Programme progress bars", AppState.uiBool("chShowProgress", true)) { save("chShowProgress", !AppState.uiBool("chShowProgress", true)) } }

        item { Section("Server") }
        item { SettingRow("Connected to", "${Net.prefs.serverName ?: ""}  ${Net.server.removePrefix("http://")}") {} }
        item {
            SettingRow("Server settings", "Plex, IPTV providers, transcoding…", hint = "Opens the full Lumen settings") {
                ctx.startActivity(Intent(ctx, WebActivity::class.java).putExtra("path", "/#/settings"))
            }
        }
        item {
            SettingRow("Reload from server", "") { scope.launch { Net.clearCache(); AppState.refresh(); tick++ } }
        }
        item {
            SettingRow("Change server or pair again", "") { ctx.startActivity(Intent(ctx, SetupActivity::class.java)) }
        }
        item {
            SettingRow("Check for updates", "Version ${BuildConfig.VERSION_NAME}", hint = "Downloads and installs the newest Lumen app") {
                android.widget.Toast.makeText(ctx, "Checking for updates…", android.widget.Toast.LENGTH_SHORT).show()
                scope.launch {
                    val r = Updater.check()
                    if (r != null) Updater.offer(r) else android.widget.Toast.makeText(ctx, "You have the latest version (${BuildConfig.VERSION_NAME})", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    }
    picker?.let { p -> ChoiceDialog(p, onDismiss = { picker = null }) { o -> picker = null; p.onPick(o) } }
    pinFor?.let { (u, makeDefault) -> PinDialog(u.optString("title"), onDismiss = { pinFor = null }) { pin -> pinFor = null; switchUser(u, makeDefault, pin) } }
    }
}

private class Picker(val title: String, val options: List<Opt>, val current: String, val onPick: (Opt) -> Unit)

/** A menu of every choice for a setting; the current one is ticked and focused. */
@Composable
private fun ChoiceDialog(p: Picker, onDismiss: () -> Unit, onPick: (Opt) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val sel = p.options.indexOfFirst { it.value.toString() == p.current }.coerceAtLeast(0)
        val frs = remember(p) { p.options.map { FocusRequester() } }
        LaunchedEffect(p) { kotlinx.coroutines.delay(60); runCatching { frs[sel].requestFocus() } }
        Column(Modifier.width(400.dp).clip(RoundedCornerShape(14.dp)).background(C.bg2).padding(18.dp)) {
            T(p.title, 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp), state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (sel - 3).coerceAtLeast(0))) {
                items(p.options.size) { i ->
                    val o = p.options[i]
                    val on = i == sel
                    Focusable(
                        onClick = { onPick(o) }, focusRequester = frs[i], shape = RoundedCornerShape(8.dp), focusedScale = 1f, outline = false,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), background = Color.Transparent, focusedBackground = Color.White,
                    ) { f ->
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(o.label, 14.sp, if (f) Color.Black else C.text, if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            if (on) T("✓", 15.sp, if (f) Color.Black else C.accent, FontWeight.Bold)
                        }
                    }
                }
            }
            T("BACK to cancel", 11.sp, C.text3, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
        }
    }
}

/** PIN entry for a protected Plex Home user: number keys on the remote, or the on-screen keyboard. */
@Composable
fun PinDialog(user: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        var pin by remember { mutableStateOf("") }
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { fr.requestFocus() } }
        Column(Modifier.width(360.dp).clip(RoundedCornerShape(14.dp)).background(C.bg2).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            T("PIN for $user", 18.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter { it.isDigit() }.take(4); if (pin.length == 4) onDone(pin) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent, fontSize = 1.sp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (pin.isNotEmpty()) onDone(pin) }),
                modifier = Modifier.focusRequester(fr).size(1.dp),
            )
            val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            Row(Modifier.clickable { runCatching { fr.requestFocus() }; kb?.show() }) {
                repeat(4) { i ->
                    Box(Modifier.padding(6.dp).size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        T(if (i < pin.length) "•" else "", 26.sp, weight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            T("Type the 4-digit PIN with the remote's number keys or the on-screen keyboard (press OK).", 11.sp, C.text3, maxLines = 3)
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    T(title.uppercase(), 11.sp, C.accent, FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
}

@Composable
private fun SettingRow(label: String, value: String, fr: FocusRequester? = null, hint: String? = null, onClick: () -> Unit) {
    Focusable(
        onClick = onClick, focusRequester = fr, memKey = "set/$label", shape = RoundedCornerShape(8.dp), focusedScale = 1.01f, outline = false,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), background = Color.White.copy(alpha = 0.05f), focusedBackground = Color.White,
    ) { f ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(label, 14.sp, if (f) Color.Black else C.text, FontWeight.Medium)
                if (hint != null && f) T(hint, 11.sp, Color.DarkGray)
            }
            Spacer(Modifier.width(12.dp))
            T(value, 13.sp, if (f) Color.Black else C.text2, FontWeight.SemiBold)
            if (f && value.isNotEmpty()) T("  ›", 13.sp, Color.Black, FontWeight.Bold)
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) = SettingRow(label, if (on) "On" else "Off", onClick = onClick)

private val AUDIO_OUT = listOf(Opt("auto", "Auto (surround / passthrough)"), Opt("stereo", "Stereo (mix down)"))
private val MAX_VOL = listOf(Opt(100, "100%"), Opt(125, "125%"), Opt(150, "150%"), Opt(200, "200%"))
private val SUB_SIZE = listOf(Opt(75, "Small"), Opt(100, "Normal"), Opt(125, "Large"), Opt(150, "Extra large"))
private val SUB_COLOR = listOf(Opt("#ffffff", "White"), Opt("#ffff00", "Yellow"), Opt("#00ffff", "Cyan"), Opt("#00ff00", "Green"))
private val SUB_BG = listOf(Opt(0, "None"), Opt(30, "Light"), Opt(60, "Medium"), Opt(90, "Dark"))
private val SKIPS = listOf(Opt(5, "5 seconds"), Opt(10, "10 seconds"), Opt(15, "15 seconds"), Opt(30, "30 seconds"), Opt(60, "60 seconds"))
private val REWIND = listOf(Opt(0, "Off"), Opt(5, "5 seconds"), Opt(10, "10 seconds"), Opt(15, "15 seconds"), Opt(30, "30 seconds"))
private val SLEEP = listOf(Opt(0, "Off"), Opt(15, "15 minutes"), Opt(30, "30 minutes"), Opt(60, "60 minutes"), Opt(90, "90 minutes"), Opt(120, "2 hours"))
private val COUNTDOWN = listOf(Opt(3, "3 seconds"), Opt(5, "5 seconds"), Opt(10, "10 seconds"), Opt(15, "15 seconds"), Opt(30, "30 seconds"))
private val SKIP_MODES = listOf(Opt("off", "Off"), Opt("button", "Show button"), Opt("auto", "Skip automatically"))
private val SKIP_DELAY = listOf(Opt(0, "Immediately"), Opt(3, "After 3 seconds"), Opt(5, "After 5 seconds"), Opt(10, "After 10 seconds"))
