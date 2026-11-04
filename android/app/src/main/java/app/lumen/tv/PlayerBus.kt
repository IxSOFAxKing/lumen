package app.lumen.tv

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Glue between the Lumen web UI (in WebActivity's WebView) and the native player (PlayerActivity).
 * Web → native: LumenNative.play(json) → PlayerBus.play()
 * Native → web: window.lumenNative.progress / ended / closed / zap / error (see docs/ANDROID_TV.md)
 */
object PlayerBus {
    var web: WeakReference<WebView>? = null
    var player: WeakReference<PlayerActivity>? = null

    fun play(ctx: Context, json: String) {
        val p = player?.get()
        if (p != null && !p.isFinishing && !p.isDestroyed) p.loadWeb(json)
        else ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("json", json))
    }

    fun stop() { player?.get()?.finish() }

    private fun js(code: String) {
        val w = web?.get() ?: return
        w.post { w.evaluateJavascript("try{$code}catch(e){}", null) }
    }

    fun progress(posSec: Double, durSec: Double, state: String) = js("window.lumenNative&&lumenNative.progress($posSec,$durSec,${JSONObject.quote(state)})")
    fun ended() = js("window.lumenNative&&lumenNative.ended()")
    fun closed(posSec: Double) = js("window.lumenNative&&lumenNative.closed($posSec)")
    fun zap(dir: Int) = js("window.lumenNative&&lumenNative.zap($dir)")
    fun error(msg: String) = js("window.lumenNative&&lumenNative.error(${JSONObject.quote(msg)})")
}
