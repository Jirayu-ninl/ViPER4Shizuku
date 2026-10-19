package dev.theiceji.viper4shizuku.viper

import dev.theiceji.viper4shizuku.engine.NativeDsp
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Test signals and the kernel / DDC / WAV files the instrumented DSP tests feed the engine. */
object DspTestAudio {
    const val SAMPLE_RATE = 48_000

    /** Output/input RMS per channel for a steady 1 kHz tone, measured after the DSP settles. */
    fun gains(dsp: NativeDsp): Pair<Double, Double> {
        dsp.reset()
        val frames = SAMPLE_RATE
        val input = FloatArray(frames * 2) { i -> (0.4 * sin(2 * PI * 1000 * (i / 2) / SAMPLE_RATE)).toFloat() }
        val output = input.copyOf()
        var frame = 0
        while (frame < frames) {
            val n = minOf(1024, frames - frame)
            dsp.process(output, n, frame * 2)
            frame += n
        }
        fun rms(buf: FloatArray, ch: Int): Double {
            var sum = 0.0
            var count = 0
            for (f in frames / 2 until frames) {
                val v = buf[f * 2 + ch].toDouble()
                sum += v * v
                count++
            }
            return sqrt(sum / count)
        }
        return rms(output, 0) / rms(input, 0) to rms(output, 1) / rms(input, 1)
    }

    fun writeFloatWav(
        file: File,
        channels: Int,
        frames: Int,
        sample: (frame: Int, channel: Int) -> Float,
    ) {
        val data = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) for (c in 0 until channels) data.putFloat(sample(f, c))
        file.writeBytes(wav(formatCode = 3, channels = channels, bits = 32, data = data.array()))
    }

    fun writePcm16Wav(
        file: File,
        channels: Int,
        frames: Int,
        sample: (frame: Int, channel: Int) -> Float,
    ) {
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) for (c in 0 until channels) data.putShort((sample(f, c) * 32767f).toInt().toShort())
        file.writeBytes(wav(formatCode = 1, channels = channels, bits = 16, data = data.array()))
    }

    /** One pass-through biquad section per rate, scaled by the given gain. */
    fun writeVdc(
        file: File,
        gain44100: Float,
        gain48000: Float,
    ) {
        file.writeText(
            "SR_44100:$gain44100,0,0,0,0\n" +
                "SR_48000:$gain48000,0,0,0,0\n",
        )
    }

    private fun wav(
        formatCode: Int,
        channels: Int,
        bits: Int,
        data: ByteArray,
    ): ByteArray {
        val blockAlign = channels * bits / 8
        return ByteBuffer
            .allocate(44 + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46464952) // RIFF
            .putInt(36 + data.size)
            .putInt(0x45564157) // WAVE
            .putInt(0x20746D66) // fmt
            .putInt(16)
            .putShort(formatCode.toShort())
            .putShort(channels.toShort())
            .putInt(SAMPLE_RATE)
            .putInt(SAMPLE_RATE * blockAlign)
            .putShort(blockAlign.toShort())
            .putShort(bits.toShort())
            .putInt(0x61746164) // data
            .putInt(data.size)
            .put(data)
            .array()
    }
}
