package app.lumen.tv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** A title from Overseerr (Discover). */
data class DItem(val id: Int, val type: String, val title: String, val year: Int?, val poster: String?, val backdrop: String?, val overview: String, val rating: Double?, val status: String, val ratingKey: String?) {
    val statusLabel get() = when (status) { "available" -> "Available"; "partial" -> "Partly available"; "pending", "processing" -> "Requested"; else -> null }
    companion object {
        fun from(j: JSONObject) = DItem(j.optInt("id"), j.optString("type"), j.optString("title"), j.num("year")?.toInt(), j.str("poster"), j.str("backdrop"),
            j.optString("overview"), j.num("rating"), j.optString("status", "none"), j.str("ratingKey"))
        fun list(a: JSONArray?) = a.objects().map { from(it) }
    }
}

object Discover {
    suspend fun configured(): Boolean = runCatching { Net.obj("/api/discover/config", 60_000).optBoolean("configured") }.getOrDefault(false)
    suspend fun list(name: String) = DItem.list(Net.obj("/api/discover/list/$name", 600_000).optJSONArray("results"))
    suspend fun search(q: String) = DItem.list(Net.obj("/api/discover/search?q=${android.net.Uri.encode(q)}").optJSONArray("results"))
}

@Composable
fun DiscoverCard(d: DItem, memKey: String, fr: FocusRequester? = null, onFocus: () -> Unit = {}) {
    Column(Modifier.width(POSTER_W)) {
        Focusable(onClick = { Nav.push(Screen.DiscoverItem(d.type, d.id, d.title)) }, memKey = memKey, focusRequester = fr, onFocus = onFocus,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f), background = C.card) {
            Img(d.poster, Modifier.fillMaxSize())
            if (d.poster == null) T(d.title, 13.sp, C.text2, maxLines = 3, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            if (d.status == "available") WatchedBadge(Modifier.align(Alignment.TopEnd).padding(5.dp))
            else d.statusLabel?.let { Box(Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(C.accent).padding(horizontal = 5.dp, vertical = 1.dp)) { T(it, 9.sp, Color.Black, FontWeight.Bold) } }
        }
        Spacer(Modifier.height(6.dp))
        T(d.title, 11.sp, C.text2)
    }
}

private val ROWS = listOf("trending" to "Trending", "movies" to "Popular movies", "tv" to "Popular TV shows", "movies-upcoming" to "Upcoming movies", "tv-upcoming" to "Upcoming TV shows")

@Composable
fun DiscoverScreen() {
    val mem = LocalMemory.current
    @Suppress("UNCHECKED_CAST")
    var rows by remember { mutableStateOf(mem.data["rows"] as List<Pair<String, List<DItem>>>?) }
    var state by remember { mutableStateOf(if (rows != null) "ok" else "loading") }
    var focused by remember { mutableStateOf<DItem?>(null) }
    LaunchedEffect(AppState.resumeTick) {
        if (!Discover.configured()) { state = "off"; return@LaunchedEffect }
        val loaded = ROWS.mapNotNull { (k, t) -> runCatching { Discover.list(k) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { t to it } }
        rows = loaded; mem.data["rows"] = loaded; state = if (loaded.isEmpty()) "error" else "ok"
    }
    val firstFr = remember { FocusRequester() }
    InitialFocus(firstFr)
    val portrait = LocalPortrait.current
    Box(Modifier.fillMaxSize()) {
        Backdrop(focused?.backdrop)
        Column(Modifier.fillMaxSize()) {
            if (portrait) MobileTopBar("Discover") else Column(Modifier.padding(start = 20.dp, top = 26.dp, end = 300.dp).height(150.dp)) {
                val f = focused
                T(f?.title ?: "Discover", 28.sp, weight = FontWeight.Bold, shadow = true)
                if (f != null) {
                    T(listOfNotNull(f.year?.toString(), if (f.type == "tv") "TV show" else "Movie", f.rating?.let { "★ $it" }, f.statusLabel).joinToString("  •  "), 13.sp, C.text, FontWeight.SemiBold, shadow = true)
                    Spacer(Modifier.height(6.dp))
                    T(f.overview, 14.sp, C.text.copy(alpha = 0.92f), maxLines = 3, lineHeight = 19.sp, shadow = true)
                } else T("Trending and upcoming titles — request them from Overseerr.", 13.sp, C.text2, shadow = true)
            }
            when (state) {
                "loading" -> Loading()
                "off" -> Message("Overseerr isn't connected", "Add it in the Lumen web app: Settings → Discover. It then works in every app.")
                "error" -> Message("Couldn't reach Overseerr", "Check the address and API key in Settings → Discover (web).")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    rows.orEmpty().forEachIndexed { r, (title, items) ->
                        item(key = title) {
                            CardRow(title, startPad = if (portrait) 16.dp else 20.dp) {
                                itemsIndexed(items, key = { _, d -> "${d.type}${d.id}" }) { i, d ->
                                    DiscoverCard(d, "disc/$title/${d.id}", fr = if (r == 0 && i == 0) firstFr else null) { focused = d }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Details of an Overseerr title, with Request (seasons for shows) and Play when it's in your Plex library. */
@Composable
fun DiscoverItemScreen(s: Screen.DiscoverItem) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val chosen = remember { mutableStateListOf<Int>() }
    LaunchedEffect(s.id, reload) {
        runCatching { Net.obj("/api/discover/${s.type}/${s.id}") }
            .onSuccess { j -> d = j; chosen.clear(); chosen.addAll(j.optJSONArray("seasons").objects().filter { it.optString("status") == "none" }.map { it.optInt("number") }) }
            .onFailure { error = it.message }
    }
    val playFr = remember { FocusRequester() }
    InitialFocus(playFr)
    val j = d
    if (j == null) { if (error != null) Message("Couldn't load ${s.title}", error) else Loading(Modifier.padding(top = 160.dp)); return }
    val item = DItem.from(j)
    val seasons = j.optJSONArray("seasons").objects()
    val canRequest = item.status == "none" || seasons.any { it.optString("status") == "none" }
    val portrait = LocalPortrait.current
    Box(Modifier.fillMaxSize()) {
        Backdrop(item.backdrop, heightFraction = 1f, bleedLeft = 0.dp)
        LazyColumn(contentPadding = PaddingValues(start = if (portrait) 18.dp else 48.dp, end = if (portrait) 18.dp else 120.dp, top = 40.dp, bottom = 80.dp)) {
            item {
                Row {
                    if (item.poster != null) { Img(item.poster, Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(10.dp))); Spacer(Modifier.width(24.dp)) }
                    Column(Modifier.weight(1f)) {
                        T(item.title, 30.sp, weight = FontWeight.Bold, maxLines = 2, lineHeight = 34.sp, shadow = true)
                        Spacer(Modifier.height(6.dp))
                        T(listOfNotNull(item.year?.toString(), j.str("certification"), j.num("runtime")?.let { "${it.toInt()} min" },
                            j.optJSONArray("genres").strings().take(3).joinToString(", ").ifBlank { null }, item.rating?.let { "★ $it" }).joinToString("  •  "), 13.sp, C.text, FontWeight.SemiBold, shadow = true)
                        item.statusLabel?.let {
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(if (item.status == "available") Color(0xFF3CCF8E) else C.accent).padding(horizontal = 7.dp, vertical = 2.dp)) { T(it.uppercase(), 10.sp, Color.Black, FontWeight.Bold) }
                        }
                        Spacer(Modifier.height(10.dp))
                        T(item.overview.ifBlank { "No description." }, 14.sp, C.text.copy(alpha = 0.92f), maxLines = 6, lineHeight = 19.sp, shadow = true)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            var first = true
                            if (item.ratingKey != null) { PillButton("Play in Lumen", Icons.Filled.PlayArrow, primary = true, focusRequester = playFr) { Nav.push(Screen.Detail(item.ratingKey)) }; first = false }
                            if (canRequest) PillButton(if (item.type == "tv") "Request ${chosen.size} season${if (chosen.size == 1) "" else "s"}" else "Request", Icons.Filled.Add, primary = item.ratingKey == null, focusRequester = if (first) playFr else null) {
                                if (item.type == "tv" && chosen.isEmpty()) { Toast.makeText(ctx, "Choose at least one season", Toast.LENGTH_SHORT).show(); return@PillButton }
                                scope.launch {
                                    val body = JSONObject().put("type", item.type).put("id", item.id)
                                    if (item.type == "tv") body.put("seasons", JSONArray(chosen.toList()))
                                    runCatching { Net.post("/api/discover/request", body) }
                                        .onSuccess { Toast.makeText(ctx, "Requested ${item.title}", Toast.LENGTH_SHORT).show(); Net.clearCache(); reload++ }
                                        .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                                }
                            } else if (first) PillButton(item.statusLabel ?: "Requested", Icons.Filled.Check, focusRequester = playFr) {}
                        }
                    }
                }
            }
            if (item.type == "tv" && seasons.isNotEmpty()) item {
                Column(Modifier.padding(top = 22.dp)) {
                    T("Seasons", 15.sp, weight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(seasons, key = { _, x -> x.optInt("number") }) { _, x ->
                            val n = x.optInt("number"); val st = x.optString("status")
                            val have = st != "none"
                            Chip(
                                "${x.optString("name").ifBlank { "Season $n" }}${if (have) " ✓" else if (n in chosen) "  ●" else ""}",
                                n in chosen,
                            ) { if (!have) { if (n in chosen) chosen.remove(n) else chosen.add(n) } }
                        }
                    }
                    T("● = will be requested   ✓ = already available or requested", 11.sp, C.text3, modifier = Modifier.padding(top = 6.dp))
                }
            }
            val cast = j.optJSONArray("cast").objects()
            if (cast.isNotEmpty()) item {
                CardRow("Cast", modifier = Modifier.padding(top = 18.dp)) {
                    itemsIndexed(cast, key = { i, _ -> i }) { i, c ->
                        Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Focusable(onClick = {}, memKey = "dcast/$i", shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(68.dp), background = C.card) {
                                Img(c.str("photo"), Modifier.fillMaxSize(), ContentScale.Crop)
                            }
                            Spacer(Modifier.height(4.dp))
                            T(c.optString("name"), 11.sp)
                            T(c.optString("character"), 10.sp, C.text3)
                        }
                    }
                }
            }
        }
    }
}
