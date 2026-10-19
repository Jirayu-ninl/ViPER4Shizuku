package dev.theiceji.viper4shizuku.probe

import android.os.SystemClock
import dev.theiceji.viper4shizuku.engine.NativeDsp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Proves ViPERDSP loads, processes audio and sustains real-time throughput,
 * independent of any capture. Run this first; if it fails, a capture failure is moot.
 */
object DspSelfTest {
    fun run(sampleRate: Int = 48_000) {
        ProbeLog.info("DSP self-test start, sr=$sampleRate")
        ProbeLog.result("dsp.version", NativeDsp.version)
        ProbeLog.result("dsp.arch", NativeDsp.architecture)
        ProbeLog.result("dsp.paramsSize", NativeDsp.paramsSize)

        NativeDsp(sampleRate).use { dsp ->
            ProbeLog.result("dsp.create", "ok")
            ProbeLog.result("dsp.sampleRate", dsp.sampleRate)

            // A 1 kHz sine, interleaved stereo.
            val frames = 4800
            val input = FloatArray(frames * 2)
            val freq = 1000.0
            for (i in 0 until frames) {
                val s = (0.5 * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
                input[i * 2] = s
                input[i * 2 + 1] = s
            }

            // Passthrough (no effects enabled): output must stay finite and non-silent.
            val buf = input.copyOf()
            dsp.process(buf, frames)
            val finite = buf.all { it.isFinite() }
            val peak = buf.maxOf { kotlin.math.abs(it) }
            ProbeLog.result("dsp.passthrough.finite", finite)
            ProbeLog.result("dsp.passthrough.peak", "%.4f".format(peak))
            ProbeLog.result("dsp.passthrough.nonSilent", peak > 0.01f)

            // Parameter path: scale output via the master limiter output volume and confirm the
            // dispatched param actually reaches the engine (peak drops from the passthrough peak).
            dsp.reset()
            val dispatched = dsp.dispatch(PARAM_MASTER_LIMITER_OUTPUT_VOLUME, floatPayload(0.25f))
            ProbeLog.result("dsp.dispatch.accepted", dispatched)
            val scaled = input.copyOf()
            dsp.process(scaled, frames)
            val scaledPeak = scaled.maxOf { abs(it) }
            ProbeLog.result("dsp.dispatch.scaledPeak", "%.4f".format(scaledPeak))
            ProbeLog.result("dsp.dispatch.changedOutput", scaledPeak < peak - 0.05f)
            // Restore unity before the throughput run.
            dsp.dispatch(PARAM_MASTER_LIMITER_OUTPUT_VOLUME, floatPayload(1.0f))
            dsp.reset()

            // Throughput: process ~10 s of audio and report the realtime ratio. A value well
            // above 1 means the DSP keeps up with capture on this core.
            val totalFrames = sampleRate * 10
            val chunk = 1024
            val work = FloatArray(chunk * 2)
            var done = 0
            val start = SystemClock.elapsedRealtimeNanos()
            while (done < totalFrames) {
                System.arraycopy(input, 0, work, 0, minOf(chunk, frames) * 2)
                dsp.process(work, chunk)
                done += chunk
            }
            val elapsedSec = (SystemClock.elapsedRealtimeNanos() - start) / 1e9
            val audioSec = done.toDouble() / sampleRate
            ProbeLog.result("dsp.throughput.audioSec", "%.1f".format(audioSec))
            ProbeLog.result("dsp.throughput.wallSec", "%.3f".format(elapsedSec))
            ProbeLog.result("dsp.throughput.realtimeRatio", "%.1f".format(audioSec / elapsedSec))
            ProbeLog.result("dsp.processedFrames", dsp.processedFrames)
        }
        ProbeLog.result("dsp.selftest", "done")
    }

    // Matches ViperParamPayload.float(): [u32 type=3][i32 index=-1][u32 count=1][f32 value], LE.
    private const val PARAM_MASTER_LIMITER_OUTPUT_VOLUME = 0x10111
    private const val TYPE_FLOAT = 3

    private fun floatPayload(value: Float): ByteArray =
        ByteBuffer
            .allocate(12 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TYPE_FLOAT)
            .putInt(-1)
            .putInt(1)
            .putFloat(value)
            .array()
}
