package dev.theiceji.viper4shizuku.capture

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The capture watchdog on a real kernel pipe: a stuck reader must make the writer give up within
 * the stall timeout (so the policy is dropped), and a live reader must get every byte in order.
 */
@RunWith(AndroidJUnit4::class)
class PipeWriterTest {
    @Test
    fun drainedPipe_deliversEveryByteInOrder() {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        val total = 1 shl 20
        val received = AtomicLong()
        val corrupt = AtomicBoolean(false)
        val reader =
            thread {
                FileInputStream(readEnd.fileDescriptor).use { input ->
                    val bytes = ByteArray(3000)
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        for (i in 0 until n) {
                            if (bytes[i] != pattern(received.get() + i)) corrupt.set(true)
                        }
                        received.addAndGet(n.toLong())
                    }
                }
            }

        val writer = PipeWriter(writeEnd.fileDescriptor, 1000) { true }
        val chunk = ByteBuffer.allocateDirect(8192)
        var sent = 0L
        while (sent < total) {
            chunk.clear()
            for (i in 0 until chunk.capacity()) chunk.put(pattern(sent + i))
            chunk.flip()
            assertTrue("writer stalled with a live reader", writer.writeFully(chunk))
            assertFalse(chunk.hasRemaining())
            sent += chunk.capacity()
        }
        writeEnd.close()
        reader.join(5000)
        readEnd.close()

        assertEquals(total.toLong(), received.get())
        assertFalse("bytes arrived out of order", corrupt.get())
    }

    @Test
    fun stuckReader_givesUpAfterStallTimeout() {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        val timeoutMs = 300
        val writer = PipeWriter(writeEnd.fileDescriptor, timeoutMs) { true }
        val chunk = ByteBuffer.allocateDirect(8192)

        // Nobody reads: the pipe fills, then the writer must stop waiting on its own.
        val start = SystemClock.elapsedRealtime()
        var lastWriteStarted: Long
        do {
            chunk.clear()
            lastWriteStarted = SystemClock.elapsedRealtime()
            assertTrue("never gave up", lastWriteStarted - start < 10_000)
        } while (writer.writeFully(chunk))
        val waited = SystemClock.elapsedRealtime() - lastWriteStarted

        assertTrue("gave up too early: $waited ms", waited >= timeoutMs)
        assertTrue("gave up too late: $waited ms", waited < timeoutMs + 1000)
        readEnd.close()
        writeEnd.close()
    }

    @Test
    fun stopWhileStalled_returnsPromptly() {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        val running = AtomicBoolean(true)
        val writer = PipeWriter(writeEnd.fileDescriptor, 60_000) { running.get() }
        val chunk = ByteBuffer.allocateDirect(8192)
        // Fill the pipe with a short-timeout writer first so the next write has to wait.
        val filler = PipeWriter(writeEnd.fileDescriptor, 100) { true }
        do {
            chunk.clear()
        } while (filler.writeFully(chunk))

        thread {
            SystemClock.sleep(200)
            running.set(false)
        }
        val start = SystemClock.elapsedRealtime()
        chunk.clear()
        assertFalse(writer.writeFully(chunk))
        val waited = SystemClock.elapsedRealtime() - start
        assertTrue("stop took $waited ms", waited < 1000)
        readEnd.close()
        writeEnd.close()
    }

    @Test(expected = IOException::class)
    fun closedReader_throws() {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        readEnd.close()
        try {
            PipeWriter(writeEnd.fileDescriptor, 1000) { true }.writeFully(ByteBuffer.allocateDirect(4096))
        } finally {
            writeEnd.close()
        }
    }

    private fun pattern(index: Long): Byte = (index % 251).toByte()
}
