package dev.theiceji.viper4shizuku.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-app player: media3 [AudioProcessor] that runs decoded PCM through a [NativeDsp].
 *
 * Only stereo PCM_16BIT / PCM_FLOAT is processed; any other format makes the processor
 * inactive so playback continues unprocessed rather than failing.
 */
@UnstableApi
class DspAudioProcessor(
    private val dsp: NativeDsp,
) : BaseAudioProcessor() {
    private var encoding: Int = C.ENCODING_INVALID
    private var bytesPerFrame: Int = 0
    private var scratch: ByteBuffer = EMPTY

    @Volatile
    var bypass: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported =
            inputAudioFormat.channelCount == 2 &&
                (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        if (!supported) return AudioProcessor.AudioFormat.NOT_SET
        encoding = inputAudioFormat.encoding
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        dsp.setSampleRate(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val frames = size / bytesPerFrame
        val usable = frames * bytesPerFrame
        val output = replaceOutputBuffer(usable)

        if (bypass || frames == 0) {
            val slice = inputBuffer.duplicate()
            slice.limit(slice.position() + usable)
            output.put(slice)
        } else {
            val source = directInput(inputBuffer, usable)
            val sourceOffset = if (source === inputBuffer) inputBuffer.position() else 0
            val nativeEncoding =
                if (encoding == C.ENCODING_PCM_FLOAT) NativeDsp.ENCODING_PCM_FLOAT else NativeDsp.ENCODING_PCM_16BIT
            if (dsp.process(source, sourceOffset, output, 0, frames, nativeEncoding)) {
                output.position(usable)
            } else {
                val slice = inputBuffer.duplicate()
                slice.limit(slice.position() + usable)
                output.put(slice)
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        dsp.reset()
    }

    override fun onReset() {
        scratch = EMPTY
        encoding = C.ENCODING_INVALID
        bytesPerFrame = 0
    }

    private fun directInput(
        input: ByteBuffer,
        size: Int,
    ): ByteBuffer {
        if (input.isDirect) return input
        if (scratch.capacity() < size) {
            scratch = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        scratch.clear()
        val slice = input.duplicate()
        slice.limit(slice.position() + size)
        scratch.put(slice)
        scratch.flip()
        return scratch
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
