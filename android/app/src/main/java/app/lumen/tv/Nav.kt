package app.lumen.tv

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/** Screens of the native interface. */
sealed class Screen(val key: String, val topLevel: Boolean) {
    data object Home : Screen("home", true)
    data object Search : Screen("search", true)
    data object Live : Screen("live", true)
    data object Guide : Screen("guide", true)
    data object Recordings : Screen("dvr", true)
    data object Settings : Screen("settings", true)
    data class Lib(val lib: Library) : Screen("lib:${lib.key}", true)
    data class Detail(val ratingKey: String, val seasonKey: String? = null) : Screen("detail:$ratingKey", false)
    /** A grid of Plex items: a library A–Z, a collection, a playlist or a genre. */
    data class Grid(val title: String, val source: GridSource) : Screen("grid:${source.id}", false)
}

sealed class GridSource(val id: String) {
    data class Library(val lib: String, val type: String, val sort: String = "titleSort", val unwatched: Boolean = false) : GridSource("lib:$lib:$sort:$unwatched")
    data class Collection(val key: String) : GridSource("col:$key")
    data class Playlist(val key: String) : GridSource("pl:$key")
    data class Genre(val lib: String, val genre: String) : GridSource("genre:$lib:$genre")
}

object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    /** Remembered focus/data per screen, so going back lands where you were. */
    val memory = HashMap<String, ScreenMemory>()
    lateinit var appContext: Context

    val current: Screen get() = stack.last()

    fun mem(s: Screen): ScreenMemory = memory.getOrPut(s.key) { ScreenMemory() }

    fun push(s: Screen) {
        if (current == s) return
        memory.remove(s.key)
        stack.add(s)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        val gone = stack.removeAt(stack.lastIndex)
        memory.remove(gone.key)
        mem(current).restorePending = true
        return true
    }

    /** Switch section from the side menu. */
    fun root(s: Screen) {
        if (stack.size == 1 && current == s) return
        stack.forEach { memory.remove(it.key) }
        stack.clear()
        stack.add(s)
    }

    fun openDetails(item: PlexItem) {
        when (item.type) {
            "episode" -> item.grandparentRatingKey?.let { push(Screen.Detail(it, item.parentRatingKey)) } ?: push(Screen.Detail(item.ratingKey))
            "season" -> item.parentRatingKey?.let { push(Screen.Detail(it, item.ratingKey)) } ?: push(Screen.Detail(item.ratingKey))
            "collection" -> push(Screen.Grid(item.title, GridSource.Collection(item.ratingKey)))
            "playlist" -> push(Screen.Grid(item.title, GridSource.Playlist(item.ratingKey)))
            else -> push(Screen.Detail(item.ratingKey))
        }
    }

    /** OK on a card: episodes and in-progress items play straight away (like Netflix); everything else opens details. */
    fun open(item: PlexItem) {
        if (item.type == "episode" || (item.type == "movie" && item.viewOffsetMs > 0)) play(item, resume = true)
        else openDetails(item)
    }

    // ---------------- playback ----------------
    fun play(item: PlexItem, resume: Boolean, queue: List<PlexItem>? = null) {
        val req = JSONObject().put("kind", "plex").put("ratingKey", item.ratingKey).put("offset", if (resume) item.resumeSec else 0.0)
        if (queue != null) req.put("queue", JSONArray(queue.map { it.ratingKey }))
        startPlayer(req)
    }

    fun playLive(ch: Channel, list: List<Channel>, groupName: String) {
        Playback.zapList = list.ifEmpty { listOf(ch) }
        Playback.zapTitle = groupName
        Net.prefs.lastChannel = "${ch.providerId}:${ch.id}"
        startPlayer(JSONObject().put("kind", "live").put("providerId", ch.providerId).put("channelId", ch.id))
    }

    fun playCatchup(ch: Channel, p: Programme) {
        startPlayer(JSONObject().put("kind", "catchup").put("providerId", ch.providerId).put("channelId", ch.id)
            .put("start", p.start).put("stop", p.stop).put("title", p.title).put("channelName", ch.name))
    }

    fun playRecording(id: Int, title: String, sub: String) {
        startPlayer(JSONObject().put("kind", "recording").put("id", id).put("title", title).put("subtitle", sub))
    }

    private fun startPlayer(req: JSONObject) {
        appContext.startActivity(Intent(appContext, PlayerActivity::class.java).putExtra("req", req.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
