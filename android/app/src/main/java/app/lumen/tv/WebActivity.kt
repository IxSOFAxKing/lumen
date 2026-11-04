package app.lumen.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.lang.ref.WeakReference

/**
 * The full Lumen web interface, used on the TV only for advanced server settings (Plex sign-in, IPTV providers,
 * transcoding…). Laid out at 1280 px wide so it fits the screen and stays readable.
 */
class WebActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.init(this)
        val server = Net.server
        web = WebView(this).apply {
            setBackgroundColor(Ui.BG)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 100
            settings.userAgentString = settings.userAgentString + " LumenTV/" + BuildConfig.VERSION_NAME + " AndroidTV"
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val srv = Uri.parse(server)
                    return if (request.url.host == srv.host) false else { runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }; true }
                }
                override fun onPageFinished(view: WebView, url: String?) { view.requestFocus() }
            }
            addJavascriptInterface(Bridge(), "LumenNative")
        }
        setContentView(FrameLayout(this).apply { addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) })
        PlayerBus.web = WeakReference(web)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setCookie(server, "lumen_device=${Net.token}; Path=/; Max-Age=315360000")
        cm.flush()
        val path = intent.getStringExtra("path") ?: "/#/settings"
        web.loadUrl(server + path.replaceFirst("/#", "/?tv=1#"))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.lumenBack ? lumenBack() : false)") { res -> if (res != "true") finish() }
            }
        })
    }

    override fun onDestroy() {
        // Settings may have changed (Plex, providers, appearance): reload the native interface's data.
        Net.clearCache()
        AppState.loaded = false
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface fun play(json: String) { runOnUiThread { PlayerBus.play(this@WebActivity, json) } }
        @JavascriptInterface fun stop() { runOnUiThread { PlayerBus.stop() } }
        @JavascriptInterface fun isTV(): Boolean = true
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun serverUrl(): String = Net.server
        /** Width (CSS px) the page should lay itself out at. */
        @JavascriptInterface fun viewportWidth(): Int = 1280
        @JavascriptInterface fun openAppSettings() { runOnUiThread { startActivity(Intent(this@WebActivity, SetupActivity::class.java)) } }
        @JavascriptInterface fun unpaired() {
            runOnUiThread {
                Net.prefs.clearPairing()
                startActivity(Intent(this@WebActivity, SetupActivity::class.java).putExtra("server", Net.prefs.server))
                finish()
            }
        }
    }
}
