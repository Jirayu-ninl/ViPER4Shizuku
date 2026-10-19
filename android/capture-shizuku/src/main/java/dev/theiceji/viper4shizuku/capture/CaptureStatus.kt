package dev.theiceji.viper4shizuku.capture

import android.os.Bundle

/** Snapshot of the capture service, moved across the binder as a [Bundle]. */
data class CaptureStatus(
    val capturing: Boolean = false,
    val uid: Int = -1,
    val sampleRate: Int = 0,
    val encoding: Int = 0,
    /** AudioMix.MIX_STATE_*: -1 disabled, 0 idle, 1 mixing; -2 = unknown. */
    val mixState: Int = MIX_STATE_UNKNOWN,
    val framesCaptured: Long = 0,
    val registrationId: String? = null,
) {
    fun toBundle(): Bundle =
        Bundle().apply {
            putBoolean(KEY_CAPTURING, capturing)
            putInt(KEY_UID, uid)
            putInt(KEY_SAMPLE_RATE, sampleRate)
            putInt(KEY_ENCODING, encoding)
            putInt(KEY_MIX_STATE, mixState)
            putLong(KEY_FRAMES, framesCaptured)
            putString(KEY_REG_ID, registrationId)
        }

    companion object {
        const val MIX_STATE_UNKNOWN = -2

        private const val KEY_CAPTURING = "capturing"
        private const val KEY_UID = "uid"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_ENCODING = "encoding"
        private const val KEY_MIX_STATE = "mix_state"
        private const val KEY_FRAMES = "frames"
        private const val KEY_REG_ID = "reg_id"

        fun fromBundle(bundle: Bundle): CaptureStatus =
            CaptureStatus(
                capturing = bundle.getBoolean(KEY_CAPTURING),
                uid = bundle.getInt(KEY_UID, -1),
                sampleRate = bundle.getInt(KEY_SAMPLE_RATE),
                encoding = bundle.getInt(KEY_ENCODING),
                mixState = bundle.getInt(KEY_MIX_STATE, MIX_STATE_UNKNOWN),
                framesCaptured = bundle.getLong(KEY_FRAMES),
                registrationId = bundle.getString(KEY_REG_ID),
            )
    }
}
