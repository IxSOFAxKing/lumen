package app.lumen.tv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Netflix-style profile picker for Plex Home users, shown when the app opens (unless a default user is set). */
@Composable
fun WhoIsWatching(pu: JSONObject, onExit: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val users = remember(pu) { pu.optJSONArray("users").objects() }
    val current = pu.optString("current")
    val frs = remember(users) { users.map { FocusRequester() } }
    var pinFor by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(80); runCatching { frs[users.indexOfFirst { it.optString("uuid") == current }.coerceAtLeast(0)].requestFocus() } }
    BackHandler { onExit() }

    fun pick(u: JSONObject, pin: String? = null) {
        busy = true
        scope.launch {
            runCatching { Net.post("/api/plex/users/switch", JSONObject().put("uuid", u.optString("uuid")).apply { if (pin != null) put("pin", pin) }) }
                .onSuccess { onDone() }
                .onFailure {
                    busy = false
                    if (it is Net.HttpError && it.code == 403 && pin == null) pinFor = u
                    else Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show()
                }
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            T("Who's watching?", 32.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(36.dp))
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                users.forEachIndexed { i, u ->
                    Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Focusable(onClick = { if (!busy) pick(u) }, focusRequester = frs[i], shape = CircleShape, focusedScale = 1.12f, modifier = Modifier.size(96.dp), background = C.card) {
                            val thumb = u.str("thumb")
                            if (thumb != null) Img(thumb, Modifier.fillMaxSize())
                            else T(u.optString("title").take(1).uppercase(), 36.sp, C.text2, FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                        }
                        Spacer(Modifier.height(10.dp))
                        T(u.optString("title"), 14.sp, weight = FontWeight.SemiBold)
                        T(if (u.optBoolean("restricted")) "Kids" else "", 11.sp, C.text3)
                    }
                }
            }
            Spacer(Modifier.height(34.dp))
            T(if (busy) "Switching…" else "Tip: set a default user in Settings to skip this screen.", 12.sp, C.text3)
        }
    }
    pinFor?.let { u -> PinDialog(u.optString("title"), onDismiss = { pinFor = null }) { pin -> pinFor = null; pick(u, pin) } }
}
