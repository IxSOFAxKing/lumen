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
 * First-run setup: find the Lumen server on the network (or type its address), then pair this TV.
 * Pairing shows a 6-digit code + QR code; the user approves it from Lumen on a phone/computer
 * (Settings → Devices). The TV then receives its own device token.
 */
class SetupActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(root)
        val preset = intent.getStringExtra("server")
        if (preset != null) connect(preset) else showSearch()
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

    private fun header(box: LinearLayout, title: String, sub: String) {
        box.addView(Ui.text(this, "▶  Lumen", 30f, Ui.ACCENT, bold = true))
        box.addView(Ui.gap(this, 18))
        box.addView(Ui.text(this, title, 28f, bold = true))
        box.addView(Ui.gap(this, 8))
        box.addView(Ui.text(this, sub, 17f, Ui.TEXT2).apply { gravity = Gravity.CENTER })
        box.addView(Ui.gap(this, 28))
    }

    // ---------------- Step 1: find the server ----------------
    private fun showSearch() {
        job?.cancel()
        val box = Ui.vbox(this).apply { setPadding(dp(24), dp(48), dp(24), dp(48)) }
        header(box, "Connect to your Lumen server", "Looking for Lumen on your network…")
        val results = Ui.vbox(this)
        val progress = ProgressBar(this)
        results.addView(progress)
        box.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(Ui.gap(this, 30))
        box.addView(Ui.text(this, "Or enter the address (e.g. 192.168.1.50:8080)", 16f, Ui.TEXT2))
        box.addView(Ui.gap(this, 10))
        val input = EditText(this).apply {
            setText(prefs.server ?: "")
            hint = "192.168.1.50:8080"
            setHintTextColor(0xFF6D7588.toInt())
            setTextColor(Ui.TEXT)
            textSize = 20f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, _, _ -> connect(v.text.toString()); true }
        }
        val row = Ui.hbox(this).apply {
            addView(input, LinearLayout.LayoutParams(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Ui.gap(this@SetupActivity, 0).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
            addView(Ui.button(this@SetupActivity, "Connect", primary = true) { connect(input.text.toString()) })
            addView(Ui.gap(this@SetupActivity, 0).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
            addView(Ui.button(this@SetupActivity, "Search again") { showSearch() })
        }
        box.addView(row)
        setScreen(box)

        job = lifecycleScope.launch {
            val found = Discovery.scan()
            results.removeAllViews()
            if (found.isEmpty()) {
                results.addView(Ui.text(this@SetupActivity,
                    if (Discovery.localIpv4() == null) "This TV doesn't seem to be on a local network." else "No Lumen server found automatically. Enter its address below.",
                    17f, Ui.TEXT2))
                input.requestFocus()
            } else {
                found.forEachIndexed { i, s ->
                    val b = Ui.button(this@SetupActivity, "${s.name}   ·   ${s.url.removePrefix("http://")}", primary = i == 0) { connect(s.url) }
                    results.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
                    if (i == 0) b.requestFocus()
                }
            }
        }
    }

    // ---------------- Step 2: check the server and pair ----------------
    private fun connect(raw: String) {
        if (raw.isBlank()) return
        val url = Api.normalise(raw)
        job?.cancel()
        val box = Ui.vbox(this).apply { setPadding(dp(24), dp(80), dp(24), dp(48)) }
        header(box, "Connecting…", url)
        box.addView(ProgressBar(this))
        setScreen(box)
        job = lifecycleScope.launch {
            try {
                val health = Api.get("$url/api/health")
                if (health.optString("app") != "lumen") throw Exception("That address isn't a Lumen server.")
                prefs.server = url
                prefs.serverName = health.optString("name", url)
                // Already paired with this server and the token still works? Go straight in.
                val tok = prefs.token
                if (tok != null && runCatching { Api.get("$url/api/auth/status", tok).optBoolean("authed") }.getOrDefault(false)) { done(); return@launch }
                val model = (Build.MODEL ?: "Android TV").trim()
                val start = Api.post("$url/api/pair/start", JSONObject().put("name", model).put("platform", "android-tv"))
                showPairing(url, start.getString("pairId"), start.getString("code"), start.optString("status") == "approved")
            } catch (e: Exception) {
                showError("Couldn't connect to $url", e.message ?: "Network error")
            }
        }
    }

    private fun showPairing(url: String, pairId: String, code: String, autoApproved: Boolean) {
        val box = Ui.vbox(this).apply { setPadding(dp(24), dp(40), dp(24), dp(40)) }
        if (!autoApproved) {
            header(box, "Pair this TV", "On your phone or computer, open Lumen → Settings → Devices and enter this code, or scan the QR code.")
            val row = Ui.hbox(this)
            val codeBox = Ui.vbox(this, Gravity.CENTER).apply {
                addView(Ui.text(this@SetupActivity, code.chunked(3).joinToString(" "), 64f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.12f })
                addView(Ui.gap(this@SetupActivity, 8))
                addView(Ui.text(this@SetupActivity, "Code expires in 10 minutes", 15f, Ui.TEXT2))
            }
            row.addView(codeBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            qr("$url/#/pair?code=$code", dp(220))?.let { bmp ->
                row.addView(ImageView(this).apply { setImageBitmap(bmp); setBackgroundColor(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10)) },
                    LinearLayout.LayoutParams(dp(240), dp(240)))
            }
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(Ui.gap(this, 26))
            box.addView(Ui.text(this, "Waiting for approval…", 17f, Ui.TEXT2))
            box.addView(Ui.gap(this, 20))
            box.addView(Ui.button(this, "Use a different server") { prefs.server = null; showSearch() }.also { it.requestFocus() })
            setScreen(box)
        }
        job = lifecycleScope.launch {
            while (isActive) {
                val st = try { Api.get("$url/api/pair/status/$pairId") } catch (e: Exception) { null }
                when (st?.optString("status")) {
                    "approved" -> { prefs.token = st.getString("token"); done(); return@launch }
                    "expired" -> { showError("The pairing code expired", "Start again to get a new code."); return@launch }
                }
                delay(2000)
            }
        }
    }

    private fun showError(title: String, detail: String) {
        val box = Ui.vbox(this).apply { setPadding(dp(24), dp(80), dp(24), dp(48)) }
        header(box, title, detail)
        val row = Ui.hbox(this)
        val retry = Ui.button(this, "Try again", primary = true) { prefs.server?.let { connect(it) } ?: showSearch() }
        row.addView(retry)
        row.addView(Ui.gap(this, 0).apply { layoutParams = LinearLayout.LayoutParams(dp(14), 1) })
        row.addView(Ui.button(this, "Choose server") { showSearch() })
        box.addView(row)
        setScreen(box)
        retry.requestFocus()
    }

    private fun done() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun qr(text: String, size: Int): Bitmap? = try {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { b ->
            for (x in 0 until size) for (y in 0 until size) b.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        }
    } catch (e: Exception) { null }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !Prefs(this).isPaired) { finishAffinity(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
