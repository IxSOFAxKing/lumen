package app.lumen.tv

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** Shared app state loaded from the Lumen server (the server is the single source of truth for every app). */
object AppState {
    var ui by mutableStateOf(JSONObject())
    var providers by mutableStateOf<List<Provider>>(emptyList())
    var plexConfigured by mutableStateOf(false)
    var plexOnline by mutableStateOf(false)
    var plexError by mutableStateOf<String?>(null)
    var libraries by mutableStateOf<List<Library>>(emptyList())
    var favLists by mutableStateOf<List<Group>>(emptyList())
    var loaded by mutableStateOf(false)
    var startApplied = false
    /** Overseerr is connected on the server (Discover). */
    var discoverOn by mutableStateOf(false)
    /** Running on a TV (leanback) rather than a phone/tablet. */
    var isTv = true
    var checkingProfile by mutableStateOf(false)
    /** Plex Home users to choose from at launch ("Who's watching?"), or null. */
    var askProfile by mutableStateOf<JSONObject?>(null)
    /** Set when the app comes back after a while in the background: ask who's watching again. */
    var profileCheckTick by mutableIntStateOf(0)

    /** "Who's watching?": show the Plex Home user picker (or switch to the default user if asking is turned off). */
    suspend fun checkProfile(force: Boolean = false) {
        if (!plexOnline) return
        val pu = runCatching { Net.obj("/api/plex/users") }.getOrNull() ?: return
        if (pu.optJSONArray("users").objects().size < 2) return
        if (force || Net.prefs.askProfile) askProfile = pu
        else if (pu.optBoolean("defaultSet")) runCatching { Net.post("/api/plex/users/reset") }
    }
    var loadError by mutableStateOf<String?>(null)
    /** Changes whenever the app comes back to the foreground (e.g. after the player), so rows refresh. */
    var resumeTick by mutableIntStateOf(0)

    var providerId by mutableIntStateOf(-1)
    val provider: Provider?
        get() = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()

    fun selectProvider(id: Int) { Net.prefs.providerId = id; providerId = id }

    suspend fun refresh() = coroutineScope {
        val settings = async { runCatching { Net.obj("/api/settings") }.getOrNull() }
        val prov = async { runCatching { Net.arr("/api/iptv/providers") }.getOrNull() }
        val plex = async { runCatching { Net.obj("/api/plex/status") }.getOrNull() }
        val libs = async { runCatching { Net.arr("/api/plex/libraries", 60_000) }.getOrNull() }
        val favs = async { runCatching { Net.arr("/api/iptv/fav-lists") }.getOrNull() }
        providerId = Net.prefs.providerId
        val disc = async { runCatching { Net.obj("/api/discover/config").optBoolean("configured") }.getOrDefault(false) }
        val s = settings.await()
        if (s == null && prov.await() == null) {
            loadError = "Can't reach your Lumen server at ${Net.server}"
        } else loadError = null
        s?.optJSONObject("ui")?.let { ui = it }
        prov.await()?.let { a -> providers = a.objects().map { Provider.from(it) }.filter { it.enabled } }
        plex.await()?.let {
            plexConfigured = it.optBoolean("configured")
            plexOnline = it.optBoolean("online")
            plexError = it.str("error")
        }
        if (plexOnline) libs.await()?.let { a -> libraries = a.objects().map { Library.from(it) }.filter { it.type == "movie" || it.type == "show" } }
        favs.await()?.let { a -> favLists = a.objects().map { Group("__fav:${it.optInt("id")}", it.optString("name"), it.optInt("count")) } }
        discoverOn = disc.await()
        loaded = true
    }

    // ---------- server UI settings (shared with the web app) ----------
    fun uiStr(key: String, def: String = ""): String = ui.str(key) ?: def
    fun uiBool(key: String, def: Boolean): Boolean = if (ui.has(key) && !ui.isNull(key)) ui.optBoolean(key, def) else def
    fun uiNum(key: String, def: Double): Double = ui.num(key) ?: def
    fun uiList(key: String): List<String> = ui.optJSONArray(key).strings()

    suspend fun saveUi(patch: JSONObject) {
        val next = JSONObject(ui.toString())
        patch.keys().forEach { k -> next.put(k, patch.get(k)) }
        ui = next
        runCatching { Net.put("/api/settings/ui", patch) }
    }

    /** Ordering helper identical to the web app's applyOrder: saved order first, new items after their default predecessor. */
    fun <T : Any> applyOrder(items: List<T>, order: List<String>, id: (T) -> String): List<T> {
        val byId = items.associateBy(id)
        val out = order.mapNotNull { byId[it] }.toMutableList()
        val placed = out.map(id).toMutableSet()
        items.forEachIndexed { i, it ->
            if (id(it) in placed) return@forEachIndexed
            val prev = items.subList(0, i).asReversed().firstOrNull { p -> id(p) in placed }
            val at = if (prev != null) out.indexOf(prev) + 1 else 0
            out.add(at, it)
            placed.add(id(it))
        }
        return out
    }

    val accent: Long
        get() = runCatching { java.lang.Long.parseLong(uiStr("accent", "#ff9f3d").removePrefix("#"), 16) or 0xFF000000 }.getOrDefault(0xFFFF9F3D)
}

/** Home screen sections, same ids and defaults as the web app. */
data class HomeSection(val id: String, val label: String, val pin: JSONObject? = null)

val HOME_SECTIONS = listOf(
    HomeSection("continue", "Continue Watching"),
    HomeSection("liveFavorites", "Live TV · Favorites"),
    HomeSection("recentMovies", "Recently Added Movies"),
    HomeSection("recentShows", "Recently Added TV Shows"),
    HomeSection("recentChannels", "Recently Watched Channels"),
    HomeSection("plexHubs", "More from Plex (Top Rated, On Deck…)"),
    HomeSection("playlists", "Plex Playlists"),
)

fun homeSections(includeHidden: Boolean = false): List<HomeSection> {
    val pins = AppState.ui.optJSONArray("pinnedRows").objects().map { HomeSection("pin:${it.optString("id")}", it.optString("title"), it) }
    val hidden = AppState.uiList("homeHidden").toSet()
    return AppState.applyOrder(pins + HOME_SECTIONS, AppState.uiList("homeOrder")) { it.id }.filter { includeHidden || it.id !in hidden }
}

object Repo {
    suspend fun plexHome(): JSONObject = Net.obj("/api/plex/home", 30_000)
    suspend fun hubsFor(lib: String): List<Hub> = Net.arr("/api/plex/library/$lib/hubs", 60_000).objects().map { Hub.from(it) }.filter { it.items.isNotEmpty() }
    suspend fun meta(key: String): PlexItem = PlexItem.from(Net.obj("/api/plex/meta/${Uri.encode(key)}"))
    suspend fun children(key: String): List<PlexItem> = Net.arr("/api/plex/meta/${Uri.encode(key)}/children", 20_000).objects().map { PlexItem.from(it) }
    suspend fun leaves(key: String): List<PlexItem> = Net.arr("/api/plex/meta/${Uri.encode(key)}/leaves", 20_000).objects().map { PlexItem.from(it) }
    suspend fun related(key: String): List<Hub> = Net.arr("/api/plex/meta/${Uri.encode(key)}/related", 600_000).objects().map { Hub.from(it) }.filter { it.items.isNotEmpty() }
    suspend fun collection(key: String): List<PlexItem> = Net.arr("/api/plex/collection/${Uri.encode(key)}/items", 120_000).objects().map { PlexItem.from(it) }
    suspend fun playlist(key: String): List<PlexItem> = Net.obj("/api/plex/playlist/${Uri.encode(key)}", 60_000).optJSONArray("items").objects().map { PlexItem.from(it) }
    suspend fun libraryItems(lib: String, sort: String, start: Int, size: Int, unwatched: Boolean = false, genre: String? = null): Pair<List<PlexItem>, Int> {
        val q = StringBuilder("/api/plex/library/$lib/items?sort=${Uri.encode(sort)}&start=$start&size=$size")
        if (unwatched) q.append("&unwatched=1")
        if (genre != null) q.append("&genre=${Uri.encode(genre)}")
        val j = Net.obj(q.toString(), 60_000)
        return j.optJSONArray("items").objects().map { PlexItem.from(it) } to j.optInt("total")
    }
    suspend fun plexSearch(q: String): List<Hub> = Net.arr("/api/plex/search?q=${Uri.encode(q)}").objects().map { Hub.from(it) }.filter { it.items.isNotEmpty() }
    suspend fun setWatched(key: String, watched: Boolean) { Net.post("/api/plex/${if (watched) "watched" else "unwatched"}/${Uri.encode(key)}") }

    // ---------- IPTV ----------
    fun channels(a: JSONArray, pid: Int? = null) = a.objects().map { Channel.from(it, pid) }
    suspend fun favorites(list: Int? = null): List<Channel> = channels(Net.arr("/api/iptv/favorites" + (list?.let { "?list=$it" } ?: ""), 15_000))
    suspend fun history(): List<Channel> = channels(Net.arr("/api/iptv/history", 15_000))
    suspend fun channelsIn(pid: Int, group: String): List<Channel> =
        channels(Net.arr("/api/iptv/providers/$pid/channels?category=${Uri.encode(group)}", 20_000), pid)
    suspend fun groups(pid: Int): List<Group> {
        val cats = Net.arr("/api/iptv/providers/$pid/categories?kind=live", 60_000).objects().map { Group(it.optString("id"), it.optString("name"), it.optInt("count")) }
        return AppState.favLists.map { it.copy(name = "★  ${it.name}") } + Group("__recent", "Recently watched") + Group("__all", "All channels") + cats
    }
    suspend fun guide(pid: Int, group: String, from: Long, to: Long, offset: Int, limit: Int): Pair<List<Channel>, Int> {
        val j = Net.obj("/api/iptv/guide/$pid?category=${Uri.encode(group)}&from=$from&to=$to&offset=$offset&limit=$limit", 30_000)
        return channels(j.optJSONArray("channels"), pid) to j.optInt("total")
    }
    suspend fun channelSearch(q: String): List<Channel> = channels(Net.obj("/api/iptv/search?q=${Uri.encode(q)}").optJSONArray("channels"))

    suspend fun toggleFavorite(ch: Channel, listId: Int? = null): Boolean {
        val list = listId ?: AppState.favLists.firstOrNull()?.id?.removePrefix("__fav:")?.toIntOrNull()
        return if (ch.isFav && (listId == null || listId in ch.favLists)) {
            Net.delete("/api/iptv/favorites/${ch.providerId}/${Uri.encode(ch.id)}" + (listId?.let { "?list=$it" } ?: ""))
            false
        } else {
            Net.post("/api/iptv/favorites", JSONObject().put("providerId", ch.providerId).put("channelId", ch.id).apply { if (list != null) put("listId", list) })
            true
        }
    }

    suspend fun record(ch: Channel, p: Programme): String {
        Net.post("/api/iptv/recordings", JSONObject().put("providerId", ch.providerId).put("channelId", ch.id).put("title", p.title).put("descr", p.descr ?: "").put("start", p.start).put("stop", p.stop))
        return "Recording scheduled: ${p.title}"
    }
}
