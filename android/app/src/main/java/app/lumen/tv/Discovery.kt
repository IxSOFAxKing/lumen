package app.lumen.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/** Finds Lumen servers on the local network by probing GET /api/health on every address of the TV's subnet. */
object Discovery {
    data class Server(val url: String, val name: String, val version: String, val authRequired: Boolean)

    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(it) }
    } catch (e: Exception) { null }

    suspend fun scan(ports: List<Int> = listOf(8080)): List<Server> = coroutineScope {
        val ip = localIpv4() ?: return@coroutineScope emptyList()
        val prefix = ip.substringBeforeLast('.')
        val sem = Semaphore(48)
        (1..254).flatMap { host -> ports.map { port -> "http://$prefix.$host:$port" } }
            .map { url -> async(Dispatchers.IO) { sem.withPermit { probe(url) } } }
            .awaitAll()
            .filterNotNull()
            .sortedBy { it.name }
    }

    fun probe(base: String, timeoutMs: Int = 700): Server? = try {
        val c = URL("$base/api/health").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs * 2
        try {
            if (c.responseCode != 200) null else {
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                if (j.optString("app") != "lumen") null
                else Server(base, j.optString("name", base), j.optString("version", ""), j.optBoolean("authRequired"))
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { null }
}
