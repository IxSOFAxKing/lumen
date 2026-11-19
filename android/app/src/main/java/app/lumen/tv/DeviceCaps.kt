package app.lumen.tv

import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * Which audio formats this device can really play: its own decoders, plus formats it can send untouched to a TV,
 * soundbar or receiver over HDMI (passthrough). Phones usually can't play DTS or TrueHD; then Plex converts just the
 * sound (the picture is still sent as it is).
 */
object DeviceCaps {
    private val MIMES = mapOf(
        "aac" to MimeTypes.AUDIO_AAC, "mp3" to MimeTypes.AUDIO_MPEG, "opus" to MimeTypes.AUDIO_OPUS, "vorbis" to MimeTypes.AUDIO_VORBIS,
        "flac" to MimeTypes.AUDIO_FLAC, "alac" to MimeTypes.AUDIO_ALAC, "ac3" to MimeTypes.AUDIO_AC3, "eac3" to MimeTypes.AUDIO_E_AC3,
        "dca" to MimeTypes.AUDIO_DTS, "dts" to MimeTypes.AUDIO_DTS, "truehd" to MimeTypes.AUDIO_TRUEHD,
    )
    private val PASSTHROUGH = mapOf(
        "ac3" to C.ENCODING_AC3, "eac3" to C.ENCODING_E_AC3, "dca" to C.ENCODING_DTS, "dts" to C.ENCODING_DTS, "truehd" to C.ENCODING_DOLBY_TRUEHD,
    )
    private val decoders: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { it.supportedTypes.map { t -> t.lowercase() } }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Plex codec names this device can play right now. */
    @OptIn(UnstableApi::class)
    fun audio(): Set<String> {
        val out = mutableSetOf("pcm")
        for ((codec, mime) in MIMES) if (mime.lowercase() in decoders) out += codec
        // "Audio channels: Stereo" turns passthrough off (everything is decoded here).
        if (AppState.uiStr("audioOutput", "auto") != "stereo") runCatching {
            @Suppress("DEPRECATION")
            val caps = AudioCapabilities.getCapabilities(Nav.appContext)
            for ((codec, enc) in PASSTHROUGH) if (caps.supportsEncoding(enc)) out += codec
        }
        return out
    }
}
