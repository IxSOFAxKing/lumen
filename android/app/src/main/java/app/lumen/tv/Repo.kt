package app.lumen.tv

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
        val pu = runCatching { Plex.users() }.getOrNull() ?: return
        if (pu.optJSONArray("users").objects().size < 2) return
        if (force || Net.prefs.askProfile) askProfile = pu
        else if (pu.optBoolean("defaultSet")) runCatching { Plex.resetUser() }
    }

    /** Switch Plex Home user on this device (and tell the Lumen server, which uses it for Discover). */
    suspend fun switchUser(uuid: String, pin: String?, makeDefault: Boolean = false): JSONObject {
        val u = Plex.switchUser(uuid, pin, makeDefault)
        if (Net.prefs.isPaired) runCatching { Net.post("/api/plex/users/switch", JSONObject().put("uuid", u.optString("uuid"))) }
        Net.clearCache(); Nav.memory.clear()
        discoverOn = lumenDiscover && !Net.prefs.plexUserRestricted
        return u
    }
    var loadError by mutableStateOf<String?>(null)
    /** A "now available" alert being shown (from the Lumen server's Overseerr check). */
    var alert by mutableStateOf<JSONObject?>(null)
    /** Changes whenever the app comes back to the foreground (e.g. after the player), so rows refresh. */
    var resumeTick by mutableIntStateOf(0)

    var providerId by mutableIntStateOf(-1)
    val provider: Provider?
        get() = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()

    fun selectProvider(id: Int) { Net.prefs.providerId = id; providerId = id }

    /** The Lumen server is connected (optional: Live TV, Discover and shared settings live there). */
    val hasLumen: Boolean get() = Net.prefs.isPaired
    /** What the Lumen server lets this account use. */
    var perms by mutableStateOf(JSONObject())
    private var lumenDiscover = false

    // ---------- start-up snapshot: show the last known state instantly, then refresh ----------
    private fun snapFile() = java.io.File(Nav.appContext.filesDir, "snapshot.json")
    fun loadSnapshot(): Boolean = runCatching {
        val j = JSONObject(snapFile().readText())
        if (j.optString("server") != (Plex.serverName + "|" + Net.server)) return false
        ui = j.optJSONObject("ui") ?: JSONObject()
        providers = j.optJSONArray("providers").objects().map { Provider.from(it) }.filter { it.enabled }
        libraries = j.optJSONArray("libraries").objects().map { Library.from(it) }.filter { it.type == "movie" || it.type == "show" }
        favLists = j.optJSONArray("favLists").objects().map { Group("__fav:${it.optInt("id")}", it.optString("name"), it.optInt("count")) }
        perms = j.optJSONObject("perms") ?: JSONObject()
        lumenDiscover = j.optBoolean("discover"); discoverOn = lumenDiscover && !Net.prefs.plexUserRestricted
        plexConfigured = Plex.signedIn && Plex.hasServer; plexOnline = plexConfigured && libraries.isNotEmpty()
        providerId = Net.prefs.providerId
        loaded = true
        true
    }.getOrDefault(false)

    suspend fun refresh() = coroutineScope {
        val lumen = hasLumen
        val settings = async { if (lumen) runCatching { Net.obj("/api/settings") }.getOrNull() else null }
        val prov = async { if (lumen) runCatching { Net.arr("/api/iptv/providers") }.getOrNull() else null }
        val favs = async { if (lumen) runCatching { Net.arr("/api/iptv/fav-lists") }.getOrNull() else null }
        val disc = async { if (lumen) runCatching { Net.obj("/api/discover/config").optBoolean("configured") }.getOrDefault(false) else false }
        val auth = async { if (lumen) runCatching { Net.obj("/api/auth/status") }.getOrNull() else null }
        // Plex: find the server (the address can change between home and away).
        plexConfigured = Plex.signedIn && Plex.hasServer
        val online = plexConfigured && runCatching { Plex.connect() }.getOrDefault(false)
        plexOnline = online
        plexError = if (plexConfigured && !online) "Can't reach the Plex server \"${Plex.serverName}\"" else null
        providerId = Net.prefs.providerId
        val s = settings.await()
        loadError = if (lumen && s == null && prov.await() == null && !online) "Can't reach your Lumen server at ${Net.server}" else null
        ui = s?.optJSONObject("ui") ?: runCatching { JSONObject(Net.prefs.localUi ?: "{}") }.getOrDefault(JSONObject())
        providers = prov.await()?.objects()?.map { Provider.from(it) }?.filter { it.enabled } ?: emptyList()
        val snapLibs = if (online) runCatching { Plex.libraries() }.getOrNull() else null
        snapLibs?.let { a -> libraries = a.objects().map { Library.from(it) }.filter { it.type == "movie" || it.type == "show" } }
        favLists = favs.await()?.objects()?.map { Group("__fav:${it.optInt("id")}", it.optString("name"), it.optInt("count")) } ?: emptyList()
        auth.await()?.optJSONObject("perms")?.let { perms = it }
        lumenDiscover = disc.await()
        discoverOn = lumenDiscover && !Net.prefs.plexUserRestricted
        loaded = true
        if (online) runCatching {
            snapFile().writeText(JSONObject().put("server", Plex.serverName + "|" + Net.server).put("ui", ui)
                .put("providers", prov.await() ?: JSONArray()).put("libraries", snapLibs ?: JSONArray()).put("favLists", favs.await() ?: JSONArray())
                .put("perms", perms).put("discover", lumenDiscover).toString())
        }
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
        if (hasLumen) runCatching { Net.put("/api/settings/ui", patch) } else Net.prefs.localUi = next.toString()
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
    // Plex: straight from the Plex server (see PlexDirect.kt).
    private var homeFromDisk = false
    /** First time after start-up: the saved home screen straight away, refreshed in the background. */
    suspend fun plexHome(): JSONObject {
        if (!homeFromDisk) {
            homeFromDisk = true
            Plex.diskHome()?.let { saved ->
                AppScope.launch { runCatching { Plex.home() }.onSuccess { Plex.saveHome(it); AppState.resumeTick++ } }
                return saved
            }
        }
        return Plex.home().also { Plex.saveHome(it) }
    }
    suspend fun hubsFor(lib: String): List<Hub> = Plex.libraryHubs(lib).objects().map { Hub.from(it) }.filter { it.items.isNotEmpty() }
    suspend fun meta(key: String): PlexItem = PlexItem.from(Plex.meta(key))
    suspend fun children(key: String): List<PlexItem> = Plex.children(key).objects().map { PlexItem.from(it) }
    suspend fun leaves(key: String): List<PlexItem> = Plex.leaves(key).objects().map { PlexItem.from(it) }
    suspend fun related(key: String): List<Hub> = Plex.related(key).objects().map { Hub.from(it) }.filter { it.items.isNotEmpty() }
    suspend fun collection(key: String): List<PlexItem> = Plex.collection(key).objects().map { PlexItem.from(it) }
    suspend fun playlist(key: String): List<PlexItem> = Plex.playlist(key).optJSONArray("items").objects().map { PlexItem.from(it) }
    suspend fun libraryItems(lib: String, sort: String, start: Int, size: Int, unwatched: Boolean = false, genre: String? = null): Pair<List<PlexItem>, Int> {
        val j = Plex.libraryItems(lib, sort, start, size, unwatched, genre)
        return j.optJSONArray("items").objects().map { PlexItem.from(it) } to j.optInt("total")
    }
    suspend fun plexSearch(q: String): List<Hub> = Plex.search(q).objects().map { Hub.from(it) }.filter { it.items.isNotEmpty() }
    suspend fun setWatched(key: String, watched: Boolean) = Plex.setWatched(key, watched)

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
