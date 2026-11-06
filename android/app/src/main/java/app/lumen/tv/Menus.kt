package app.lumen.tv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MenuSpec(val title: String, val subtitle: String?, val items: List<Pair<String, () -> Unit>>)

/** Long-press (or MENU) options for any card, shown as a small dialog. */
object Menus {
    var current by mutableStateOf<MenuSpec?>(null)

    private fun toast(msg: String?) { Toast.makeText(Nav.appContext, msg ?: "Something went wrong", Toast.LENGTH_SHORT).show() }
    private fun refresh() { Net.clearCache(); AppState.resumeTick++ }

    fun item(m: PlexItem, inContinue: Boolean = false) {
        val l = mutableListOf<Pair<String, () -> Unit>>()
        when (m.type) {
            "movie", "episode", "clip" -> {
                if (m.viewOffsetMs > 0) {
                    l += "Resume from ${fmtClock(m.resumeSec)}" to { Nav.play(m, resume = true) }
                    l += "Play from the beginning" to { Nav.play(m, resume = false) }
                } else l += "Play" to { Nav.play(m, resume = false) }
                l += "More info" to { Nav.openDetails(m) }
                if (m.isEpisode && m.grandparentRatingKey != null) l += "Go to ${m.grandparentTitle ?: "show"}" to { Nav.push(Screen.Detail(m.grandparentRatingKey, m.parentRatingKey)) }
            }
            "show", "season" -> l += "Open" to { Nav.openDetails(m) }
            "playlist" -> {
                l += "Open" to { Nav.openDetails(m) }
                l += "Play all" to { AppScope.launch { runCatching { Repo.playlist(m.ratingKey) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { Nav.play(it.first(), false, it) } } }
                l += "Shuffle" to { AppScope.launch { runCatching { Repo.playlist(m.ratingKey) }.getOrNull()?.shuffled()?.takeIf { it.isNotEmpty() }?.let { Nav.play(it.first(), false, it) } } }
            }
            else -> l += "Open" to { Nav.openDetails(m) }
        }
        if (m.type in listOf("movie", "episode", "show", "season")) {
            l += (if (m.watched) "Mark as unwatched" else "Mark as watched") to {
                AppScope.launch { runCatching { Repo.setWatched(m.ratingKey, !m.watched) }.onSuccess { refresh(); toast(if (m.watched) "Marked as unwatched" else "Marked as watched") }.onFailure { toast(it.message) } }
            }
        }
        if (inContinue || m.viewOffsetMs > 0) l += "Remove from Continue Watching" to {
            AppScope.launch { runCatching { Plex.removeFromContinue(m.ratingKey) }.onSuccess { refresh(); toast("Removed from Continue Watching") } }
        }
        val title = if (m.isEpisode) m.grandparentTitle ?: m.title else m.title
        val sub = if (m.isEpisode) listOfNotNull(m.episodeLabel, m.title).joinToString(" · ") else m.metaLine()
        current = MenuSpec(title, sub, l)
    }

    fun channel(ch: Channel, list: List<Channel>, group: String) {
        val l = mutableListOf<Pair<String, () -> Unit>>()
        l += "Watch" to { Nav.playLive(ch, list, group) }
        l += (if (ch.isFav) "Remove from favourites" else "Add to favourites") to {
            AppScope.launch { runCatching { Repo.toggleFavorite(ch) }.onSuccess { added -> refresh(); toast(if (added) "Added ${ch.name} to favourites" else "Removed ${ch.name} from favourites") }.onFailure { toast(it.message) } }
        }
        ch.onAir?.let { p -> l += "Record \"${p.title}\"" to { AppScope.launch { runCatching { Repo.record(ch, p) }.onSuccess { toast(it) }.onFailure { toast(it.message) } } } }
        if (ch.archive) ch.onAir?.let { p -> l += "Watch from start" to { Nav.playCatchup(ch, p) } }
        if (AppState.isTv) l += "Open the Guide" to { Nav.root(Screen.Guide) }
        if (AppState.isTv) l += "Add to Multi-View" to { MultiView.add(ch); Nav.root(Screen.MultiView) }
        current = MenuSpec(listOfNotNull(ch.num?.toString(), ch.name).joinToString("  "), ch.onAir?.let { "${fmtTime(it.start)} – ${fmtTime(it.stop)} · ${it.title}" }, l)
    }
}

@Composable
fun MenuHost() {
    val m = Menus.current ?: return
    androidx.compose.ui.window.Dialog(onDismissRequest = { Menus.current = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember(m) { FocusRequester() }
        LaunchedEffect(m) { delay(60); runCatching { first.requestFocus() } }
        // The menu usually opens while OK is still held: ignore OK until it has been released and pressed again.
        var armed by remember(m) { mutableStateOf(false) }
        Column(Modifier.width(380.dp).clip(RoundedCornerShape(14.dp)).background(C.bg2).padding(18.dp).onPreviewKeyEvent { ev ->
            val c = ev.nativeKeyEvent.keyCode
            val ok = c == android.view.KeyEvent.KEYCODE_DPAD_CENTER || c == android.view.KeyEvent.KEYCODE_ENTER || c == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!ok) false
            else if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) { armed = true; false }
            else !armed
        }) {
            T(m.title, 18.sp, weight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(start = 6.dp))
            m.subtitle?.takeIf { it.isNotBlank() }?.let { T(it, 12.sp, C.text2, maxLines = 2, modifier = Modifier.padding(start = 6.dp, top = 2.dp)) }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp))
            m.items.forEachIndexed { i, (label, action) ->
                Focusable(
                    onClick = { Menus.current = null; action() }, focusRequester = if (i == 0) first else null, shape = RoundedCornerShape(8.dp), focusedScale = 1f, outline = false,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), background = Color.Transparent, focusedBackground = Color.White,
                ) { f ->
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(label, 14.sp, if (f) Color.Black else C.text, FontWeight.Medium)
                    }
                }
            }
        }
    }
}
