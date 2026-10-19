package dev.theiceji.viper4shizuku.ui.screens.debug

internal enum class LogSource {
    APP,
    LOGCAT,
}

internal enum class LogLevel {
    INFO,
    DEBUG,
    WARN,
    ERROR,
    UNKNOWN,
}

internal data class LogEntry(
    val source: LogSource,
    val level: LogLevel,
    val category: String?,
    val tag: String?,
    val message: String,
    val raw: String,
)

private val APP_HEADER = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[([^]]+)]\[([^]]+)] (.*)$""")
private val LOGCAT_HEADER = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*:\s?(.*)$""")

internal object LogParser {
    fun parseApp(line: String): LogEntry {
        val match =
            APP_HEADER.find(line) ?: return LogEntry(
                source = LogSource.APP,
                level = LogLevel.UNKNOWN,
                category = null,
                tag = null,
                message = line,
                raw = line,
            )
        val (categoryToken, levelToken, message) = match.destructured
        return LogEntry(
            source = LogSource.APP,
            level = mapAppLevel(levelToken),
            category = categoryToken,
            tag = null,
            message = message,
            raw = line,
        )
    }

    fun parseLogcat(line: String): LogEntry? {
        val match = LOGCAT_HEADER.find(line) ?: return null
        val (levelToken, tagToken, message) = match.destructured
        return LogEntry(
            source = LogSource.LOGCAT,
            level = mapLogcatLevel(levelToken),
            category = null,
            tag = tagToken.trim(),
            message = message,
            raw = line,
        )
    }

    private fun mapAppLevel(token: String): LogLevel =
        when (token.uppercase()) {
            "ERROR" -> LogLevel.ERROR
            "WARN" -> LogLevel.WARN
            "INFO" -> LogLevel.INFO
            "DEBUG" -> LogLevel.DEBUG
            else -> LogLevel.UNKNOWN
        }

    private fun mapLogcatLevel(token: String): LogLevel =
        when (token) {
            "E", "F" -> LogLevel.ERROR
            "W" -> LogLevel.WARN
            "I" -> LogLevel.INFO
            "D", "V" -> LogLevel.DEBUG
            else -> LogLevel.UNKNOWN
        }
}
