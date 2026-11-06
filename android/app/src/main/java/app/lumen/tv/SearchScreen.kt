package app.lumen.tv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class SearchResults(val channels: List<Channel>, val hubs: List<Hub>, val requestable: List<DItem> = emptyList())

@Composable
fun SearchScreen() {
    val mem = LocalMemory.current
    var q by remember { mutableStateOf(mem.data["q"] as String? ?: "") }
    var res by remember { mutableStateOf(mem.data["res"] as SearchResults?) }
    var busy by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFr = remember { FocusRequester() }
    InitialFocus(fieldFr)
    // A search from Google Assistant / the system.
    LaunchedEffect(VoiceSearch.pendingQuery) { VoiceSearch.pendingQuery?.let { q = it; VoiceSearch.pendingQuery = null } }
    // Voice search: the microphone button, or the remote's search key.
    val sctx = androidx.compose.ui.platform.LocalContext.current
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { q = it }
    }
    val voiceIntent = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search movies, shows and channels")
    }
    val canVoice = remember { voiceIntent.resolveActivity(sctx.packageManager) != null }
    fun listen() { runCatching { voice.launch(voiceIntent) }.onFailure { android.widget.Toast.makeText(sctx, "Voice search isn't available on this device", android.widget.Toast.LENGTH_SHORT).show() } }
    LaunchedEffect(VoiceSearch.listenTick) { if (VoiceSearch.listenTick > 0) { delay(250); listen() } }

    LaunchedEffect(q) {
        mem.data["q"] = q
        if (q.trim().length < 2) { res = null; return@LaunchedEffect }
        delay(450)
        busy = true
        val r = coroutineScope {
            val ch = async { if (AppState.providers.isNotEmpty()) runCatching { Repo.channelSearch(q.trim()) }.getOrDefault(emptyList()) else emptyList() }
            val os = async { if (AppState.discoverOn) runCatching { Discover.search(q.trim()) }.getOrDefault(emptyList()) else emptyList() }
            val px = async { if (AppState.plexOnline) runCatching { Repo.plexSearch(q.trim()) }.getOrDefault(emptyList()) else emptyList() }
            SearchResults(ch.await(), px.await(), os.await())
        }
        res = r; mem.data["res"] = r; busy = false
    }

    Column(Modifier.fillMaxSize().padding(top = 30.dp)) {
        Row(Modifier.padding(start = 32.dp, end = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (fieldFocused) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f))
                    .border(2.dp, if (fieldFocused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = C.text2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Box {
                        if (q.isEmpty()) T("Search movies, shows and channels", 16.sp, C.text3)
                        BasicTextField(
                            value = q, onValueChange = { q = it }, singleLine = true,
                            textStyle = TextStyle(color = C.text, fontSize = 17.sp),
                            cursorBrush = SolidColor(C.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { mem.data["q"] = q }),
                            modifier = Modifier.fillMaxWidth().focusRequester(fieldFr).onFocusChanged { fieldFocused = it.isFocused },
                        )
                    }
                }
            }
            if (canVoice) {
                Spacer(Modifier.width(12.dp))
                Focusable(onClick = { listen() }, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(48.dp), background = Color.White.copy(alpha = 0.08f), focusedBackground = Color.White) { f ->
                    Icon(Ico.Mic, "Voice search", tint = if (f) Color.Black else C.text, modifier = Modifier.align(Alignment.Center).size(24.dp))
                }
            }
            if (busy) { Spacer(Modifier.width(14.dp)); T("Searching…", 13.sp, C.text3) }
        }
        Spacer(Modifier.height(14.dp))
        val r = res
        when {
            q.trim().length < 2 -> Message("Find something to watch", "Press OK on the search box to type with your remote or voice keyboard.")
            r == null -> {}
            r.channels.isEmpty() && r.hubs.isEmpty() && r.requestable.isEmpty() -> Message("No results for \"${q.trim()}\"")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                if (r.channels.isNotEmpty()) item(key = "channels") {
                    CardRow("Live channels", startPad = 32.dp) {
                        itemsIndexed(r.channels, key = { i, c -> "${c.providerId}:${c.id}:$i" }) { _, ch -> ChannelCard(ch, "s/ch/${ch.providerId}:${ch.id}", onMenu = { Menus.channel(ch, r.channels, "Search") }) { Nav.playLive(ch, r.channels, "Search") } }
                    }
                }
                itemsIndexed(r.hubs, key = { i, h -> "${h.id}-$i" }) { _, hub ->
                    PlexRow("s/${hub.id}", hub.title, hub.items, hub.type == "episode", 32.dp) { Nav.openDetails(it) }
                }
                if (r.requestable.isNotEmpty()) item(key = "request") {
                    CardRow("Request with Overseerr", startPad = 32.dp) {
                        itemsIndexed(r.requestable, key = { _, d -> "${d.type}${d.id}" }) { _, d -> DiscoverCard(d, "s/os/${d.type}${d.id}") }
                    }
                }
            }
        }
    }
}

/** Recordings (DVR): play finished ones, see what's scheduled. */
@Composable
fun RecordingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<org.json.JSONObject>?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick, AppState.resumeTick) { list = runCatching { Net.arr("/api/iptv/recordings").objects() }.getOrDefault(emptyList()) }
    val firstFr = remember { FocusRequester() }
    InitialFocus(firstFr)
    Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 30.dp, end = 48.dp)) {
        T("Recordings", 24.sp, weight = FontWeight.Bold)
        T("OK plays a recording · MENU cancels or deletes it", 12.sp, C.text3)
        Spacer(Modifier.height(14.dp))
        val l = list
        when {
            l == null -> Loading()
            l.isEmpty() -> Message("No recordings yet", "In the Guide, press OK on an upcoming programme (or MENU on one that's on now) to record it.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                itemsIndexed(l, key = { _, r -> r.optInt("id") }) { i, r ->
                    val status = r.optString("status")
                    val id = r.optInt("id")
                    val playable = status == "completed" || status == "recording"
                    Focusable(
                        onClick = { if (playable) Nav.playRecording(id, r.optString("title"), r.optString("channel_name")) else Toast.makeText(ctx, "This recording is $status", Toast.LENGTH_SHORT).show() },
                        onMenu = {
                            scope.launch {
                                runCatching { if (status == "scheduled" || status == "recording") Net.post("/api/iptv/recordings/$id/cancel") else Net.delete("/api/iptv/recordings/$id") }
                                    .onSuccess { Toast.makeText(ctx, if (status == "scheduled" || status == "recording") "Recording cancelled" else "Recording deleted", Toast.LENGTH_SHORT).show(); tick++ }
                            }
                        },
                        focusRequester = if (i == 0) firstFr else null, memKey = "rec/$id", shape = RoundedCornerShape(8.dp), focusedScale = 1.01f, outline = false,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), background = Color.White.copy(alpha = 0.05f), focusedBackground = Color.White,
                    ) { f ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                T(r.optString("title"), 15.sp, if (f) Color.Black else C.text, FontWeight.SemiBold)
                                T("${r.optString("channel_name")}  ·  ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(r.optLong("start") * 1000))} – ${fmtTime(r.optLong("stop"))}", 12.sp, if (f) Color.DarkGray else C.text2)
                            }
                            val color = when (status) { "recording" -> C.live; "completed" -> Color(0xFF3CCF8E); "failed" -> Color(0xFFFF5A6A); else -> C.text3 }
                            T(status.replaceFirstChar { it.uppercase() }, 12.sp, color, FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
