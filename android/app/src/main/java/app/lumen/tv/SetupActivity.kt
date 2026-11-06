package app.lumen.tv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.lumen.tv.Ui.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * First run:
 *  1. Sign in with Plex (a code to enter at plex.tv/link, or scan the QR code with a phone).
 *  2. Pick the Plex server (automatic when there's one).
 *  3. Look for a Lumen server on the network that belongs to that Plex server and sign in to it with the same
 *     Plex account (no codes, no passwords). Without one the app works with Plex alone.
 */
class SetupActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.init(this)
        prefs = Net.prefs
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(root)
        when {
            intent.getBooleanExtra("findLumen", false) && Plex.signedIn -> { prefs.lumenOptOut = false; findLumen(auto = false) }
            intent.getBooleanExtra("lumenAddress", false) && Plex.signedIn -> { prefs.lumenOptOut = false; noLumen(emptyList(), manualOnly = true) }
            !Plex.signedIn -> plexSignIn()
            !Plex.hasServer -> lifecycleScope.launch { chooseServer(prefs.plexToken!!) }
            else -> done()
        }
    }

    private fun setScreen(content: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(FrameLayout(this@SetupActivity).apply {
                addView(content, FrameLayout.LayoutParams(minOf(dp(820), resources.displayMetrics.widthPixels - dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            })
        }
        root.removeAllViews()
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun screen(title: String, sub: String, top: Int = 48): LinearLayout {
        val box = Ui.vbox(this).apply { setPadding(dp(24), dp(top), dp(24), dp(40)) }
        box.addView(Ui.text(this, "▶  Lumen", 30f, Ui.ACCENT, bold = true))
        box.addView(Ui.gap(this, 18))
        box.addView(Ui.text(this, title, 28f, bold = true).apply { gravity = Gravity.CENTER })
        box.addView(Ui.gap(this, 8))
        box.addView(Ui.text(this, sub, 17f, Ui.TEXT2).apply { gravity = Gravity.CENTER })
        box.addView(Ui.gap(this, 28))
        return box
    }

    private fun busy(title: String, sub: String) {
        val box = screen(title, sub, 90)
        box.addView(ProgressBar(this))
        setScreen(box)
    }

    // ---------------- 1. Sign in with Plex ----------------
    private fun plexSignIn() {
        job?.cancel()
        busy("Sign in with Plex", "Getting a code…")
        job = lifecycleScope.launch {
            val pin = try { Plex.createPin() } catch (e: Exception) { showError("Couldn't reach plex.tv", e.message ?: "Check this device's internet connection.") { plexSignIn() }; return@launch }
            // The QR code needs its own (long) PIN: Plex's sign-in page doesn't accept the short plex.tv/link code.
            val qrPin = runCatching { Plex.createPin(strong = true) }.getOrNull()
            val box = screen("Sign in with Plex", "On your phone or computer, go to plex.tv/link and enter this code, or scan the QR code.", 36)
            val row = Ui.hbox(this@SetupActivity)
            val codeBox = Ui.vbox(this@SetupActivity, Gravity.CENTER).apply {
                addView(Ui.text(this@SetupActivity, "plex.tv/link", 22f, Ui.TEXT2))
                addView(Ui.gap(this@SetupActivity, 6))
                addView(Ui.text(this@SetupActivity, pin.code, 64f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.18f })
            }
            row.addView(codeBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            qrPin?.let { qr(Plex.authUrl(it.code), dp(220)) }?.let { bmp ->
                row.addView(ImageView(this@SetupActivity).apply { setImageBitmap(bmp); setBackgroundColor(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10)) },
                    LinearLayout.LayoutParams(dp(240), dp(240)))
            }
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(Ui.gap(this@SetupActivity, 24))
            box.addView(Ui.text(this@SetupActivity, "Waiting for you to sign in…", 17f, Ui.TEXT2))
            box.addView(Ui.gap(this@SetupActivity, 18))
            box.addView(Ui.button(this@SetupActivity, "New code") { plexSignIn() }.also { it.requestFocus() })
            setScreen(box)
            val started = System.currentTimeMillis()
            while (isActive) {
                delay(2000)
                val token = runCatching { Plex.checkPin(pin.id) }.getOrNull() ?: qrPin?.let { q -> runCatching { Plex.checkPin(q.id) }.getOrNull() }
                if (token != null) { chooseServer(token); return@launch }
                if (System.currentTimeMillis() - started > 14 * 60_000) { plexSignIn(); return@launch } // codes expire
            }
        }
    }

    // ---------------- 2. Pick the Plex server ----------------
    private suspend fun chooseServer(token: String) {
        busy("Finding your Plex server…", "")
        val servers = try { Plex.servers(token) } catch (e: Exception) { showError("Couldn't list your Plex servers", e.message ?: "") { lifecycleScope.launch { chooseServer(token) } }; return }
        prefs.plexToken = token
        when {
            servers.isEmpty() -> showError("No Plex servers", "This Plex account has no servers and none are shared with it.") { Plex.signOut(); plexSignIn() }
            servers.size == 1 -> useServer(servers[0], token)
            else -> {
                val box = screen("Choose your Plex server", "Your account can use more than one.")
                servers.sortedByDescending { it.owned }.forEachIndexed { i, s ->
                    val b = Ui.button(this, "${s.name}${if (s.owned) "" else "   ·   shared with you"}", primary = i == 0) { lifecycleScope.launch { useServer(s, token) } }
                    box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
                    if (i == 0) b.requestFocus()
                }
                setScreen(box)
            }
        }
    }

    private suspend fun useServer(s: Plex.Server, token: String) {
        busy("Connecting to ${s.name}…", "")
        val conn = Plex.pickConnection(s)
        Plex.choose(s, conn, token)
        // Already connected to a Lumen server (e.g. paired before this update)? Keep using it.
        val tok = prefs.token
        val srv = prefs.server
        if (tok != null && srv != null && runCatching { Api.get("$srv/api/auth/status", tok).optBoolean("authed") }.getOrDefault(false)) { done(); return }
        findLumen(auto = true)
    }

    // ---------------- 3. The Lumen server (optional) ----------------
    private fun findLumen(auto: Boolean) {
        job?.cancel()
        busy("Looking for your Lumen server…", "Lumen adds Live TV, Discover and settings that follow you between devices.")
        job = lifecycleScope.launch {
            val mid = prefs.plexMachineId
            val candidates = buildList {
                prefs.server?.let { s -> Discovery.probe(s, 1500)?.let { add(it) } }
                addAll(Discovery.scan())
            }.distinctBy { it.url }
            val mine = candidates.filter { it.plexMachineId != null && it.plexMachineId == mid }
            when {
                mine.size == 1 -> signInToLumen(mine[0].url, mine[0].name, auto)
                mine.size > 1 -> chooseLumen(mine)
                else -> noLumen(candidates)
            }
        }
    }

    private fun chooseLumen(list: List<Discovery.Server>) {
        val box = screen("Choose your Lumen server", "")
        list.forEachIndexed { i, s ->
            val b = Ui.button(this, "${s.name}   ·   ${s.url.removePrefix("http://")}", primary = i == 0) { lifecycleScope.launch { signInToLumen(s.url, s.name, false) } }
            box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            if (i == 0) b.requestFocus()
        }
        box.addView(Ui.button(this, "Continue without Lumen") { prefs.forgetServer(); done() })
        setScreen(box)
    }

    private fun noLumen(others: List<Discovery.Server>, manualOnly: Boolean = false) {
        val box = if (manualOnly) screen("Connect to a Lumen server", "Enter its address, or search your network.")
            else screen("No Lumen server found", "That's fine: Plex works on its own. Live TV, Discover and shared settings need a Lumen server; you can connect one later in Settings.")
        val go = Ui.button(this, if (manualOnly) "Cancel" else "Continue with Plex", primary = !manualOnly) { if (!manualOnly) prefs.forgetServer(); done() }
        box.addView(go)
        box.addView(Ui.gap(this, 14))
        others.forEach { s ->
            box.addView(Ui.button(this, "Use ${s.name}   ·   ${s.url.removePrefix("http://")}") { lifecycleScope.launch { signInToLumen(s.url, s.name, false) } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        box.addView(Ui.gap(this, 10))
        box.addView(Ui.text(this, "Or enter its address (e.g. 192.168.1.50:8080)", 16f, Ui.TEXT2))
        box.addView(Ui.gap(this, 10))
        val input = EditText(this).apply {
            setText(prefs.server ?: "")
            hint = "192.168.1.50:8080"
            setHintTextColor(0xFF6D7588.toInt()); setTextColor(Ui.TEXT); textSize = 20f; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, _, _ -> manual(v.text.toString()); true }
        }
        val row = Ui.hbox(this).apply {
            addView(input, LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Ui.gap(this@SetupActivity, 0).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
            addView(Ui.button(this@SetupActivity, "Connect") { manual(input.text.toString()) })
            addView(Ui.gap(this@SetupActivity, 0).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
            addView(Ui.button(this@SetupActivity, "Search again") { findLumen(auto = false) })
        }
        box.addView(row)
        setScreen(box)
        go.requestFocus()
    }

    private fun manual(raw: String) {
        if (raw.isBlank()) return
        val url = Api.normalise(raw)
        lifecycleScope.launch {
            busy("Connecting…", url)
            val h = runCatching { Api.get("$url/api/health") }.getOrNull()
            if (h?.optString("app") != "lumen") { showError("That isn't a Lumen server", url) { findLumen(auto = false) }; return@launch }
            signInToLumen(url, h.optString("name", url), false)
        }
    }

    /** Sign in to Lumen with the Plex account: the server checks it has access to its Plex server. */
    private suspend fun signInToLumen(url: String, name: String, auto: Boolean) {
        busy("Signing in to $name…", url.removePrefix("http://"))
        try {
            LumenLink.signIn(url, name)
            done()
        } catch (e: Exception) {
            val box = screen("Couldn't sign in to $name", e.message ?: "Network error")
            val go = Ui.button(this, "Continue with Plex only", primary = true) { prefs.forgetServer(); done() }
            box.addView(go)
            box.addView(Ui.gap(this, 12))
            box.addView(Ui.button(this, "Try again") { lifecycleScope.launch { signInToLumen(url, name, auto) } })
            setScreen(box)
            go.requestFocus()
        }
    }

    private fun showError(title: String, detail: String, retry: () -> Unit) {
        val box = screen(title, detail, 90)
        val b = Ui.button(this, "Try again", primary = true) { retry() }
        box.addView(b)
        setScreen(box)
        b.requestFocus()
    }

    private fun done() {
        AppState.loaded = false
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun qr(text: String, size: Int): Bitmap? = try {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { b ->
            for (x in 0 until size) for (y in 0 until size) b.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        }
    } catch (e: Exception) { null }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !(Plex.signedIn && Plex.hasServer)) { finishAffinity(); return true }
        return super.onKeyDown(keyCode, event)
    }
}

/** Finding the Lumen server that belongs to this Plex server, and signing in to it with the Plex account. */
object LumenLink {
    /** Looked for a Lumen server in the background since the app started. */
    var tried = false

    suspend fun signIn(url: String, name: String) {
        val p = Net.prefs
        val r = Api.post("$url/api/auth/plex/token", JSONObject().put("token", p.plexToken).put("name", (Build.MODEL ?: "Android").trim())
            .put("platform", if (AppState.isTv) "android-tv" else "android-phone"), timeoutMs = 20_000)
        p.server = url; p.serverName = name; p.token = r.getString("token"); p.lumenOptOut = false
        Net.clearCache()
    }

    /** Quietly connect when exactly one Lumen server for our Plex server is on the network. */
    suspend fun autoConnect(): Boolean {
        val p = Net.prefs
        if (p.isPaired || p.lumenOptOut || !Plex.signedIn) return false
        val mid = p.plexMachineId ?: return false
        val mine = Discovery.scan().filter { it.plexMachineId == mid }
        if (mine.size != 1) return false
        return runCatching { signIn(mine[0].url, mine[0].name) }.isSuccess
    }
}
