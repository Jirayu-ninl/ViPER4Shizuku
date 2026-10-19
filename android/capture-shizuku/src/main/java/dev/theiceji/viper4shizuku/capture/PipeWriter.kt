package dev.theiceji.viper4shizuku.capture

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Writes captured PCM into the app's pipe without ever blocking for long. This is the capture
 * watchdog: a pipe that stays full means the app is alive but not reading (stuck renderer,
 * frozen process), which linkToDeath never reports, while the policy keeps all media silent.
 */
internal class PipeWriter(
    private val pipe: FileDescriptor,
    private val stallTimeoutMs: Int,
    private val keepGoing: () -> Boolean,
) {
    private val pollFd =
        StructPollfd().apply {
            fd = pipe
            events = OsConstants.POLLOUT.toShort()
        }
    private val pollFds = arrayOf(pollFd)

    /**
     * Writes all of [buffer]. Returns false if the pipe stayed full for [stallTimeoutMs] or
     * [keepGoing] turned false; throws [IOException] when the reader is gone.
     */
    fun writeFully(buffer: ByteBuffer): Boolean {
        var stalledSince = 0L
        while (buffer.hasRemaining()) {
            if (!keepGoing()) return false
            pollFd.revents = 0
            val ready =
                try {
                    Os.poll(pollFds, POLL_MS)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw IOException("pipe poll: ${e.message}", e)
                }
            if (ready == 0) {
                val now = SystemClock.elapsedRealtime()
                if (stalledSince == 0L) stalledSince = now
                if (now - stalledSince >= stallTimeoutMs) return false
                continue
            }
            if (pollFd.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                throw IOException("pipe reader gone (revents=${pollFd.revents})")
            }
            // POLLOUT on a pipe means a whole buffer slot is free, so a write of at most PIPE_BUF
            // bytes completes without blocking.
            val limit = buffer.limit()
            buffer.limit(minOf(limit, buffer.position() + PIPE_BUF))
            try {
                Os.write(pipe, buffer)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR) throw IOException("pipe write: ${e.message}", e)
            } finally {
                buffer.limit(limit)
            }
            stalledSince = 0L
        }
        return true
    }

    private companion object {
        /** POSIX PIPE_BUF: a write up to this size into a pipe with a free slot never blocks. */
        const val PIPE_BUF = 4096

        /** How often a full pipe is re-polled while waiting for the app to drain it. */
        const val POLL_MS = 50
    }
}
