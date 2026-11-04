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

    val isPaired: Boolean get() = !server.isNullOrBlank() && !token.isNullOrBlank()

    fun clearPairing() = sp.edit().remove("token").apply()
}
