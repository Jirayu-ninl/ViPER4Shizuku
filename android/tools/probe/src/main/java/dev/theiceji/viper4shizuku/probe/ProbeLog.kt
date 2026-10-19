package dev.theiceji.viper4shizuku.probe

import android.util.Log

/**
 * Structured, greppable logging for the device-test scripts. Every machine-readable line is
 * `ViPER4ShizukuProbe: RESULT <key> <value>` so `adb logcat -s ViPER4ShizukuProbe` can be
 * parsed without guessing. Human notes go through [info].
 */
object ProbeLog {
    const val TAG = "ViPER4ShizukuProbe"

    fun result(
        key: String,
        value: Any?,
    ) {
        Log.i(TAG, "RESULT $key ${value.toString().replace('\n', ' ')}")
    }

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun error(
        message: String,
        tr: Throwable? = null,
    ) {
        if (tr != null) Log.e(TAG, message, tr) else Log.e(TAG, message)
        Log.i(TAG, "RESULT error ${message.replace('\n', ' ')}")
    }
}
