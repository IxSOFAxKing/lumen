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

    val isPaired: Boolean get() = !server.isNullOrBlank() && !token.isNullOrBlank()

    fun clearPairing() = sp.edit().remove("token").apply()
}
