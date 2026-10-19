package dev.theiceji.viper4shizuku.capture

import android.media.AudioFormat
import android.os.Bundle

/**
 * Parameters for a capture session, passed across the Shizuku binder as a [Bundle] so the
 * shell-uid process and the app agree without a custom Parcelable in the system class loader.
 */
data class CaptureConfig(
    val sampleRate: Int = 48_000,
    /** [AudioFormat.ENCODING_PCM_FLOAT] or [AudioFormat.ENCODING_PCM_16BIT]. */
    val encoding: Int = AudioFormat.ENCODING_PCM_FLOAT,
    /** AudioAttributes usages to capture; see [AudioAttributesUsages]. */
    val usages: IntArray = DEFAULT_USAGES,
    /** AudioRecord read size in frames. */
    val readFrames: Int = 1024,
    /**
     * Watchdog: if the app leaves the pipe full this long it is presumed stuck and the policy is
     * dropped, so media plays unprocessed instead of staying silent (device tests S-11/S-12: ~2 s).
     */
    val readerStallTimeoutMs: Int = 1500,
) {
    fun toBundle(): Bundle =
        Bundle().apply {
            putInt(KEY_SAMPLE_RATE, sampleRate)
            putInt(KEY_ENCODING, encoding)
            putIntArray(KEY_USAGES, usages)
            putInt(KEY_READ_FRAMES, readFrames)
            putInt(KEY_READER_STALL_TIMEOUT_MS, readerStallTimeoutMs)
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureConfig) return false
        return sampleRate == other.sampleRate &&
            encoding == other.encoding &&
            readFrames == other.readFrames &&
            readerStallTimeoutMs == other.readerStallTimeoutMs &&
            usages.contentEquals(other.usages)
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + encoding
        result = 31 * result + usages.contentHashCode()
        result = 31 * result + readFrames
        result = 31 * result + readerStallTimeoutMs
        return result
    }

    companion object {
        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_ENCODING = "encoding"
        private const val KEY_USAGES = "usages"
        private const val KEY_READ_FRAMES = "read_frames"
        private const val KEY_READER_STALL_TIMEOUT_MS = "reader_stall_timeout_ms"

        /**
         * Usages a LOOP_BACK|non-render mix is allowed to match on Android (see AOSP
         * AudioPolicyMix.cpp mixMatch): MEDIA, GAME, UNKNOWN. VOICE_COMMUNICATION would need
         * extra permission and is deliberately left out.
         */
        val DEFAULT_USAGES =
            intArrayOf(
                AudioAttributesUsages.USAGE_MEDIA,
                AudioAttributesUsages.USAGE_GAME,
                AudioAttributesUsages.USAGE_UNKNOWN,
            )

        fun fromBundle(bundle: Bundle): CaptureConfig =
            CaptureConfig(
                sampleRate = bundle.getInt(KEY_SAMPLE_RATE, 48_000),
                encoding = bundle.getInt(KEY_ENCODING, AudioFormat.ENCODING_PCM_FLOAT),
                usages = bundle.getIntArray(KEY_USAGES) ?: DEFAULT_USAGES,
                readFrames = bundle.getInt(KEY_READ_FRAMES, 1024),
                readerStallTimeoutMs = bundle.getInt(KEY_READER_STALL_TIMEOUT_MS, 1500),
            )
    }
}

/** AudioAttributes.USAGE_* mirrored so the module needn't touch @hide fields. */
object AudioAttributesUsages {
    const val USAGE_UNKNOWN = 0
    const val USAGE_MEDIA = 1
    const val USAGE_GAME = 14
}
