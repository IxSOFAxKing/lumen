package app.lumen.tv

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The app talks to Plex directly: it signs in to plex.tv itself (plex.tv/link code), finds the Plex server and the
 * best address for it, and browses / plays from it. The device's own hardware decodes the original file whenever
 * it can. The Lumen server is optional (Live TV, Discover and shared settings).
 *
 * The functions below return the same JSON shapes the Lumen server's /api/plex routes used to, so the screens
 * didn't need to change.
 */
object Plex {
    class PlexError(val code: Int, message: String) : java.io.IOException(message)
    data class Conn(val uri: String, val local: Boolean, val relay: Boolean)
    data class Server(val name: String, val machineId: String, val owned: Boolean, val accessToken: String?, val connections: List<Conn>)

    private lateinit var ctx: Context
    private val prefs get() = Net.prefs
    fun init(c: Context) { if (!::ctx.isInitialized) ctx = c.applicationContext }

    /** Address of the Plex server that answered (null until connected). */
    @Volatile var baseUrl: String? = null
        private set
    @Volatile var isLocal = true
        private set

    val signedIn: Boolean get() = !prefs.plexToken.isNullOrBlank()
    val hasServer: Boolean get() = !prefs.plexMachineId.isNullOrBlank()
    /** Token for the Plex server, for the current Home user. */
    val token: String get() = prefs.plexUserToken ?: prefs.plexServerToken ?: prefs.plexToken ?: ""
    val serverName: String get() = prefs.plexServerName ?: "Plex"
    val owned: Boolean get() = prefs.plexOwned

    private val clientId: String get() = prefs.plexClientId ?: UUID.randomUUID().toString().also { prefs.plexClientId = it }
    private val device: String get() = (Build.MODEL ?: "Android").trim()

    private fun Request.Builder.plex(token: String?): Request.Builder = apply {
        header("Accept", "application/json")
        header("X-Plex-Product", "Lumen")
        header("X-Plex-Version", BuildConfig.VERSION_NAME)
        header("X-Plex-Client-Identifier", clientId)
        header("X-Plex-Platform", "Android")
        header("X-Plex-Platform-Version", Build.VERSION.RELEASE ?: "")
        header("X-Plex-Device", device)
        header("X-Plex-Device-Name", "Lumen ($device)")
        header("X-Plex-Model", if (AppState.isTv) "tv" else "phone")
        if (!token.isNullOrBlank()) header("X-Plex-Token", token)
    }

    private val quick by lazy { Net.http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build() }

    private suspend fun http(method: String, url: String, token: String?, client: okhttp3.OkHttpClient = Net.http, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).plex(token)
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null))
        client.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw PlexError(r.code, when (r.code) {
                401 -> "Plex didn't accept the sign-in (${r.code})"
                403 -> "Plex refused this (${r.code})"
                404 -> "Not found on Plex"
                else -> "Plex responded ${r.code}"
            })
            text
        }
    }

    private fun tvUrl(path: String, params: Map<String, Any?> = emptyMap()) = Uri.parse("https://plex.tv$path").buildUpon().apply {
        params.forEach { (k, v) -> if (v != null && v.toString().isNotEmpty()) appendQueryParameter(k, v.toString()) }
    }.build().toString()

    // ------------------------------------------------------------------ sign in (plex.tv/link)
    data class Pin(val id: String, val code: String)

    /** strong = false: a 4-character code to enter at plex.tv/link. strong = true: for the sign-in link in the QR code. */
    suspend fun createPin(strong: Boolean = false): Pin {
        val j = JSONObject(http("POST", tvUrl("/api/v2/pins", mapOf("strong" to strong.toString())), null))
        return Pin(j.optString("id"), j.optString("code"))
    }

    /** Plex's sign-in page for a strong PIN (what the QR code opens on a phone). */
    fun authUrl(code: String): String = "https://app.plex.tv/auth#?" + Uri.Builder()
        .appendQueryParameter("clientID", clientId).appendQueryParameter("code", code)
        .appendQueryParameter("context[device][product]", "Lumen").build().encodedQuery

    /** The account token once the code was entered, else null. */
    suspend fun checkPin(id: String): String? =
        JSONObject(http("GET", tvUrl("/api/v2/pins/$id"), null)).str("authToken")

    suspend fun account(token: String): JSONObject = JSONObject(http("GET", tvUrl("/api/v2/user"), token))

    suspend fun servers(token: String): List<Server> {
        val a = JSONArray(http("GET", tvUrl("/api/v2/resources", mapOf("includeHttps" to 1, "includeRelay" to 1)), token))
        return a.objects().filter { it.optString("provides").contains("server") }.map { r ->
            Server(r.optString("name"), r.optString("clientIdentifier"), r.optBoolean("owned"), r.str("accessToken"),
                r.optJSONArray("connections").objects().map { Conn(it.optString("uri"), it.optBoolean("local"), it.optBoolean("relay")) })
        }
    }

    /** First reachable address: on the home network first, then remote access, then Plex's relay. */
    suspend fun pickConnection(s: Server): Conn? {
        val ordered = s.connections.sortedWith(compareBy({ it.relay }, { !it.local }))
        // Try the direct ones together (fast), then the relay.
        val direct = ordered.filter { !it.relay }
        val found = coroutineScope {
            direct.map { c -> async { if (reachable(c.uri, s.accessToken)) c else null } }.awaitAll()
        }.filterNotNull()
        found.firstOrNull { it.local }?.let { return it }
        found.firstOrNull()?.let { return it }
        return ordered.filter { it.relay }.firstOrNull { reachable(it.uri, s.accessToken) }
    }

    private suspend fun reachable(uri: String, token: String?): Boolean =
        runCatching { http("GET", "$uri/identity", token, quick); true }.getOrDefault(false)

    /** Use this server (after signing in). */
    fun choose(s: Server, conn: Conn?, accountToken: String) {
        prefs.plexToken = accountToken
        prefs.plexMachineId = s.machineId
        prefs.plexServerName = s.name
        prefs.plexServerToken = s.accessToken ?: accountToken
        prefs.plexOwned = s.owned
        prefs.plexUserToken = null; prefs.plexUserUuid = null
        conn?.let { baseUrl = it.uri; isLocal = it.local; prefs.plexUrl = it.uri }
        cache.clear()
    }

    /** Find the server again (the address can change, e.g. at home vs away). */
    suspend fun connect(): Boolean {
        val acct = prefs.plexToken ?: return false
        val mid = prefs.plexMachineId ?: return false
        // The last address that worked is tried first: no plex.tv round trip when nothing changed.
        prefs.plexUrl?.let { last -> if (reachable(last, token)) { baseUrl = last; isLocal = isPrivate(last); return true } }
        val s = runCatching { servers(acct) }.getOrNull()?.firstOrNull { it.machineId == mid }
        if (s == null) { baseUrl = null; return false }
        if (s.accessToken != null && s.accessToken != prefs.plexServerToken) prefs.plexServerToken = s.accessToken
        val c = pickConnection(s) ?: run { baseUrl = null; return false }
        baseUrl = c.uri; isLocal = c.local; prefs.plexUrl = c.uri
        return true
    }

    private fun isPrivate(uri: String): Boolean {
        val host = Uri.parse(uri).host ?: return false
        // plex.direct names embed the address: 192-168-1-5.<hash>.plex.direct
        val ip = if (host.endsWith(".plex.direct")) host.substringBefore('.').replace('-', '.') else host
        return Regex("^(10\\.|127\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|169\\.254\\.)").containsMatchIn(ip)
    }

    fun signOut() {
        prefs.plexToken = null; prefs.plexMachineId = null; prefs.plexServerName = null; prefs.plexServerToken = null
        prefs.plexUserToken = null; prefs.plexUserUuid = null; prefs.plexUrl = null; prefs.plexDefaultUser = null
        baseUrl = null; cache.clear()
    }

    // ------------------------------------------------------------------ Plex server requests
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    fun clearCache() = cache.clear()

    fun url(path: String, params: Map<String, Any?> = emptyMap(), tok: String = token): String {
        val base = baseUrl ?: prefs.plexUrl ?: throw PlexError(0, "Plex server not reachable")
        return Uri.parse(base + (if (path.startsWith("/")) path else "/$path")).buildUpon().apply {
            params.forEach { (k, v) -> if (v != null) appendQueryParameter(k, v.toString()) }
            appendQueryParameter("X-Plex-Token", tok)
        }.build().toString()
    }

    /** GET a Plex endpoint; returns its MediaContainer. */
    suspend fun get(path: String, params: Map<String, Any?> = emptyMap(), ttlMs: Long = 0, headers: Map<String, String> = emptyMap()): JSONObject {
        val key = (prefs.plexUserUuid ?: "") + "|" + path + "|" + params + "|" + headers
        if (ttlMs > 0) cache[key]?.let { (t, v) -> if (System.currentTimeMillis() - t < ttlMs) return v }
        val text = http("GET", url(path, params), token, headers = headers)
        val mc = runCatching { JSONObject(text).optJSONObject("MediaContainer") }.getOrNull() ?: JSONObject()
        if (ttlMs > 0) cache[key] = System.currentTimeMillis() to mc
        return mc
    }

    suspend fun send(method: String, path: String, params: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap()) {
        http(method, url(path, params), token, headers = headers)
    }

    /** Resized artwork straight from Plex's photo transcoder. */
    fun img(path: String?, w: Int, h: Int): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return runCatching { url("/photo/:/transcode", mapOf("url" to path, "width" to w, "height" to h, "minSize" to 1, "upscale" to 1)) }.getOrNull()
    }

    // ------------------------------------------------------------------ browsing
    suspend fun libraries(): JSONArray = JSONArray(get("/library/sections", ttlMs = 300_000).optJSONArray("Directory").objects())

    suspend fun home(): JSONObject = coroutineScope {
        val hubsJ = async { runCatching { get("/hubs", mapOf("count" to 20, "includeEmptyHubs" to 0, "excludeFields" to "summary"), 60_000) }.getOrNull() }
        val contJ = async { runCatching { get("/hubs/home/continueWatching", mapOf("count" to 20), 30_000) }.getOrNull() }
        val playJ = async { runCatching { get("/playlists", mapOf("playlistType" to "video"), 120_000) }.getOrNull() }
        val libs = runCatching { get("/library/sections", ttlMs = 300_000) }.getOrNull()?.optJSONArray("Directory").objects()
            .filter { it.optString("type") == "movie" || it.optString("type") == "show" }
        val lists = libs.take(8).map { l ->
            async {
                val type = if (l.optString("type") == "movie") 1 else 2
                val sorts = if (type == 1) listOf("addedAt:desc") else listOf("episode.addedAt:desc", "addedAt:desc")
                var items = emptyList<JSONObject>()
                for (sort in sorts) {
                    val r = runCatching { get("/library/sections/${l.optString("key")}/all", mapOf("type" to type, "sort" to sort),
                        60_000, mapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to "24")) }.getOrNull()
                    if (r != null) { items = r.optJSONArray("Metadata").objects(); break }
                }
                l.optString("type") to items
            }
        }.awaitAll()
        var hubs = hubsJ.await()?.optJSONArray("Hub").objects()
        val cw = contJ.await()?.optJSONArray("Hub").objects().firstOrNull()
        if (cw != null && hubs.none { it.optString("hubIdentifier") == cw.optString("hubIdentifier") || it.optString("hubIdentifier") == "home.continue" }) hubs = listOf(cw) + hubs
        hubs = hubs.filter { !Regex("recentlyadded|recently_added|home\\.movies\\.recent|home\\.television\\.recent", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("hubIdentifier")) }
        val recentMovies = lists.filter { it.first == "movie" }.flatMap { it.second }.sortedByDescending { it.optLong("addedAt") }.take(30)
        val recentShows = lists.filter { it.first == "show" }.flatMap { it.second }.sortedByDescending { if (it.has("updatedAt")) it.optLong("updatedAt") else it.optLong("addedAt") }.take(30)
        val featured = mutableListOf<JSONObject>()
        var i = 0
        while (featured.size < 8 && (i < recentMovies.size || i < recentShows.size)) {
            for (m in listOfNotNull(recentMovies.getOrNull(i), recentShows.getOrNull(i))) if (m.has("art") && featured.size < 8) featured += m
            i++
        }
        JSONObject().put("hubs", JSONArray(hubs)).put("playlists", JSONArray(playJ.await()?.optJSONArray("Metadata").objects()))
            .put("featured", JSONArray(featured)).put("recentMovies", JSONArray(recentMovies)).put("recentShows", JSONArray(recentShows))
    }

    // The home screen as last loaded, kept on disk so the app can show it instantly at start-up.
    private fun homeFile() = File(ctx.filesDir, "home-${prefs.plexUserUuid ?: "me"}.json")
    fun diskHome(): JSONObject? = runCatching { homeFile().takeIf { it.exists() }?.readText()?.let { JSONObject(it) } }.getOrNull()
    fun saveHome(j: JSONObject) { runCatching { homeFile().writeText(j.toString()) } }

    suspend fun libraryHubs(id: String): JSONArray = coroutineScope {
        val hubsJ = async { get("/hubs/sections/$id", mapOf("count" to 20, "includeEmptyHubs" to 0), 60_000) }
        val popJ = async { runCatching { get("/library/sections/$id/all", mapOf("sort" to "viewCount:desc"), 60_000, mapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to "20")) }.getOrNull() }
        val list = hubsJ.await().optJSONArray("Hub").objects().toMutableList()
        val pop = popJ.await()?.optJSONArray("Metadata").objects().filter { it.optInt("viewCount") > 0 || it.optInt("viewedLeafCount") > 0 }
        if (pop.isNotEmpty()) list.add(minOf(2, list.size), JSONObject().put("hubIdentifier", "lumen.popular").put("title", "Most Popular").put("Metadata", JSONArray(pop)).put("size", pop.size))
        JSONArray(list)
    }

    suspend fun libraryItems(id: String, sort: String, start: Int, size: Int, unwatched: Boolean, genre: String?): JSONObject {
        val p = mutableMapOf<String, Any?>("sort" to sort, "includeCollections" to 0)
        if (unwatched) p["unwatched"] = 1
        if (genre != null) p["genre"] = genre
        val m = get("/library/sections/$id/all", p, 60_000, mapOf("X-Plex-Container-Start" to start.toString(), "X-Plex-Container-Size" to size.toString()))
        return JSONObject().put("items", m.optJSONArray("Metadata") ?: JSONArray()).put("total", if (m.has("totalSize")) m.optInt("totalSize") else m.optInt("size")).put("start", start)
    }

    suspend fun meta(key: String): JSONObject =
        get("/library/metadata/${Uri.encode(key)}", mapOf("includeExtras" to 1, "includeOnDeck" to 1, "includeChildren" to 1, "includeReviews" to 0, "includeMarkers" to 1, "checkFiles" to 0), 15_000)
            .optJSONArray("Metadata").objects().firstOrNull() ?: throw PlexError(404, "Not found")

    suspend fun children(key: String): JSONArray = get("/library/metadata/${Uri.encode(key)}/children", mapOf("excludeAllLeaves" to 1), 20_000).optJSONArray("Metadata") ?: JSONArray()
    suspend fun leaves(key: String): JSONArray = get("/library/metadata/${Uri.encode(key)}/allLeaves", ttlMs = 20_000).optJSONArray("Metadata") ?: JSONArray()
    suspend fun related(key: String): JSONArray = runCatching { get("/hubs/metadata/${Uri.encode(key)}/related", mapOf("count" to 20), 600_000).optJSONArray("Hub") }.getOrNull() ?: JSONArray()
    suspend fun collection(key: String): JSONArray = get("/library/collections/${Uri.encode(key)}/children", ttlMs = 120_000).optJSONArray("Metadata") ?: JSONArray()
    suspend fun playlist(key: String): JSONObject = coroutineScope {
        val m = async { get("/playlists/${Uri.encode(key)}").optJSONArray("Metadata").objects().firstOrNull() ?: JSONObject() }
        val items = async { get("/playlists/${Uri.encode(key)}/items").optJSONArray("Metadata") ?: JSONArray() }
        JSONObject(m.await().toString()).put("items", items.await())
    }
    suspend fun search(q: String): JSONArray {
        val hubs = get("/hubs/search", mapOf("query" to q, "limit" to 20, "includeCollections" to 1)).optJSONArray("Hub").objects()
        return JSONArray(hubs.filter { it.optJSONArray("Metadata").objects().isNotEmpty() && it.optString("type") in setOf("movie", "show", "episode", "season", "collection") })
    }
    suspend fun setWatched(key: String, watched: Boolean) {
        send("GET", if (watched) "/:/scrobble" else "/:/unscrobble", mapOf("identifier" to "com.plexapp.plugins.library", "key" to key))
        cache.clear()
    }
    suspend fun removeFromContinue(key: String) {
        runCatching { send("PUT", "/actions/removeFromContinueWatching", mapOf("ratingKey" to key)) }
        cache.clear()
    }
    suspend fun timeline(ratingKey: String, state: String, time: Double, duration: Double, session: String?) {
        runCatching {
            send("GET", "/:/timeline", mapOf("ratingKey" to ratingKey, "key" to "/library/metadata/$ratingKey", "state" to state, "time" to (time * 1000).toLong(),
                "duration" to (duration * 1000).toLong(), "context" to "library", "hasMDE" to 1), if (session != null) mapOf("X-Plex-Session-Identifier" to session) else emptyMap())
        }
        if (state != "playing") cache.clear()
    }

    /** Seek-bar preview images (Plex "BIF" index) and single frames. */
    fun bifUrl(partId: String): String? = runCatching { url("/library/parts/$partId/indexes/sd", mapOf("interval" to 10000)) }.getOrNull()
    fun thumbUrl(partId: String, ms: Long): String? = runCatching { url("/library/parts/$partId/indexes/sd/$ms") }.getOrNull()

    // ------------------------------------------------------------------ Plex Home users ("Who's watching?")
    private fun xmlElements(text: String, tag: String): List<Map<String, String>> {
        fun dec(v: String) = v.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        return Regex("<$tag\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(text).map { m ->
            Regex("([\\w:-]+)=\"([^\"]*)\"").findAll(m.groupValues[1]).associate { it.groupValues[1] to dec(it.groupValues[2]) }
        }.toList()
    }
    private fun bool(v: Any?) = v == true || v == 1 || v == "1" || v == "true"

    /** Users of the Plex Home (only the server's owner can switch between them). */
    suspend fun homeUsers(): List<JSONObject> {
        val acct = prefs.plexToken ?: return emptyList()
        val text = http("GET", tvUrl("/api/home/users"), acct)
        val list: List<Map<String, Any?>> = runCatching {
            val j = JSONObject(text)
            (j.optJSONArray("users") ?: j.optJSONObject("MediaContainer")?.optJSONArray("User")).objects().map { o -> o.keys().asSequence().associateWith { o.opt(it) } }
        }.getOrNull() ?: xmlElements(text, "User")
        return list.map { u ->
            JSONObject().put("id", u["id"].toString()).put("uuid", (u["uuid"] ?: u["id"]).toString())
                .put("title", (u["title"] ?: u["friendlyName"] ?: u["username"] ?: "User").toString()).put("thumb", u["thumb"]?.toString())
                .put("admin", bool(u["admin"])).put("protected", bool(u["protected"]) || bool(u["hasPassword"])).put("restricted", bool(u["restricted"]))
        }
    }

    /** Server tokens of everyone the server is shared with, so the owner can switch without PINs. userID -> token. */
    private suspend fun sharedTokens(): Map<String, String> {
        val acct = prefs.plexToken ?: return emptyMap()
        val mid = prefs.plexMachineId ?: return emptyMap()
        val text = http("GET", tvUrl("/api/servers/$mid/shared_servers"), acct)
        val list: List<Map<String, Any?>> = runCatching {
            val j = JSONObject(text)
            (j.optJSONObject("MediaContainer")?.optJSONArray("SharedServer") ?: j.optJSONArray("SharedServer")).objects().map { o -> o.keys().asSequence().associateWith { o.opt(it) } }
        }.getOrNull() ?: xmlElements(text, "SharedServer")
        return list.mapNotNull { s -> val id = s["userID"]?.toString(); val t = s["accessToken"]?.toString(); if (id != null && t != null) id to t else null }.toMap()
    }

    /** Same shape as the old /api/plex/users: { users, current, default, defaultSet, own }. */
    suspend fun users(): JSONObject {
        if (!owned) {
            // Signed in as a Home member or a friend: always yourself.
            val me = runCatching { account(prefs.plexToken!!) }.getOrNull()
            val u = JSONObject().put("uuid", me?.str("uuid") ?: "me").put("title", me?.str("title") ?: me?.str("username") ?: "You").put("thumb", me?.str("thumb")).put("admin", false).put("known", true)
            return JSONObject().put("users", JSONArray().put(u)).put("current", u.optString("uuid")).put("default", u.optString("uuid")).put("defaultSet", false).put("own", true)
        }
        val list = homeUsers()
        val cur = prefs.plexUserUuid ?: list.firstOrNull { it.optBoolean("admin") }?.optString("uuid")
        val def = prefs.plexDefaultUser
        return JSONObject().put("users", JSONArray(list.map { JSONObject(it.toString()).put("known", true) }))
            .put("current", cur).put("default", def ?: list.firstOrNull { it.optBoolean("admin") }?.optString("uuid")).put("defaultSet", def != null)
    }

    /** Switch to a Home user (PIN only needed when Plex won't hand the owner their token). */
    suspend fun switchUser(uuid: String, pin: String?, makeDefault: Boolean = false): JSONObject {
        val u = homeUsers().firstOrNull { it.optString("uuid") == uuid || it.optString("id") == uuid } ?: throw PlexError(404, "User not found")
        val serverToken: String = if (u.optBoolean("admin")) {
            prefs.plexServerToken ?: prefs.plexToken!!
        } else {
            runCatching { sharedTokens()[u.optString("id")] }.getOrNull() ?: run {
                if (u.optBoolean("protected") && pin == null) throw PlexError(403, "This user needs a PIN")
                val text = http("POST", tvUrl("/api/home/users/${u.optString("id")}/switch", mapOf("pin" to pin)), prefs.plexToken)
                val userToken = runCatching { JSONObject(text).let { it.str("authToken") ?: it.str("authenticationToken") } }.getOrNull()
                    ?: xmlElements(text, "user").firstOrNull()?.let { it["authenticationToken"] ?: it["authToken"] }
                    ?: throw PlexError(502, "Plex didn't return a token for this user")
                servers(userToken).firstOrNull { it.machineId == prefs.plexMachineId }?.accessToken
                    ?: throw PlexError(403, "${u.optString("title")} doesn't have access to this Plex server")
            }
        }
        prefs.plexUserToken = if (u.optBoolean("admin")) null else serverToken
        prefs.plexUserUuid = if (u.optBoolean("admin")) null else u.optString("uuid")
        prefs.plexUserRestricted = u.optBoolean("restricted")
        if (makeDefault) prefs.plexDefaultUser = u.optString("uuid")
        cache.clear()
        return u
    }

    /** Follow the default user again. */
    suspend fun resetUser() {
        val def = prefs.plexDefaultUser
        if (def == null) { prefs.plexUserToken = null; prefs.plexUserUuid = null; prefs.plexUserRestricted = false; cache.clear() }
        else runCatching { switchUser(def, null) }
    }

    // ------------------------------------------------------------------ playback
    private val NATIVE_CONTAINERS = setOf("mp4", "m4v", "mov", "webm", "mkv", "mpegts", "ts", "avi")
    private val NATIVE_VIDEO = setOf("h264", "hevc", "h265", "vp9", "av1", "vp8", "mpeg2video", "mpeg4")
    private val NATIVE_AUDIO = setOf("aac", "mp3", "opus", "vorbis", "flac", "ac3", "eac3", "dca", "dts", "truehd", "pcm", "alac")
    private val QUALITY = mapOf("20000" to (20000 to "1920x1080"), "12000" to (12000 to "1920x1080"), "8000" to (8000 to "1920x1080"),
        "4000" to (4000 to "1280x720"), "2000" to (2000 to "1280x720"), "720" to (1500 to "854x480"))
    private val LANG3 = mapOf("eng" to "en", "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "ita" to "it", "por" to "pt", "jpn" to "ja",
        "kor" to "ko", "chi" to "zh", "zho" to "zh", "rus" to "ru", "ara" to "ar", "hin" to "hi", "dut" to "nl", "nld" to "nl", "swe" to "sv", "nor" to "no", "nob" to "no",
        "dan" to "da", "fin" to "fi", "pol" to "pl", "tur" to "tr", "gre" to "el", "ell" to "el", "heb" to "he", "tha" to "th", "vie" to "vi", "ind" to "id", "ces" to "cs",
        "cze" to "cs", "hun" to "hu", "ukr" to "uk", "ron" to "ro", "rum" to "ro")
    private fun langOf(s: JSONObject): String {
        val t = s.optString("languageTag").take(2).lowercase()
        if (t.isNotEmpty()) return t
        val c = s.optString("languageCode").lowercase()
        return LANG3[c] ?: c.take(2)
    }

    /**
     * Decide how to play an item, like the Lumen server's /api/plex/play did: the original file when this device can
     * play it (hardware decoding), otherwise a Plex transcode. Returns the same JSON the server returned.
     */
    suspend fun play(body: JSONObject): JSONObject {
        val ratingKey = body.getString("ratingKey")
        val offset = body.optDouble("offset", 0.0)
        val prefsJ = body.optJSONObject("prefs") ?: JSONObject()
        var audioId: Int? = if (body.has("audioStreamID")) body.optInt("audioStreamID") else null
        var subId: Int? = if (body.has("subtitleStreamID")) body.optInt("subtitleStreamID") else null
        val quality = body.str("quality") ?: (if (isLocal) prefsJ.str("qualityLocal") else prefsJ.str("qualityRemote")) ?: "auto"
        val meta = get("/library/metadata/${Uri.encode(ratingKey)}", mapOf("includeMarkers" to 1, "includeChapters" to 1)).optJSONArray("Metadata").objects().firstOrNull()
            ?: throw PlexError(404, "Item not found")
        val medias = meta.optJSONArray("Media").objects()
        val media = medias.getOrNull(body.optInt("mediaIndex", 0)) ?: medias.firstOrNull() ?: throw PlexError(404, "No playable media")
        val part = media.optJSONArray("Part").objects().firstOrNull() ?: throw PlexError(404, "No playable media")
        val streams = part.optJSONArray("Stream").objects()

        // Preferred audio / subtitle languages (only when no track was picked by hand).
        run {
            val audios = streams.filter { it.optInt("streamType") == 2 }; val subs = streams.filter { it.optInt("streamType") == 3 }
            var audio = audios.firstOrNull { it.optBoolean("selected") } ?: audios.firstOrNull()
            val aLang = prefsJ.str("audioLang")
            if (audioId == null && aLang != null) audios.firstOrNull { langOf(it) == aLang }?.let { if (it !== audio) { audioId = it.optInt("id"); audio = it } }
            val mode = prefsJ.str("subtitleMode")
            if (subId == null && mode != null && mode != "plex") {
                val subLang = prefsJ.str("subtitleLang") ?: aLang ?: "en"
                val pick = subs.firstOrNull { langOf(it) == subLang && !it.optBoolean("forced") } ?: subs.firstOrNull { langOf(it) == subLang }
                val forced = subs.firstOrNull { it.optBoolean("forced") && langOf(it) == subLang }
                val cur = subs.firstOrNull { it.optBoolean("selected") }
                val want = when (mode) {
                    "always" -> pick
                    "foreign" -> if (audio != null && langOf(audio!!).isNotEmpty() && langOf(audio!!) != subLang) pick else forced
                    else -> null
                }
                if ((want?.optInt("id") ?: 0) != (cur?.optInt("id") ?: 0)) subId = want?.optInt("id") ?: 0
            }
        }
        // Remember the choice on Plex so its transcoder uses it too.
        if (audioId != null || subId != null) runCatching {
            send("PUT", "/library/parts/${part.optLong("id")}", buildMap {
                put("allParts", 1); audioId?.let { put("audioStreamID", it) }; subId?.let { put("subtitleStreamID", it) }
            })
        }
        val selAudio = streams.firstOrNull { it.optInt("streamType") == 2 && (if (audioId != null) it.optInt("id") == audioId else it.optBoolean("selected")) }
            ?: streams.firstOrNull { it.optInt("streamType") == 2 }
        val sId = subId ?: (streams.firstOrNull { it.optInt("streamType") == 3 && it.optBoolean("selected") }?.optInt("id") ?: 0)
        val selSub = streams.firstOrNull { it.optInt("streamType") == 3 && it.optInt("id") == sId }

        val direct = media.optString("container") in NATIVE_CONTAINERS && media.optString("videoCodec") in NATIVE_VIDEO &&
            (selAudio?.optString("codec") ?: media.optString("audioCodec")) in NATIVE_AUDIO
        val wantsOriginal = quality == "original" || quality == "auto"
        val duration = (if (meta.has("duration")) meta.optLong("duration") else media.optLong("duration")) / 1000.0

        val base = JSONObject().put("ratingKey", meta.optString("ratingKey")).put("title", meta.optString("title")).put("itemType", meta.optString("type"))
            .put("duration", duration).put("partId", part.optLong("id"))
            .put("markers", meta.optJSONArray("Marker") ?: JSONArray()).put("chapters", meta.optJSONArray("Chapter") ?: JSONArray())
            .put("media", JSONArray(medias.mapIndexed { i, m ->
                val res = m.optString("videoResolution")
                JSONObject().put("index", i).put("label", "${res}${if (res.all { it.isDigit() } && res.isNotEmpty()) "p" else ""} ${m.optString("videoCodec")} ${m.optString("container")}".trim()).put("bitrate", m.optInt("bitrate"))
            }))
            .put("audio", JSONArray(streams.filter { it.optInt("streamType") == 2 }.map { s -> JSONObject().put("id", s.optInt("id")).put("label", s.str("displayTitle") ?: s.str("language") ?: "Track ${s.optInt("index")}").put("selected", s.optInt("id") == selAudio?.optInt("id")) }))
            .put("subtitles", JSONArray(streams.filter { it.optInt("streamType") == 3 }.map { s -> JSONObject().put("id", s.optInt("id")).put("label", s.str("displayTitle") ?: s.str("language") ?: "Subtitle").put("selected", s.optInt("id") == selSub?.optInt("id")).put("key", s.str("key")) }))
            .put("quality", quality).put("sourceBitrate", media.optInt("bitrate")).put("network", if (isLocal) "local" else "remote")

        if (direct && wantsOriginal && !body.optBoolean("forceTranscode")) {
            // The original file, decoded by this device.
            val sub = selSub?.takeIf { it.str("key") != null }?.let { runCatching { subtitleFile(it.optInt("id"), it.str("key"), 0) }.getOrNull() }
            return base.put("method", "direct").put("type", "file").put("startOffset", 0).put("seekTo", offset)
                .put("url", url(part.optString("key"), mapOf("X-Plex-Client-Identifier" to clientId, "X-Plex-Product" to "Lumen")))
                .apply { if (sub != null) put("subtitleUrl", sub) }
        }

        // Plex transcodes (or only repackages, when "direct stream" is allowed).
        val (bitrate, res) = QUALITY[quality] ?: if (quality == "original") (40000 to "3840x2160") else QUALITY.getValue("20000")
        val session = UUID.randomUUID().toString()
        val params = linkedMapOf<String, Any?>(
            "path" to "/library/metadata/${meta.optString("ratingKey")}", "mediaIndex" to medias.indexOf(media), "partIndex" to 0, "protocol" to "hls", "fastSeek" to 1,
            "directPlay" to 0, "directStream" to if (wantsOriginal) 1 else 0, "directStreamAudio" to 1,
            "subtitleSize" to (prefsJ.optDouble("subtitleSize", 100.0)).toInt(), "audioBoost" to body.optInt("audioBoost", 100).coerceIn(100, 300),
            "location" to if (isLocal) "lan" else "wan", "offset" to offset.toLong(), "session" to session, "maxVideoBitrate" to bitrate, "videoBitrate" to bitrate,
            "videoQuality" to 100, "videoResolution" to res, "copyts" to 1, "mediaBufferSize" to 102400, "autoAdjustQuality" to 0, "hasMDE" to 1,
            "subtitles" to if (selSub != null) "burn" else "none",
            "X-Plex-Session-Identifier" to session, "X-Plex-Client-Identifier" to clientId, "X-Plex-Product" to "Lumen",
            "X-Plex-Platform" to "Chrome", "X-Plex-Device" to "Browser",
            "X-Plex-Client-Profile-Extra" to listOf(
                "add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts&videoCodec=h264&audioCodec=aac,mp3&replace=true)",
                "add-limitation(scope=videoCodec&scopeName=h264&type=upperBound&name=video.level&value=51)",
                "add-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=aac)",
            ).joinToString("+"),
        )
        // Ask Plex first, so a refusal shows a clear message.
        val decision = runCatching { JSONObject(http("GET", url("/video/:/transcode/universal/decision", params), token)).optJSONObject("MediaContainer") ?: JSONObject() }
            .getOrElse { throw PlexError(502, "Plex couldn't prepare this: ${it.message}") }
        val code = decision.optInt("generalDecisionCode")
        if (code >= 2000 || decision.optInt("transcodeDecisionCode") >= 2000) {
            throw PlexError(502, "Plex can't play this: " + (decision.str("transcodeDecisionText") ?: decision.str("generalDecisionText") ?: "decision $code"))
        }
        val streamDecision = decision.optJSONArray("Metadata").objects().firstOrNull()?.optJSONArray("Media").objects().firstOrNull()
            ?.optJSONArray("Part").objects().firstOrNull()?.optJSONArray("Stream").objects().firstOrNull()?.str("decision") ?: "transcode"
        return base.put("method", "plex-$streamDecision").put("type", "hls").put("session", session).put("startOffset", 0).put("seekTo", offset.toLong())
            .put("url", url("/video/:/transcode/universal/start.m3u8", params))
    }

    suspend fun stopTranscode(session: String) { runCatching { send("GET", "/video/:/transcode/universal/stop", mapOf("session" to session)) } }
    suspend fun pingTranscode(session: String) { runCatching { send("GET", "/video/:/transcode/universal/ping", mapOf("session" to session)) } }

    /**
     * A subtitle as a local WebVTT file (converted from SRT and shifted by [offsetMs]), for the player to load
     * alongside the video. Returns a file:// URI.
     */
    suspend fun subtitleFile(id: Int, key: String?, offsetMs: Int): String = withContext(Dispatchers.IO) {
        val path = key?.takeIf { it.startsWith("/library/streams/") } ?: "/library/streams/$id"
        var text = http("GET", url(path), token)
        if (!text.trimStart().startsWith("WEBVTT")) text = srtToVtt(text)
        if (offsetMs != 0) text = shiftVtt(text, offsetMs)
        val dir = File(ctx.cacheDir, "subs").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        val f = File(dir, "$id${if (offsetMs != 0) "_$offsetMs" else ""}.vtt")
        f.writeText(text)
        Uri.fromFile(f).toString()
    }

    private fun srtToVtt(srt: String): String =
        "WEBVTT\n\n" + srt.replace("\r", "").replace(Regex("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})"), "$1.$2").trim() + "\n"

    private fun shiftVtt(text: String, ms: Int): String {
        fun parse(t: String): Long {
            val p = t.split(':', '.'); return if (p.size == 4) ((p[0].toLong() * 60 + p[1].toLong()) * 60 + p[2].toLong()) * 1000 + p[3].toLong()
            else (p[0].toLong() * 60 + p[1].toLong()) * 1000 + p[2].toLong()
        }
        fun fmt(v: Long): String { val t = v.coerceAtLeast(0); return "%02d:%02d:%02d.%03d".format(t / 3600000, t / 60000 % 60, t / 1000 % 60, t % 1000) }
        return text.replace(Regex("((?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3})\\s*-->\\s*((?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3})")) { m ->
            "${fmt(parse(m.groupValues[1]) + ms)} --> ${fmt(parse(m.groupValues[2]) + ms)}"
        }
    }
}
