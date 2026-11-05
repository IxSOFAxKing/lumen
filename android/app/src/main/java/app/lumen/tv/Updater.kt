package app.lumen.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * In-app updates: checks the latest GitHub release of the Lumen TV app, downloads the APK and hands it to
 * Android's package installer. Releases are signed with the same key, so they install over this version.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/IxSOFAxKing/lumen/releases/latest"
    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

    @Volatile var downloading = false
    private var lastAutoCheck = 0L

    /** The newest release, or null when this version is current (or GitHub can't be reached). */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            Net.http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val j = JSONObject(res.body?.string().orEmpty())
                val tag = j.optString("tag_name")                      // tv-v1.0.53
                val name = tag.removePrefix("tv-v")
                val code = name.substringAfterLast('.').toIntOrNull() ?: return@use null
                val apk = j.optJSONArray("assets").objects().firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url") ?: return@use null
                if (code > BuildConfig.VERSION_CODE) Release(code, name, apk, j.optString("body")) else null
            }
        }.getOrNull()
    }

    /** Checked when the app opens (at most every 6 hours): offers the update in a small dialog. */
    fun autoCheck() {
        if (System.currentTimeMillis() - lastAutoCheck < 6 * 3600_000L) return
        lastAutoCheck = System.currentTimeMillis()
        AppScope.launch { check()?.let { offer(it) } }
    }

    fun offer(r: Release) {
        Menus.current = MenuSpec("Update available: ${r.versionName}", "You have ${BuildConfig.VERSION_NAME}. Your settings and pairing are kept.", listOf(
            "Update now" to { install(Nav.appContext, r) },
            "Later" to {},
        ))
    }

    fun install(ctx: Context, r: Release) {
        if (downloading) return
        // Android asks once for permission to install apps from Lumen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, "Allow Lumen to install updates, then choose Update again.", Toast.LENGTH_LONG).show()
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }.onFailure {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return
        }
        downloading = true
        Toast.makeText(ctx, "Downloading Lumen ${r.versionName}…", Toast.LENGTH_SHORT).show()
        AppScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(ctx.cacheDir, "lumen-update.apk")
                    Net.http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { res ->
                        if (!res.isSuccessful) error("Download failed (${res.code})")
                        f.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
                    }
                    f
                }
            }
            downloading = false
            file.onFailure { Toast.makeText(ctx, it.message ?: "Download failed", Toast.LENGTH_LONG).show() }
                .onSuccess { f ->
                    runCatching { startSession(ctx, f) }.onFailure { Toast.makeText(ctx, "Couldn't start the installer: ${it.message}", Toast.LENGTH_LONG).show() }
                }
        }
    }

    private suspend fun startSession(ctx: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("lumen.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
    }
}

/** Receives the installer's result; shows Android's "Do you want to update this app?" confirmation. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(ctx, "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }
}
