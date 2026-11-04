package app.lumen.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal JSON-over-HTTP client for the pairing/discovery endpoints. */
object Api {
    class HttpError(val code: Int, message: String) : IOException(message)

    suspend fun get(url: String, token: String? = null, timeoutMs: Int = 6000): JSONObject =
        request("GET", url, null, token, timeoutMs)

    suspend fun post(url: String, body: JSONObject, token: String? = null, timeoutMs: Int = 8000): JSONObject =
        request("POST", url, body, token, timeoutMs)

    private suspend fun request(method: String, url: String, body: JSONObject?, token: String?, timeoutMs: Int): JSONObject =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("Accept", "application/json")
                if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (code !in 200..299) throw HttpError(code, json.optString("error", "HTTP $code"))
                json
            } finally {
                c.disconnect()
            }
        }

    /** Normalises what the user typed: adds http:// and the default port. */
    fun normalise(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val hostPart = s.substringAfter("://").substringBefore('/')
        if (!hostPart.contains(':') && s.startsWith("http://")) s = s.replaceFirst(hostPart, "$hostPart:8080")
        return s
    }
}
