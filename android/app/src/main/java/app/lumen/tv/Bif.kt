package app.lumen.tv

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Seek-bar preview thumbnails from Plex's BIF index (the same thumbnails Plex apps show while scrubbing).
 * The file is cached on disk and thumbnails are read from it on demand, so long movies don't use much memory.
 */
class Bif private constructor(private val file: File, private val intervalMs: Long, private val times: LongArray, private val offsets: LongArray) {
    /** Index of the thumbnail shown at [posSec]. */
    fun indexAt(posSec: Double): Int {
        val ms = (posSec * 1000).toLong()
        var lo = 0; var hi = times.size - 2
        if (hi < 0) return -1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (times[mid] <= ms) lo = mid else hi = mid - 1 }
        return lo
    }

    suspend fun image(i: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        if (i < 0 || i >= offsets.size - 1) return@withContext null
        val len = (offsets[i + 1] - offsets[i]).toInt()
        if (len <= 0 || len > 2_000_000) return@withContext null
        runCatching {
            RandomAccessFile(file, "r").use { f ->
                val buf = ByteArray(len)
                f.seek(offsets[i]); f.readFully(buf)
                BitmapFactory.decodeByteArray(buf, 0, len)?.asImageBitmap()
            }
        }.getOrNull()
    }

    companion object {
        private val MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)

        suspend fun load(ctx: Context, partId: String): Bif? = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "bif").apply { mkdirs() }
            // Keep the cache small: only the most recent few files.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
            val file = File(dir, "$partId.bif")
            if (!file.exists() || file.length() < 64) {
                val tmp = File(dir, "$partId.part")
                val ok = runCatching {
                    Net.http.newCall(Request.Builder().url("${Net.server}/api/plex/bif/$partId").build()).execute().use { res ->
                        if (!res.isSuccessful) return@use false
                        tmp.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
                        true
                    }
                }.getOrDefault(false)
                if (!ok || !tmp.renameTo(file)) { tmp.delete(); return@withContext null }
            }
            file.setLastModified(System.currentTimeMillis())
            runCatching { parse(file) }.getOrNull()
        }

        private fun parse(file: File): Bif? {
            RandomAccessFile(file, "r").use { f ->
                val head = ByteArray(64); f.readFully(head)
                if (!head.copyOfRange(0, 8).contentEquals(MAGIC)) return null
                val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                val count = bb.getInt(12).toLong() and 0xffffffffL
                var interval = bb.getInt(16).toLong() and 0xffffffffL
                if (interval == 0L) interval = 1000
                if (count <= 0 || count > 200_000) return null
                val table = ByteArray(((count + 1) * 8).toInt()); f.readFully(table)
                val tb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
                val times = LongArray((count + 1).toInt()); val offs = LongArray((count + 1).toInt())
                for (i in 0..count.toInt()) {
                    val ts = tb.getInt(i * 8).toLong() and 0xffffffffL
                    times[i] = if (ts == 0xffffffffL) Long.MAX_VALUE else ts * interval
                    offs[i] = tb.getInt(i * 8 + 4).toLong() and 0xffffffffL
                }
                return Bif(file, interval, times, offs)
            }
        }
    }
}
