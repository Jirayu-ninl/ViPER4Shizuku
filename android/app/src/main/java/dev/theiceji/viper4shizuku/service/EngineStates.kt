package dev.theiceji.viper4shizuku.service

/** System audio capture (other apps' media, through Shizuku) as the UI shows it. */
data class CaptureState(
    val status: Status = Status.SHIZUKU_UNAVAILABLE,
    /** Why the last start failed or capture stopped on its own; null if it did not. */
    val error: String? = null,
    val trackLatencyMs: Int = 0,
    val backlogMs: Int = 0,
    val underruns: Int = 0,
    val framesDropped: Long = 0,
) {
    enum class Status {
        /** Shizuku is not installed or not started; it has to be started again after a reboot. */
        SHIZUKU_UNAVAILABLE,
        PERMISSION_REQUIRED,
        READY,
        STARTING,
        CAPTURING,
    }
}

/** The in-app player (a local file played by ViPER4Shizuku itself) as the UI shows it. */
data class PlayerState(
    /** A file is loaded, playing or paused. */
    val active: Boolean = false,
    val title: String? = null,
    val isPlaying: Boolean = false,
    val error: String? = null,
)
