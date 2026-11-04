package app.lumen.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import app.lumen.tv.Ui.dp
import java.lang.ref.WeakReference

/**
 * Hosts the Lumen web interface (all browsing, guide, settings…) in a full-screen WebView.
 * Video is handed to the native player through the LumenNative JavaScript bridge.
 */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var errorView: View? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.isPaired) { openSetup(); finish(); return }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web = WebView(this).apply {
            setBackgroundColor(Ui.BG)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString + " LumenTV/" + BuildConfig.VERSION_NAME + " AndroidTV"
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showError(error.description?.toString() ?: "Can't reach the server")
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Stay inside Lumen; open anything else (e.g. trailers) externally
                    val server = Uri.parse(prefs.server)
                    return if (request.url.host == server.host) false else { runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }; true }
                }
                override fun onPageFinished(view: WebView, url: String?) { view.requestFocus() }
            }
            addJavascriptInterface(Bridge(), "LumenNative")
        }
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG); addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        setContentView(root)
        PlayerBus.web = WeakReference(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Let Lumen handle Back (close dialogs, go back a page); exit only when it has nowhere to go.
                web.evaluateJavascript("(window.lumenBack ? lumenBack() : false)") { res -> if (res != "true") moveTaskToBack(true) }
            }
        })
        loadLumen()
    }

    private fun loadLumen() {
        val server = prefs.server ?: return openSetup()
        // The device token authenticates the WebView (cookie) — the player sends it as a Bearer header.
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setCookie(server, "lumen_device=${prefs.token}; Path=/; Max-Age=315360000")
        cm.flush()
        errorView?.let { root.removeView(it) }; errorView = null
        web.loadUrl("$server/?tv=1")
        web.requestFocus()
    }

    private fun showError(msg: String) {
        if (errorView != null) return
        val box = Ui.vbox(this, Gravity.CENTER).apply { setBackgroundColor(Ui.BG); setPadding(dp(40), dp(40), dp(40), dp(40)) }
        box.addView(Ui.text(this, "Can't reach your Lumen server", 28f, bold = true))
        box.addView(Ui.gap(this, 10))
        box.addView(Ui.text(this, "${prefs.serverName ?: ""} ${prefs.server ?: ""}\n$msg", 17f, Ui.TEXT2).apply { gravity = Gravity.CENTER })
        box.addView(Ui.gap(this, 26))
        val row = Ui.hbox(this)
        val retry = Ui.button(this, "Retry", primary = true) { loadLumen() }
        row.addView(retry)
        row.addView(View(this), LinearLayout.LayoutParams(dp(14), 1))
        row.addView(Ui.button(this, "Change server") { openSetup() })
        box.addView(row)
        errorView = box
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        retry.requestFocus()
    }

    fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::web.isInitialized && prefs.isPaired) loadLumen() // returning from setup with a (new) server/token
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Media keys on the browse screens: let the web app see them as normal keys
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP && ::web.isInitialized) {
            web.evaluateJavascript("document.activeElement && document.activeElement.dispatchEvent(new MouseEvent('contextmenu',{bubbles:true}))", null)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (::web.isInitialized) { root.removeView(web); web.destroy() }
        super.onDestroy()
    }

    /** window.LumenNative — see docs/ANDROID_TV.md for the contract. */
    inner class Bridge {
        @JavascriptInterface fun play(json: String) { runOnUiThread { PlayerBus.play(this@MainActivity, json) } }
        @JavascriptInterface fun stop() { runOnUiThread { PlayerBus.stop() } }
        @JavascriptInterface fun isTV(): Boolean = true
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun serverUrl(): String = prefs.server ?: ""
        @JavascriptInterface fun openAppSettings() { runOnUiThread { openSetup() } }
        /** The server rejected our device token (removed in Settings → Devices): pair again. */
        @JavascriptInterface fun unpaired() {
            runOnUiThread {
                prefs.clearPairing()
                startActivity(Intent(this@MainActivity, SetupActivity::class.java).putExtra("server", prefs.server))
            }
        }
    }
}
