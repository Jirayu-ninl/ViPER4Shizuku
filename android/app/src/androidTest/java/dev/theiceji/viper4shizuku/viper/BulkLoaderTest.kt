package dev.theiceji.viper4shizuku.viper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.theiceji.viper4shizuku.effect.ConvolverState
import dev.theiceji.viper4shizuku.effect.DdcState
import dev.theiceji.viper4shizuku.effect.EffectState
import dev.theiceji.viper4shizuku.engine.NativeDsp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import dev.theiceji.viper4shizuku.viper.DspTestAudio.SAMPLE_RATE
import dev.theiceji.viper4shizuku.viper.DspTestAudio.gains
import dev.theiceji.viper4shizuku.viper.DspTestAudio.writeFloatWav
import dev.theiceji.viper4shizuku.viper.DspTestAudio.writePcm16Wav
import dev.theiceji.viper4shizuku.viper.DspTestAudio.writeVdc
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Bulk side channels end to end on the real DSP: kernel WAV and DDC .vdc files on disk,
 * through [BulkLoader] and [LocalTransport] (wired the way ViperService wires them), into
 * [NativeDsp], checked by what the DSP reports and by what it does to audio.
 */
@RunWith(AndroidJUnit4::class)
class BulkLoaderTest {
    private lateinit var root: File
    private lateinit var kernelDir: File
    private lateinit var ddcDir: File
    private val dsps = CopyOnWriteArrayList<NativeDsp>()
    private lateinit var transport: LocalTransport
    private lateinit var loader: BulkLoader

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        root = File(cache, "bulk-loader-test-${System.nanoTime()}")
        kernelDir = File(root, "Kernel").apply { mkdirs() }
        ddcDir = File(root, "DDC").apply { mkdirs() }
        transport = LocalTransport { dsps }
        loader = BulkLoader(transport, kernelDir, ddcDir)
        transport.bulkSideChannels = { ddc, kernel, force ->
            if (ddc.isNotEmpty()) loader.applyDdcDevice(ddc, force)
            if (kernel.isNotEmpty()) loader.applyConvolverKernel(kernel, force)
        }
        dsps += NativeDsp(SAMPLE_RATE)
    }

    @After
    fun tearDown() {
        dsps.forEach { it.close() }
        root.deleteRecursively()
    }

    @Test
    fun stereoKernel_spanningManyChunks_loadsIntactAndConvolvesPerChannel() {
        // 6000 stereo frames = 12000 floats, six chunks; L = 0.5 * impulse, R = 0.25 * impulse.
        writeFloatWav(File(kernelDir, "stereo.wav"), channels = 2, frames = 6000) { frame, ch ->
            if (frame == 0) (if (ch == 0) 0.5f else 0.25f) else 0f
        }
        assertTrue(2 * 6000 > 5 * BulkLoader.MAX_FLOATS_PER_CHUNK)

        assertTrue(loader.applyConvolverKernel("stereo.wav"))
        val dsp = dsps.single()
        // The DSP only takes the kernel id once the CRC of every received float matches.
        assertEquals(BulkLoader.kernelId("stereo.wav"), dsp.kernelId)

        transport.pushFullState(convolverOn("stereo.wav"))
        val (left, right) = gains(dsp)
        assertEquals("left gain", 0.5, left, 0.01)
        assertEquals("right gain", 0.25, right, 0.01)
    }

    @Test
    fun pushFullState_loadsKernelAndDdcThroughSideChannels() {
        writePcm16Wav(File(kernelDir, "mono.wav"), channels = 1, frames = 4096) { frame, _ -> if (frame == 0) 0.5f else 0f }
        writeVdc(File(ddcDir, "flat.vdc"), gain44100 = 0.25f, gain48000 = 0.5f)
        val dsp = dsps.single()

        transport.pushFullState(
            EffectState(
                convolver = ConvolverState(enable = true, kernelFile = "mono.wav"),
                ddc = DdcState(enable = true, device = "flat"),
            ),
        )

        assertEquals(BulkLoader.kernelId("mono.wav"), dsp.kernelId)
        // Convolver 0.5 (16-bit kernel, so within quantisation) times DDC 0.5 at 48 kHz.
        val (left, right) = gains(dsp)
        assertEquals(0.25, left, 0.01)
        assertEquals(0.25, right, 0.01)
    }

    @Test
    fun ddc_usesTheCoefficientsForTheStreamRate() {
        writeVdc(File(ddcDir, "flat.vdc"), gain44100 = 0.25f, gain48000 = 0.5f)
        val dsp = dsps.single()
        transport.pushFullState(EffectState(ddc = DdcState(enable = true, device = "flat")))

        assertEquals(0.5, gains(dsp).first, 0.01)
        dsp.setSampleRate(44_100)
        assertEquals(0.25, gains(dsp).first, 0.01)
    }

    @Test
    fun emptyNames_unloadKernelAndDdc() {
        writeFloatWav(File(kernelDir, "k.wav"), channels = 1, frames = 2048) { frame, _ -> if (frame == 0) 0.5f else 0f }
        writeVdc(File(ddcDir, "flat.vdc"), gain44100 = 0.5f, gain48000 = 0.5f)
        val dsp = dsps.single()
        val state =
            EffectState(
                convolver = ConvolverState(enable = true, kernelFile = "k.wav"),
                ddc = DdcState(enable = true, device = "flat"),
            )
        transport.pushFullState(state)
        assertEquals(0.25, gains(dsp).first, 0.01)

        assertTrue(loader.applyConvolverKernel(""))
        assertTrue(loader.applyDdcDevice(""))
        assertEquals(0, dsp.kernelId)
        // Both stay enabled in the params; with nothing loaded they pass audio through.
        assertEquals(1.0, gains(dsp).first, 0.01)
    }

    @Test
    fun streamedNames_areSkippedUntilForcedOrInvalidated_andANewDspStillGetsThem() {
        writeFloatWav(File(kernelDir, "k.wav"), channels = 1, frames = 2048) { frame, _ -> if (frame == 0) 0.5f else 0f }
        val first = dsps.single()
        assertTrue(loader.applyConvolverKernel("k.wav"))
        assertFalse(loader.applyConvolverKernel("k.wav"))

        // A DSP attached later: ViperService invalidates and pushes the full state again.
        val second = NativeDsp(SAMPLE_RATE).also { dsps += it }
        assertEquals(0, second.kernelId)
        transport.invalidateBulk()
        assertTrue(loader.applyConvolverKernel("k.wav"))
        transport.pushFullState(convolverOn("k.wav"))

        assertEquals(BulkLoader.kernelId("k.wav"), second.kernelId)
        // The first DSP saw the same kernel again (same CRC, so the commit is ignored) and kept it.
        assertEquals(BulkLoader.kernelId("k.wav"), first.kernelId)
        assertEquals(0.5, gains(first).first, 0.01)
        assertEquals(0.5, gains(second).first, 0.01)
    }

    @Test
    fun concurrentLoads_alwaysLeaveOneWholeKernel() {
        // Same size, so without serialisation the chunks of one land in the other's buffer and
        // both commits fail their CRC.
        writeFloatWav(File(kernelDir, "a.wav"), channels = 1, frames = 48_000) { frame, _ -> if (frame == 0) 0.5f else 0f }
        writeFloatWav(File(kernelDir, "b.wav"), channels = 1, frames = 48_000) { frame, _ -> if (frame == 0) 0.25f else 0f }
        val dsp = dsps.single()
        val expectedGain = mapOf(BulkLoader.kernelId("a.wav") to 0.5, BulkLoader.kernelId("b.wav") to 0.25)
        // Enabled by param, not by a full state, which would stream a kernel of its own.
        transport.set(ViperParams.PARAM_CONVOLVER_ENABLE, ParamValue.Bool(true))

        repeat(10) { round ->
            assertTrue(loader.applyConvolverKernel("", force = true))
            val start = CountDownLatch(1)
            val threads =
                listOf("a.wav", "b.wav").map { name ->
                    thread {
                        start.await()
                        loader.applyConvolverKernel(name, force = true)
                    }
                }
            start.countDown()
            threads.forEach { it.join() }

            val gain = expectedGain[dsp.kernelId]
            assertTrue("round $round: no kernel loaded (id=${dsp.kernelId})", gain != null)
            assertEquals("round $round", gain!!, gains(dsp).first, 0.01)
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun convolverOn(kernel: String) = EffectState(convolver = ConvolverState(enable = true, kernelFile = kernel))
}
