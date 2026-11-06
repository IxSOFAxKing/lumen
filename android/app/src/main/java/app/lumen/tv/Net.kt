package app.lumen.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Authenticated client for the Lumen server (device token as a Bearer header).
 * Everything the TV shows comes from the server, so settings, logins and favourites are shared with every app.
 */
object Net {
    class HttpError(val code: Int, message: String) : IOException(message)

    lateinit var prefs: Prefs
        private set
    /** Called when the server no longer accepts our device token (removed in Settings → Devices). */
    var onUnauthorized: (() -> Unit)? = null

    val server: String get() = prefs.server ?: ""
    val token: String get() = prefs.token ?: ""

    lateinit var http: OkHttpClient
        private set

    fun init(ctx: Context) {
        if (::http.isInitialized) return
        prefs = Prefs(ctx)
        Plex.init(ctx)
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val srv = Uri.parse(server)
                // Only our own server gets the device token.
                val b = req.newBuilder()
                if (req.url.host == srv.host && token.isNotEmpty()) b.header("Authorization", "Bearer $token")
                chain.proceed(b.build())
            }
            .build()
    }

    /** Absolute URL for a server path (or an already absolute URL). */
    fun url(path: String?): String? {
        if (path.isNullOrBlank() || path == "null") return null
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("file:") || path.startsWith("content:")) return path
        return server + (if (path.startsWith("/")) path else "/$path")
    }

    /** Plex artwork, resized by the Plex server itself. */
    fun plexImg(path: String?, w: Int, h: Int): String? = Plex.img(path, w, h)

    private val JSON = "application/json".toMediaType()
    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()

    fun clearCache() = cache.clear()

    private suspend fun call(method: String, path: String, body: Any?): Any? = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url(path)!!).header("Accept", "application/json")
        val rb = if (body != null) body.toString().toRequestBody(JSON) else if (method != "GET") "{}".toRequestBody(JSON) else null
        b.method(method, if (method == "GET") null else rb)
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val parsed: Any? = try {
                val t = text.trimStart()
                when {
                    t.startsWith("{") -> JSONObject(t)
                    t.startsWith("[") -> JSONArray(t)
                    else -> null
                }
            } catch (e: Exception) { null }
            if (res.code == 401 && (parsed as? JSONObject)?.optBoolean("lumenAuth") == true) {
                onUnauthorized?.invoke()
            }
            if (!res.isSuccessful) throw HttpError(res.code, (parsed as? JSONObject)?.optString("error")?.takeIf { it.isNotBlank() } ?: "Request failed (${res.code})")
            parsed
        }
    }

    suspend fun obj(path: String, ttlMs: Long = 0): JSONObject {
        if (ttlMs > 0) cache[path]?.let { (t, v) -> if (System.currentTimeMillis() - t < ttlMs && v is JSONObject) return v }
        val v = call("GET", path, null) as? JSONObject ?: JSONObject()
        if (ttlMs > 0) cache[path] = System.currentTimeMillis() to v
        return v
    }

    suspend fun arr(path: String, ttlMs: Long = 0): JSONArray {
        if (ttlMs > 0) cache[path]?.let { (t, v) -> if (System.currentTimeMillis() - t < ttlMs && v is JSONArray) return v }
        val v = call("GET", path, null) as? JSONArray ?: JSONArray()
        if (ttlMs > 0) cache[path] = System.currentTimeMillis() to v
        return v
    }

    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject {
        if (!HEARTBEAT.containsMatchIn(path)) cache.clear()
        return call("POST", path, body) as? JSONObject ?: JSONObject()
    }

    suspend fun put(path: String, body: JSONObject): JSONObject {
        cache.clear()
        return call("PUT", path, body) as? JSONObject ?: JSONObject()
    }

    suspend fun delete(path: String): JSONObject {
        cache.clear()
        return call("DELETE", path, null) as? JSONObject ?: JSONObject()
    }

    private val HEARTBEAT = Regex("/(ping|timeline|progress|play)(/|$|\\?)")
}

// ---------- small JSON helpers ----------
fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> opt(i)?.toString() }
}

/** optString that treats JSON null as missing. */
fun JSONObject.str(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotEmpty() }
}

fun JSONObject.num(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val v = opt(name)
    return when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
