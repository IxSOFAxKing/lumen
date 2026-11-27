package app.lumen.tv

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Plex watchlist helpers for the details pages and the home row. */
object Watchlist {
    /** The title that goes on the watchlist: a movie or show (for a season or episode, its show). */
    fun guidOf(item: PlexItem): String? = when (item.type) {
        "episode" -> item.raw.str("grandparentGuid")
        "season" -> item.raw.str("parentGuid")
        else -> item.raw.str("guid")
    }

    /** A watchlist title that isn't in the library: find it in Discover (to request it), if Discover is set up. */
    fun openMissing(item: PlexItem) {
        AppScope.launch {
            val ctx = Nav.appContext
            if (!AppState.discoverOn) { Toast.makeText(ctx, "${item.title} isn't in your Plex library yet", Toast.LENGTH_SHORT).show(); return@launch }
            val wantType = if (item.type == "show") "tv" else "movie"
            val hits = runCatching { Discover.search(item.title) }.getOrDefault(emptyList())
            val hit = hits.firstOrNull { it.type == wantType && it.title.equals(item.title, true) && (item.year == null || it.year == item.year) }
                ?: hits.firstOrNull { it.type == wantType && it.title.equals(item.title, true) } ?: hits.firstOrNull { it.type == wantType }
            if (hit != null) Nav.push(Screen.DiscoverItem(hit.type, hit.id, hit.title))
            else Toast.makeText(ctx, "${item.title} isn't in your Plex library yet", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggle(item: PlexItem, state: MutableState<Boolean?>, scope: CoroutineScope, ctx: Context) {
        val on = state.value ?: return
        scope.launch {
            runCatching { Plex.setWatchlisted(guidOf(item), !on) }
                .onSuccess {
                    state.value = !on
                    Toast.makeText(ctx, if (!on) "Added to your Plex watchlist" else "Removed from your Plex watchlist", Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(ctx, it.message ?: "Couldn't change the watchlist", Toast.LENGTH_LONG).show() }
        }
    }
}

/** Whether this title is on the watchlist; null while unknown or when it can't be watchlisted (button hidden). */
@Composable
fun rememberWatchlisted(item: PlexItem): MutableState<Boolean?> {
    val guid = Watchlist.guidOf(item)
    val state = remember(guid) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(guid) { state.value = runCatching { Plex.watchlisted(guid) }.getOrNull() }
    return state
}
