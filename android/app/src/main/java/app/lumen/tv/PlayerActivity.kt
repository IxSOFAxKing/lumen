package app.lumen.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.lumen.tv.Ui.dp
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Native player (Media3 / ExoPlayer): hardware decoding of HEVC, Dolby Digital/Plus, DTS passthrough,
 * MKV, MPEG-TS and HLS. Playback items come from the web UI through PlayerBus.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var skipBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var item = JSONObject()
    private var lastReport = 0L
    private var closedReported = false
    private val closeOnError = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = Prefs(this)

        // Every request carries the device token (server may be password-protected).
        val token = prefs.token ?: ""
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("LumenTV/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(90_000) // Plex/Lumen transcoders can take a while for the first segment
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token", "Cookie" to "lumen_device=$token"))
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    report("stopped")
                    PlayerBus.ended()
                    // With a next episode the web app calls play() again; otherwise close.
                    if (!item.optBoolean("hasNext")) finish()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { report(if (isPlaying) "playing" else "paused") }
            override fun onPlayerError(error: PlaybackException) {
                PlayerBus.error(error.errorCodeName + (error.message?.let { ": $it" } ?: ""))
                Toast.makeText(this@PlayerActivity, "Playback problem — trying another way…", Toast.LENGTH_SHORT).show()
                handler.removeCallbacks(closeOnError)
                handler.postDelayed(closeOnError, 12_000) // the web app retries with a fallback stream; give up if it doesn't
            }
        })

        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = true
            controllerShowTimeoutMs = 3500
            setShowNextButton(false)
            setShowPreviousButton(false)
            setKeepContentOnPlayerReset(true)
            setShowSubtitleButton(true)
        }
        title = Ui.text(this, "", 24f, bold = true)
        subtitle = Ui.text(this, "", 16f, Ui.TEXT2)
        topBar = Ui.vbox(this, Gravity.START).apply {
            setPadding(dp(32), dp(22), dp(32), dp(40))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
            addView(title); addView(subtitle)
        }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> topBar.visibility = v })
        skipBtn = Ui.button(this, "Skip intro", primary = true) { skip() }.apply { visibility = View.GONE }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(skipBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(48); bottomMargin = dp(120)
            })
        }
        setContentView(root)
        PlayerBus.player = WeakReference(this)
        intent.getStringExtra("json")?.let { load(it) }
        handler.post(ticker)
    }

    /** Start (or replace) the current item. Called for the first item, next episodes, channel zaps and fallbacks. */
    fun load(json: String) {
        handler.removeCallbacks(closeOnError)
        if (item.length() > 0 && !closedReported) report("stopped")
        item = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val url = item.optString("url")
        if (url.isBlank()) { finish(); return }
        title.text = item.optJSONObject("channel")?.optString("name")?.takeIf { it.isNotBlank() } ?: item.optString("title")
        subtitle.text = item.optString("subtitle")

        val b = MediaItem.Builder().setUri(Uri.parse(url))
        when (item.optString("type")) {
            "hls" -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            "mpegts" -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        item.optString("subtitleUrl").takeIf { it.isNotBlank() && it != "null" }?.let { sub ->
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub)).setMimeType(MimeTypes.TEXT_VTT).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        player.setMediaItem(b.build())
        val start = item.optDouble("startPosition", 0.0)
        if (start > 1 && !item.optBoolean("live")) player.seekTo((start * 1000).toLong())
        player.prepare()
        player.playWhenReady = true
        view.showController()
        closedReported = false
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateSkip()
            if (System.currentTimeMillis() - lastReport > 10_000 && player.isPlaying) report("playing")
            handler.postDelayed(this, 500)
        }
    }

    private fun positionSec() = player.currentPosition / 1000.0
    private fun durationSec(): Double = if (player.duration != C.TIME_UNSET && player.duration > 0) player.duration / 1000.0 else item.optDouble("duration", 0.0)

    private fun report(state: String) {
        if (item.optBoolean("live")) return
        lastReport = System.currentTimeMillis()
        PlayerBus.progress(positionSec(), durationSec(), state)
    }

    // ---------- Skip intro / credits (Plex markers) ----------
    private var currentMarkerEnd = -1.0
    private fun updateSkip() {
        val markers = item.optJSONArray("markers") ?: run { skipBtn.visibility = View.GONE; return }
        val pos = positionSec() + item.optDouble("startOffset", 0.0)
        var found: JSONObject? = null
        for (i in 0 until markers.length()) {
            val m = markers.optJSONObject(i) ?: continue
            if (pos >= m.optDouble("start") && pos < m.optDouble("end") - 1 && (m.optString("type") == "intro" || m.optString("type") == "credits")) { found = m; break }
        }
        if (found == null) { if (skipBtn.visibility == View.VISIBLE) skipBtn.visibility = View.GONE; currentMarkerEnd = -1.0; return }
        if (currentMarkerEnd != found.optDouble("end")) {
            currentMarkerEnd = found.optDouble("end")
            skipBtn.text = if (found.optString("type") == "intro") "Skip intro" else if (item.optBoolean("hasNext")) "Next episode" else "Skip credits"
            skipBtn.visibility = View.VISIBLE
            skipBtn.requestFocus()
        }
    }
    private fun skip() {
        skipBtn.visibility = View.GONE
        if (skipBtn.text == "Next episode") { report("stopped"); PlayerBus.ended(); return }
        player.seekTo(((currentMarkerEnd - item.optDouble("startOffset", 0.0)) * 1000).toLong())
    }

    // ---------- Remote keys ----------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // OK on the remote presses "Skip intro" while it's showing
        if (skipBtn.visibility == View.VISIBLE && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (event.action == KeyEvent.ACTION_UP) skip()
            return true
        }
        val live = item.optBoolean("live") && item.optBoolean("canZap")
        if (event.action == KeyEvent.ACTION_DOWN && live) {
            val controllerHidden = !view.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { PlayerBus.zap(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { PlayerBus.zap(1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (controllerHidden) { PlayerBus.zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (controllerHidden) { PlayerBus.zap(1); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && !item.optBoolean("live") && !view.isControllerFullyVisible && skipBtn.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { player.seekTo(player.currentPosition + 30_000); view.showController(); return true }
            }
        }
        // Any other key wakes the controls; PlayerView handles play/pause, seeking and media keys.
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) { report("paused"); player.pause() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!closedReported) { closedReported = true; PlayerBus.closed(if (item.optBoolean("live")) 0.0 else positionSec()) }
        if (PlayerBus.player?.get() === this) PlayerBus.player = null
        player.release()
        super.onDestroy()
    }
}
