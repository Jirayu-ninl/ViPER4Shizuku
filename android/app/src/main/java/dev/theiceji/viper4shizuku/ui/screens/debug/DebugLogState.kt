package dev.theiceji.viper4shizuku.ui.screens.debug

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.theiceji.viper4shizuku.utils.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "DebugLogState"
private const val MAX_BUFFERED_ENTRIES = 2000
private const val COMMIT_INTERVAL_MS = 32L
private const val COMMIT_BATCH_LIMIT = 64
private const val RECENT_APP_MESSAGES_CAPACITY = 512
private const val FILE_LOGGER_TAG = "ViPER4Shizuku"

@Stable
internal class DebugLogState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ingestChannel = Channel<Pair<Long, LogEntry>>(capacity = Channel.UNLIMITED)
    private val recentAppMessages = LinkedHashSet<String>()
    private val recentAppMessagesLock = Any()

    @Volatile
    private var epoch: Long = 0L
    val visibleEntries: SnapshotStateList<LogEntry> = mutableStateListOf()
    var totalCount by mutableIntStateOf(0)
        private set
    var appStreamError by mutableStateOf<String?>(null)
        private set
    var logcatStreamError by mutableStateOf<String?>(null)
        private set

    private var logcatJob: Job? = null

    init {
        scope.launch { drainIngestChannel() }
    }

    fun start(includeFileHistory: Boolean) {
        FileLogger.setListener { line ->
            val entry = LogParser.parseApp(line)
            rememberAppMessage(entry.message)
            ingestChannel.trySend(epoch to entry)
        }
        if (includeFileHistory) {
            scope.launch { loadAppHistory() }
        }
        startLogcatStream(useNowTimestamp = !includeFileHistory)
    }

    fun clear() {
        scope.launch {
            epoch++
            logcatJob?.cancel()
            withContext(Dispatchers.IO) {
                FileLogger.clearLogs()
            }
            synchronized(recentAppMessagesLock) { recentAppMessages.clear() }
            withContext(Dispatchers.Main) {
                visibleEntries.clear()
                totalCount = 0
            }
            startLogcatStream(useNowTimestamp = true)
        }
    }

    fun shutdown() {
        FileLogger.setListener(null)
        scope.cancel()
    }

    private fun startLogcatStream(useNowTimestamp: Boolean) {
        logcatJob = scope.launch { streamLogcat(useNowTimestamp) }
    }

    private suspend fun loadAppHistory() {
        withContext(Dispatchers.IO) {
            val file = FileLogger.getLogFile()
            if (file == null || !file.exists()) return@withContext
            val loadEpoch = epoch
            try {
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val entry = LogParser.parseApp(line)
                        rememberAppMessage(entry.message)
                        ingestChannel.trySend(loadEpoch to entry)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to load app log history", e)
                withContext(Dispatchers.Main) {
                    appStreamError = e.message ?: "I/O error"
                }
            }
        }
    }

    private suspend fun streamLogcat(useNowTimestamp: Boolean) {
        withContext(Dispatchers.IO) {
            val streamEpoch = epoch
            var proc: Process? = null
            try {
                val command =
                    mutableListOf("logcat", "-v", "threadtime", "--pid=${android.os.Process.myPid()}")
                if (useNowTimestamp) {
                    val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    command.addAll(listOf("-T", fmt.format(Date())))
                }
                proc =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()
                proc.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("---------")) continue
                        val entry = LogParser.parseLogcat(line) ?: continue
                        if (isAppEcho(entry)) continue
                        ingestChannel.trySend(streamEpoch to entry)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Logcat stream failed", e)
                withContext(Dispatchers.Main) {
                    logcatStreamError = e.message ?: "logcat unavailable"
                }
            } finally {
                proc?.destroy()
            }
        }
    }

    private fun rememberAppMessage(message: String) {
        synchronized(recentAppMessagesLock) {
            if (recentAppMessages.add(message) && recentAppMessages.size > RECENT_APP_MESSAGES_CAPACITY) {
                val it = recentAppMessages.iterator()
                it.next()
                it.remove()
            }
        }
    }

    private fun isAppEcho(entry: LogEntry): Boolean {
        if (entry.tag != FILE_LOGGER_TAG) return false
        return synchronized(recentAppMessagesLock) {
            recentAppMessages.contains(entry.message)
        }
    }

    private suspend fun drainIngestChannel() {
        val pending = ArrayList<LogEntry>(COMMIT_BATCH_LIMIT)
        while (scope.isActive) {
            val first = ingestChannel.receive()
            if (first.first == epoch) pending.add(first.second)
            while (pending.size < COMMIT_BATCH_LIMIT) {
                val next = ingestChannel.tryReceive().getOrNull() ?: break
                if (next.first == epoch) pending.add(next.second)
            }
            if (pending.isNotEmpty()) {
                commitBatch(pending)
                pending.clear()
            }
            delay(COMMIT_INTERVAL_MS.milliseconds)
        }
    }

    private suspend fun commitBatch(batch: List<LogEntry>) {
        withContext(Dispatchers.Main) {
            visibleEntries.addAll(batch)
            val overflow = visibleEntries.size - MAX_BUFFERED_ENTRIES
            if (overflow > 0) visibleEntries.removeRange(0, overflow)
            totalCount = visibleEntries.size
        }
    }
}
