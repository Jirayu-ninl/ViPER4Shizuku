package dev.theiceji.viper4shizuku.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max

/**
 * System audio capture, app side: reads captured PCM from the capture service's pipe, runs it
 * through [dsp] and plays it with an [AudioTrack] owned by the ViPER4Shizuku process.
 *
 * Playback stays in the app (not in the shell-uid capture process) because Android 17's
 * background audio hardening only lets a foreground service with while-in-use capability play.
 */
class LoopbackRenderer(
    private val source: ParcelFileDescriptor,
    val sampleRate: Int,
    val encoding: Int,
    private val dsp: NativeDsp,
    private val config: Config = Config(),
    private val onStopped: (reason: String) -> Unit = {},
) {
    data class Config(
        /** Frames read, processed and written per iteration (10 ms at 48 kHz). */
        val chunkFrames: Int = 480,
        /** AudioTrack buffer as a multiple of its minimum size. */
        val trackBufferMultiplier: Int = 2,
        /** Captured audio waiting in the pipe beyond this is dropped to bound latency. */
        val maxBacklogMs: Int = 80,
        /** Backlog kept after a drop. */
        val targetBacklogMs: Int = 20,
    )

    data class Stats(
        val running: Boolean = false,
        val framesIn: Long = 0,
        val framesDropped: Long = 0,
        val underruns: Int = 0,
        val peak: Float = 0f,
        val trackLatencyMs: Int = 0,
        val backlogMs: Int = 0,
    )

    @Volatile
    var bypass: Boolean = false

    @Volatile
    var stats: Stats = Stats()
        private set

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
    private val bytesPerFrame = bytesPerSample * 2

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "ViPER4ShizukuLoopback").apply { start() }
    }

    @Synchronized
    fun stop() {
        running = false
        // FileChannel is interruptible: this unblocks a pending read() on the pipe.
        thread?.let {
            if (it !== Thread.currentThread()) {
                it.interrupt()
                it.join(1000)
            }
        }
        thread = null
        try {
            source.close()
        } catch (_: IOException) {
        }
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var reason = "stopped"
        val track = buildTrack()
        try {
            FileInputStream(source.fileDescriptor).use { stream ->
                val channel = stream.channel
                val buffer =
                    ByteBuffer.allocateDirect(config.chunkFrames * bytesPerFrame).order(ByteOrder.nativeOrder())
                val discard = ByteBuffer.allocateDirect(buffer.capacity()).order(ByteOrder.nativeOrder())
                val maxBacklog = msToBytes(config.maxBacklogMs)
                val targetBacklog = msToBytes(config.targetBacklogMs)
                var framesIn = 0L
                var framesDropped = 0L
                var peak = 0f
                var lastStats = 0L
                track.play()

                while (running) {
                    buffer.clear()
                    if (!readFully(channel, buffer)) {
                        reason = "capture ended"
                        break
                    }
                    val frames = config.chunkFrames
                    framesIn += frames

                    val backlog = stream.available()
                    if (backlog > maxBacklog) {
                        framesDropped += skip(channel, discard, backlog - targetBacklog) / bytesPerFrame
                    }

                    if (!bypass) dsp.process(buffer, 0, buffer, 0, frames, encoding)
                    peak = max(peak, peakOf(buffer))

                    buffer.position(0)
                    val written = track.write(buffer, buffer.limit(), AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        reason = "AudioTrack.write failed: $written"
                        break
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStats >= 250) {
                        stats =
                            Stats(
                                running = true,
                                framesIn = framesIn,
                                framesDropped = framesDropped,
                                underruns = track.underrunCount,
                                peak = peak,
                                trackLatencyMs = track.bufferSizeInFrames * 1000 / sampleRate,
                                backlogMs = backlog * 1000 / (sampleRate * bytesPerFrame),
                            )
                        peak = 0f
                        lastStats = now
                    }
                }
            }
        } catch (e: IOException) {
            reason = if (running) "pipe error: ${e.message}" else "stopped"
        } catch (e: Exception) {
            Log.e(TAG, "renderer failed", e)
            reason = "renderer error: ${e.message}"
        } finally {
            running = false
            try {
                track.pause()
                track.flush()
            } catch (_: IllegalStateException) {
            }
            track.release()
            stats = stats.copy(running = false)
            try {
                source.close()
            } catch (_: IOException) {
            }
            Log.i(TAG, "renderer finished: $reason")
            onStopped(reason)
        }
    }

    private fun buildTrack(): AudioTrack {
        val minSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        val chunkBytes = config.chunkFrames * bytesPerFrame
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .apply {
                        // Capture policies start at API 29; below that there is no playback capture.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                        }
                    }.build(),
            ).setAudioFormat(
                AudioFormat
                    .Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            ).setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(minSize * config.trackBufferMultiplier, chunkBytes * 2))
            .build()
    }

    private fun msToBytes(ms: Int): Int = sampleRate / 1000 * ms * bytesPerFrame

    private fun peakOf(buffer: ByteBuffer): Float {
        var p = 0f
        val limit = buffer.limit()
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            var i = 0
            while (i < limit) {
                p = max(p, abs(buffer.getFloat(i)))
                i += 4 * 8
            }
        } else {
            var i = 0
            while (i < limit) {
                p = max(p, abs(buffer.getShort(i) / 32768f))
                i += 2 * 8
            }
        }
        return p
    }

    private fun readFully(
        channel: FileChannel,
        buffer: ByteBuffer,
    ): Boolean {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) return false
        }
        buffer.flip()
        return true
    }

    private fun skip(
        channel: FileChannel,
        scratch: ByteBuffer,
        bytes: Int,
    ): Int {
        val aligned = bytes - bytes % bytesPerFrame
        var left = aligned
        while (left > 0) {
            scratch.clear()
            scratch.limit(minOf(left, scratch.capacity()))
            val n = channel.read(scratch)
            if (n < 0) break
            left -= n
        }
        // Keep the stream frame aligned even if the pipe ended mid-skip.
        return aligned - left
    }

    private companion object {
        const val TAG = "ViPER4ShizukuLoopback"
    }
}
