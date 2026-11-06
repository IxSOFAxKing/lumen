package app.lumen.tv

import android.content.Context
import android.net.Uri
import android.view.ContextThemeWrapper
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.images.WebImage

/** Cast settings: Google's standard receiver (plays HLS, which Plex makes for us). */
class LumenCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): com.google.android.gms.cast.framework.CastOptions =
        com.google.android.gms.cast.framework.CastOptions.Builder()
            .setReceiverApplicationId(com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Casting Plex movies and episodes from the phone to a Chromecast. Plex makes an HLS stream at the current position
 * (the Chromecast fetches it straight from the Plex server), and the phone's player pauses meanwhile.
 */
object Cast {
    fun context(ctx: Context): CastContext? = runCatching { CastContext.getSharedInstance(ctx.applicationContext) }.getOrNull()
    fun session(ctx: Context): CastSession? = context(ctx)?.sessionManager?.currentCastSession?.takeIf { it.isConnected }

    /** The device picker (or, while casting, the "stop casting" dialog). */
    fun showPicker(ctx: Context) {
        val cc = context(ctx) ?: run {
            android.widget.Toast.makeText(ctx, "Casting needs Google Play services on this phone.", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val themed = ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        if (session(ctx) != null) androidx.mediarouter.app.MediaRouteControllerDialog(themed).show()
        else androidx.mediarouter.app.MediaRouteChooserDialog(themed).apply { routeSelector = cc.mergedSelector ?: androidx.mediarouter.media.MediaRouteSelector.EMPTY }.show()
    }

    fun load(s: CastSession, url: String, title: String, subtitle: String, poster: String?) {
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            if (subtitle.isNotBlank()) putString(MediaMetadata.KEY_SUBTITLE, subtitle)
            poster?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(url).setContentType("application/x-mpegURL").setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(meta).build()
        s.remoteMediaClient?.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
    }

    /** Listener helper: only the callbacks we need. */
    open class Listener : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }
}
