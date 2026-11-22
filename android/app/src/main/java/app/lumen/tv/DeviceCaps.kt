package app.lumen.tv

import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
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

    private val VIDEO = mapOf(
        "h264" to MimeTypes.VIDEO_H264, "hevc" to MimeTypes.VIDEO_H265, "h265" to MimeTypes.VIDEO_H265, "vp9" to MimeTypes.VIDEO_VP9,
        "vp8" to MimeTypes.VIDEO_VP8, "av1" to MimeTypes.VIDEO_AV1, "mpeg2video" to MimeTypes.VIDEO_MPEG2, "mpeg4" to MimeTypes.VIDEO_MP4V,
    )
    private val videoDecoders by lazy {
        runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
    }

    /** Whether a decoder on this device plays [codec] at this size (e.g. HEVC / AV1 in 4K). Unknown size = codec only. */
    fun video(codec: String, width: Int, height: Int): Boolean {
        val mime = VIDEO[codec.lowercase()] ?: return false
        return videoDecoders.any { info ->
            info.supportedTypes.any { it.equals(mime, true) } && runCatching {
                width <= 0 || height <= 0 || info.getCapabilitiesForType(mime).videoCapabilities?.let { vc ->
                    vc.isSizeSupported(width, height) || vc.isSizeSupported(height, width)
                } != false
            }.getOrDefault(true)
        }
    }

    /** Plex codec names (video) this device decodes at all. */
    fun videoCodecs(): Set<String> = VIDEO.keys.filter { video(it, 0, 0) }.toSet()

    /** Plex codec names this device can play right now. */
    @OptIn(UnstableApi::class)
    fun audio(): Set<String> {
        val out = mutableSetOf("pcm")
        for ((codec, mime) in MIMES) if (mime.lowercase() in decoders) out += codec
        // The app's own FFmpeg decoders (like Plex's app): any of these plays even without a device decoder.
        runCatching { if (FfmpegLibrary.isAvailable()) for ((codec, mime) in MIMES) if (FfmpegLibrary.supportsFormat(mime)) out += codec }
        // Passthrough only when chosen in Settings → Audio → Audio output.
        if (AppState.uiStr("audioOutput", "auto") == "passthrough") runCatching {
            @Suppress("DEPRECATION")
            val caps = AudioCapabilities.getCapabilities(Nav.appContext)
            for ((codec, enc) in PASSTHROUGH) if (caps.supportsEncoding(enc)) out += codec
        }
        return out
    }
}
