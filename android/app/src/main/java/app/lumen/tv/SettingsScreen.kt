package app.lumen.tv

import android.content.Intent
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
    fun cycle(key: String, opts: List<Opt>, def: Any) {
        val cur = AppState.ui.opt(key)?.toString() ?: def.toString()
        val i = opts.indexOfFirst { it.value.toString() == cur }
        save(key, opts[(i + 1) % opts.size].value)
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 260.dp, top = 30.dp, bottom = 100.dp)) {
        item { T("Settings", 26.sp, weight = FontWeight.Bold); T("Changes sync to your Lumen server and every app, except the ones marked “this TV”.", 12.sp, C.text3, maxLines = 2) }

        item { Section("This TV") }
        item {
            SettingRow("Interface size (this TV)", "${Net.prefs.uiScale}%", fr = firstFr, hint = "Make everything bigger or smaller") {
                val i = SCALES.indexOf(Net.prefs.uiScale).let { if (it < 0) 2 else it }
                val next = SCALES[(i + 1) % SCALES.size]
                Net.prefs.uiScale = next
                MainActivity.scale.intValue = next
                tick++
            }
        }

        item { Section("Playback") }
        item { SettingRow("Quality at home", label("qualityLocal", QUALITIES, "auto")) { cycle("qualityLocal", QUALITIES, "auto") } }
        item { SettingRow("Quality away from home", label("qualityRemote", QUALITIES, "8000")) { cycle("qualityRemote", QUALITIES, "8000") } }
        item { SettingRow("Audio language", label("audioLang", LANGS, "")) { cycle("audioLang", LANGS, "") } }
        item { SettingRow("Subtitles", label("subtitleMode", SUB_MODES, "plex")) { cycle("subtitleMode", SUB_MODES, "plex") } }
        item { SettingRow("Subtitle language", label("subtitleLang", LANGS, "")) { cycle("subtitleLang", LANGS, "") } }
        item { Toggle("Autoplay next episode", AppState.uiBool("autoplayNext", true)) { save("autoplayNext", !AppState.uiBool("autoplayNext", true)) } }
        item { Toggle("Skip intros automatically", AppState.uiBool("autoSkipIntro", false)) { save("autoSkipIntro", !AppState.uiBool("autoSkipIntro", false)) } }
        item { Toggle("Skip credits automatically", AppState.uiBool("autoSkipCredits", false)) { save("autoSkipCredits", !AppState.uiBool("autoSkipCredits", false)) } }

        item { Section("Home screen") }
        item { SettingRow("Start on", label("startPage", START_PAGES, "home")) { cycle("startPage", START_PAGES, "home") } }
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
        item { SettingRow("App version", BuildConfig.VERSION_NAME, hint = "Updates: install http://<server>:8080/tv.apk with Downloader") {} }
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
