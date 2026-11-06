package app.lumen.tv

import android.content.Context

/** The Lumen server this TV is paired with, and its device token. Everything else lives on the server. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    var server: String?
        get() = sp.getString("server", null)
        set(v) = sp.edit().putString("server", v?.trimEnd('/')).apply()

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var serverName: String?
        get() = sp.getString("serverName", null)
        set(v) = sp.edit().putString("serverName", v).apply()

    /** Interface size on this TV (percent). */
    var uiScale: Int
        get() = sp.getInt("uiScale", 100)
        set(v) = sp.edit().putInt("uiScale", v).apply()

    /** Selected IPTV provider on this TV. */
    var providerId: Int
        get() = sp.getInt("provider", -1)
        set(v) = sp.edit().putInt("provider", v).apply()

    /** Last Live TV group shown. */
    var liveGroup: String?
        get() = sp.getString("liveGroup", null)
        set(v) = sp.edit().putString("liveGroup", v).apply()

    var lastChannel: String?
        get() = sp.getString("lastChannel", null)
        set(v) = sp.edit().putString("lastChannel", v).apply()

    var tvTheme: String?
        get() = sp.getString("tvTheme", null)
        set(v) = sp.edit().putString("tvTheme", v).apply()

    var tvAccent: String?
        get() = sp.getString("tvAccent", null)
        set(v) = sp.edit().putString("tvAccent", v).apply()

    /** Show "Who's watching?" when the app opens (otherwise use the default Plex user). */
    var askProfile: Boolean
        get() = sp.getBoolean("askProfile", true)
        set(v) = sp.edit().putBoolean("askProfile", v).apply()

    /** Connected to a Lumen server (optional: Live TV, Discover and shared settings). */
    val isPaired: Boolean get() = !server.isNullOrBlank() && !token.isNullOrBlank()

    fun clearPairing() = sp.edit().remove("token").apply()
    /** Stop using the Lumen server (the app keeps working with Plex alone). */
    fun forgetServer() = sp.edit().remove("token").remove("server").remove("serverName").apply()

    // ---------- Plex (the app signs in to Plex itself) ----------
    private fun s(key: String) = sp.getString(key, null)
    private fun s(key: String, v: String?) = sp.edit().apply { if (v == null) remove(key) else putString(key, v) }.apply()
    /** plex.tv account token. */
    var plexToken: String? get() = s("plexToken"); set(v) = s("plexToken", v)
    var plexClientId: String? get() = s("plexClientId"); set(v) = s("plexClientId", v)
    var plexMachineId: String? get() = s("plexMachineId"); set(v) = s("plexMachineId", v)
    var plexServerName: String? get() = s("plexServerName"); set(v) = s("plexServerName", v)
    /** Token for the Plex server (the account's own). */
    var plexServerToken: String? get() = s("plexServerToken"); set(v) = s("plexServerToken", v)
    var plexOwned: Boolean get() = sp.getBoolean("plexOwned", false); set(v) = sp.edit().putBoolean("plexOwned", v).apply()
    /** Last address of the Plex server that worked. */
    var plexUrl: String? get() = s("plexUrl"); set(v) = s("plexUrl", v)
    /** Current Plex Home user (null = the account itself). */
    var plexUserToken: String? get() = s("plexUserToken"); set(v) = s("plexUserToken", v)
    var plexUserUuid: String? get() = s("plexUserUuid"); set(v) = s("plexUserUuid", v)
    var plexUserRestricted: Boolean get() = sp.getBoolean("plexUserRestricted", false); set(v) = sp.edit().putBoolean("plexUserRestricted", v).apply()
    var plexDefaultUser: String? get() = s("plexDefaultUser"); set(v) = s("plexDefaultUser", v)
    /** Disconnected from Lumen on purpose: don't reconnect automatically. */
    var lumenOptOut: Boolean get() = sp.getBoolean("lumenOptOut", false); set(v) = sp.edit().putBoolean("lumenOptOut", v).apply()
    /** The Live TV row on the Android TV home screen. */
    var homeChannelId: Long get() = sp.getLong("homeChannelId", -1); set(v) = sp.edit().putLong("homeChannelId", v).apply()
    /** "Now available" alerts already shown (time, ms). */
    var alertsSince: Long get() = sp.getLong("alertsSince", 0); set(v) = sp.edit().putLong("alertsSince", v).apply()
    /** App settings when there's no Lumen server to keep them. */
    var localUi: String? get() = s("localUi"); set(v) = s("localUi", v)
}
