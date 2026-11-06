package app.lumen.tv

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lumen on the Android TV home screen:
 *  - "Continue watching" items go into the system's Watch Next row.
 *  - A "Lumen · Live TV" row with your favourite channels.
 * Selecting one opens Lumen straight into it (lumen:// links, handled by [Links]).
 */
object TvHome {
    private var lastUpdate = 0L

    /** Refresh both rows (at most every two minutes; TV only, Android 8+). */
    fun update(ctx: Context, force: Boolean = false) {
        if (!AppState.isTv || android.os.Build.VERSION.SDK_INT < 26 || !Plex.signedIn) return
        if (!force && System.currentTimeMillis() - lastUpdate < 120_000) return
        lastUpdate = System.currentTimeMillis()
        val app = ctx.applicationContext
        AppScope.launch(Dispatchers.IO) {
            runCatching { watchNext(app) }.onFailure { android.util.Log.w("Lumen", "Watch Next: ${it.message}") }
            runCatching { channels(app) }.onFailure { android.util.Log.w("Lumen", "Home channels: ${it.message}") }
        }
    }

    // ---------- Watch Next ----------
    private suspend fun watchNext(ctx: Context) {
        val items = runCatching { Plex.get("/hubs/home/continueWatching", mapOf("count" to 12)).optJSONArray("Hub").objects().firstOrNull()?.optJSONArray("Metadata").objects() }
            .getOrNull()?.map { PlexItem.from(it) } ?: return
        val cr = ctx.contentResolver
        // Ours only: the provider returns just this app's rows.
        cr.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, arrayOf(TvContractCompat.WatchNextPrograms._ID), null, null, null)?.use { c ->
            while (c.moveToNext()) cr.delete(TvContractCompat.buildWatchNextProgramUri(c.getLong(0)), null, null)
        }
        for (m in items.take(12)) {
            val ep = m.isEpisode
            val art = Plex.img(if (ep) (m.thumb ?: m.grandparentArt) else (m.art ?: m.thumb), 640, 360) ?: continue
            val b = WatchNextProgram.Builder()
                .setType(if (ep) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(if (m.viewOffsetMs > 0) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                .setLastEngagementTimeUtcMillis((m.raw.optLong("lastViewedAt").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)) * 1000)
                .setTitle(if (ep) m.grandparentTitle ?: m.title else m.title)
                .setDescription(m.summary ?: "")
                .setPosterArtUri(Uri.parse(art))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(Uri.parse("lumen://plex/${m.ratingKey}"))
                .setInternalProviderId(m.ratingKey)
            if (ep) { b.setEpisodeTitle(m.title); m.parentIndex?.let { b.setSeasonNumber(it) }; m.index?.let { b.setEpisodeNumber(it) } }
            if (m.durationMs > 0) b.setDurationMillis(m.durationMs.toInt())
            if (m.viewOffsetMs > 0) b.setLastPlaybackPositionMillis(m.viewOffsetMs.toInt())
            cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues())
        }
    }

    // ---------- Live TV favourites row ----------
    private suspend fun channels(ctx: Context) {
        if (!AppState.hasLumen || AppState.providers.isEmpty()) return
        val favs = runCatching { Repo.favorites() }.getOrNull() ?: return
        val helper = PreviewChannelHelper(ctx)
        val p = Net.prefs
        var id = p.homeChannelId
        if (id <= 0 || runCatching { helper.getPreviewChannel(id) }.getOrNull() == null) {
            val logo = BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher)
            id = helper.publishChannel(PreviewChannel.Builder().setDisplayName("Lumen · Live TV")
                .setAppLinkIntentUri(Uri.parse("lumen://live")).setLogo(logo).setInternalProviderId("lumen-live").build())
            p.homeChannelId = id
            runCatching { TvContractCompat.requestChannelBrowsable(ctx, id) } // shows the row (the first time, Android may ask)
        }
        ctx.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null)
        favs.take(24).forEachIndexed { i, ch ->
            val logo = originalUrl(ch.logo)
            val now = ch.now
            val b = PreviewProgram.Builder().setChannelId(id).setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle(ch.name).setDescription(now?.title ?: "").setWeight(1000 - i)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(Uri.parse("lumen://live/${ch.providerId}/${Uri.encode(ch.id)}"))
                .setInternalProviderId("${ch.providerId}:${ch.id}")
            if (logo != null) b.setPosterArtUri(Uri.parse(logo)) else b.setPosterArtUri(Uri.parse("android.resource://${ctx.packageName}/${R.drawable.banner}"))
            helper.publishPreviewProgram(b.build())
        }
    }

    /** Channel logos go through Lumen's signed image proxy (needs sign-in); the home screen loads the original. */
    private fun originalUrl(logo: String?): String? {
        if (logo.isNullOrBlank()) return null
        if (logo.startsWith("http")) return logo
        return runCatching { Uri.parse("http://x$logo").getQueryParameter("u") }.getOrNull()?.takeIf { it.startsWith("http") }
    }
}

/** lumen:// links (from the TV home screen) and searches (voice / Google Assistant). Handled once the app has loaded. */
object Links {
    var pending by mutableStateOf<Uri?>(null)

    fun handle(uri: Uri) {
        when (uri.host) {
            "plex" -> uri.pathSegments.firstOrNull()?.let { key ->
                AppScope.launch { runCatching { Repo.meta(key) }.getOrNull()?.let { m -> withContext(Dispatchers.Main) { Nav.play(m, resume = true) } } }
            }
            "live" -> {
                val seg = uri.pathSegments
                if (seg.size >= 2) AppScope.launch {
                    val pid = seg[0].toIntOrNull() ?: return@launch
                    val favs = runCatching { Repo.favorites() }.getOrNull().orEmpty()
                    val ch = favs.firstOrNull { it.providerId == pid && it.id == seg[1] }
                    withContext(Dispatchers.Main) { if (ch != null) Nav.playLive(ch, favs, "Favourites") else Nav.root(Screen.Live) }
                } else if (AppState.providers.isNotEmpty()) Nav.root(Screen.Live)
            }
            "search" -> { VoiceSearch.pendingQuery = uri.getQueryParameter("q"); Nav.root(Screen.Search) }
        }
    }
}

/** A search from Google Assistant / the system ("Search for X on Lumen"), or the remote's search key. */
object VoiceSearch {
    var pendingQuery by mutableStateOf<String?>(null)
    /** Bumped to start listening on the Search screen. */
    var listenTick by mutableIntStateOf(0)
}

@Suppress("unused") private val keepContentUris = ContentUris::class
