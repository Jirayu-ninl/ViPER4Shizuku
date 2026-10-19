package dev.theiceji.viper4shizuku.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.theiceji.viper4shizuku.effect.ConvolverState
import dev.theiceji.viper4shizuku.effect.DdcState
import dev.theiceji.viper4shizuku.effect.EffectState
import dev.theiceji.viper4shizuku.engine.NativeDsp
import dev.theiceji.viper4shizuku.viper.BulkLoader
import dev.theiceji.viper4shizuku.viper.DspTestAudio.SAMPLE_RATE
import dev.theiceji.viper4shizuku.viper.DspTestAudio.gains
import dev.theiceji.viper4shizuku.viper.DspTestAudio.writeFloatWav
import dev.theiceji.viper4shizuku.viper.DspTestAudio.writePcm16Wav
import dev.theiceji.viper4shizuku.viper.DspTestAudio.writeVdc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * The real [ViperService] feeding engines: kernel and DDC files read from the external-files
 * dirs the UI imports into, pushed into DSPs in the order engines attach them, and the master
 * switch flipped the ways the UI and the QS tile flip it. The service is bound the way
 * MainViewModel binds it, with a state provider standing in for the UI state.
 */
@RunWith(AndroidJUnit4::class)
class ViperServiceEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var service: ViperService
    private lateinit var connection: ServiceConnection
    private val files = mutableListOf<File>()
    private val ownDsps = mutableListOf<NativeDsp>()

    @Volatile
    private var uiState = EffectState()

    // Unique per run: a service instance can outlive one test, and file names are its cache keys.
    private val run = System.nanoTime()
    private val kernel = "svc-test-$run.wav"
    private val otherKernel = "svc-test-$run-b.wav"
    private val ddc = "svc-test-$run"

    @Before
    fun setUp() {
        val connected = CountDownLatch(1)
        var bound: ViperService? = null
        connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    bound = (binder as ViperService.LocalBinder).service
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        assertTrue(context.bindService(Intent(context, ViperService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue("service did not bind", connected.await(10, TimeUnit.SECONDS))
        service = bound!!
        service.setStateProvider { uiState }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            service.stopPlayer()
            ownDsps.forEach { service.detachDsp(it) }
        }
        ownDsps.forEach { it.close() }
        // The tile's toggle starts the service as well as binding it.
        context.stopService(Intent(context, ViperService::class.java))
        context.unbindService(connection)
        files.forEach { it.delete() }
    }

    @Test
    fun attachWithMasterOn_loadsKernelAndDdcFromTheUiImportDirs() {
        installKernel(kernel, gain = 0.5f)
        installDdc(ddc, gain = 0.5f)
        uiState = convolverAndDdc(master = true)

        val dsp = attachDsp()

        awaitTrue("kernel in the attached DSP") { dsp.kernelId == BulkLoader.kernelId(kernel) }
        // Convolver 0.5 times DDC 0.5; the DDC goes out before the kernel, so it is in too.
        val (left, right) = gains(dsp)
        assertEquals("left", 0.25, left, 0.01)
        assertEquals("right", 0.25, right, 0.01)
    }

    @Test
    fun attachWithMasterOff_loadsNothing_untilTheUiTurnsMasterOn() {
        installKernel(kernel, gain = 0.5f)
        installDdc(ddc, gain = 0.5f)
        uiState = convolverAndDdc(master = false)

        val dsp = attachDsp()
        settle()
        assertEquals(0, dsp.kernelId)

        // MainViewModel.setMasterEnabled: new UI state, then dispatchFullState() on the main thread.
        uiState = uiState.copy(masterEnable = true)
        instrumentation.runOnMainSync { service.dispatchFullState(uiState) }

        awaitTrue("kernel after master on") { dsp.kernelId == BulkLoader.kernelId(kernel) }
        assertEquals(0.25, gains(dsp).first, 0.01)
    }

    @Test
    fun fullStateFromTheUi_returnsBeforeTheKernelIsStreamed() {
        // Two seconds of stereo kernel, about 90 chunks: far too slow to finish inside the call.
        val file = File(importDir("Kernel"), kernel)
        writeFloatWav(file, channels = 2, frames = 2 * SAMPLE_RATE) { frame, _ -> if (frame == 0) 0.5f else 0f }
        files += file
        uiState = EffectState(masterEnable = false, convolver = ConvolverState(enable = true, kernelFile = kernel))
        val dsp = attachDsp()
        settle()

        uiState = uiState.copy(masterEnable = true)
        var kernelWhenTheCallReturned = -1
        instrumentation.runOnMainSync {
            service.dispatchFullState(uiState)
            kernelWhenTheCallReturned = dsp.kernelId
        }

        assertEquals("kernel streamed on the main thread", 0, kernelWhenTheCallReturned)
        awaitTrue("kernel streamed after the call") { dsp.kernelId == BulkLoader.kernelId(kernel) }
    }

    @Test
    fun kernelPickedInTheUi_replacesTheOneInAnAttachedDsp() {
        installKernel(kernel, gain = 0.5f)
        installKernel(otherKernel, gain = 0.25f)
        uiState = EffectState(masterEnable = true, convolver = ConvolverState(enable = true, kernelFile = kernel))
        val dsp = attachDsp()
        awaitTrue("first kernel") { dsp.kernelId == BulkLoader.kernelId(kernel) }

        // MainViewModel.setConvolverKernel: new UI state, then the kernel streamed off the main thread.
        uiState = uiState.copy(convolver = uiState.convolver.copy(kernelFile = otherKernel))
        service.applyConvolverKernel(otherKernel, force = true)

        awaitTrue("second kernel") { dsp.kernelId == BulkLoader.kernelId(otherKernel) }
        assertEquals(0.25, gains(dsp).first, 0.01)
    }

    @Test
    fun player_restarted_getsTheKernelInItsNewDsp() {
        installKernel(kernel, gain = 0.5f)
        uiState = EffectState(masterEnable = true, convolver = ConvolverState(enable = true, kernelFile = kernel))
        val tone = toneFile()

        repeat(2) { round ->
            instrumentation.runOnMainSync { service.playLocalFile(Uri.fromFile(tone)) }
            awaitTrue("round $round: kernel in the player") {
                service.probeDriverStatus()?.kernelId == BulkLoader.kernelId(kernel)
            }
            instrumentation.runOnMainSync { service.stopPlayer() }
            assertNull("round $round: player DSP still attached", service.probeDriverStatus())
        }
    }

    @Test
    fun player_followsTheMasterSwitch_fromTheUiAndFromTheTile() {
        uiState = EffectState(masterEnable = true)
        val tone = Uri.fromFile(toneFile())
        instrumentation.runOnMainSync { service.playLocalFile(tone) }
        assertProcessing("master on at start")

        uiState = uiState.copy(masterEnable = false)
        instrumentation.runOnMainSync { service.dispatchFullState(uiState) }
        assertBypassed("master off from the UI")

        uiState = uiState.copy(masterEnable = true)
        instrumentation.runOnMainSync { service.dispatchFullState(uiState) }
        assertProcessing("master on from the UI")

        // MasterTileService: the tile goes through the service intent, not the UI state.
        ViperService.toggleMaster(context, false)
        assertBypassed("master off from the tile")

        uiState = uiState.copy(masterEnable = true)
        instrumentation.runOnMainSync { service.dispatchFullState(uiState) }
        assertProcessing("master on from the UI after the tile turned it off")

        // An engine started now takes the switch from the service, which last heard it from the UI.
        instrumentation.runOnMainSync {
            service.stopPlayer()
            service.playLocalFile(tone)
        }
        assertProcessing("player restarted after that")
    }

    @Test
    fun capture_withoutShizuku_staysOffAndSaysSo() {
        instrumentation.runOnMainSync { service.refreshCaptureState() }
        // Where Shizuku runs, starting would really capture; this checks the path without it.
        assumeTrue(service.captureState.value.status == CaptureState.Status.SHIZUKU_UNAVAILABLE)

        instrumentation.runOnMainSync { service.startCapture() }
        settle()

        assertEquals(CaptureState.Status.SHIZUKU_UNAVAILABLE, service.captureState.value.status)
        assertNull("no capture DSP without Shizuku", service.probeDriverStatus())
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Where MainViewModel imports kernels and DDC files to. */
    private fun importDir(sub: String) = File(context.getExternalFilesDir(null), sub).apply { mkdirs() }

    private fun installKernel(
        name: String,
        gain: Float,
    ) {
        val file = File(importDir("Kernel"), name)
        writeFloatWav(file, channels = 1, frames = 2048) { frame, _ -> if (frame == 0) gain else 0f }
        files += file
    }

    private fun installDdc(
        name: String,
        gain: Float,
    ) {
        val file = File(importDir("DDC"), "$name.vdc")
        writeVdc(file, gain44100 = gain, gain48000 = gain)
        files += file
    }

    private fun convolverAndDdc(master: Boolean) =
        EffectState(
            masterEnable = master,
            convolver = ConvolverState(enable = true, kernelFile = kernel),
            ddc = DdcState(enable = true, device = ddc),
        )

    /** Attaches a DSP the way an engine does, from the main thread. */
    private fun attachDsp(): NativeDsp =
        NativeDsp(SAMPLE_RATE).also { dsp ->
            ownDsps += dsp
            instrumentation.runOnMainSync { service.attachDsp(dsp) }
        }

    /** Two seconds of a stereo 440 Hz tone, looped by the in-app player. */
    private fun toneFile(): File =
        File(context.cacheDir, "svc-test-$run-tone.wav").also { file ->
            writePcm16Wav(file, channels = 2, frames = 2 * SAMPLE_RATE) { frame, _ ->
                (0.2 * sin(2 * PI * 440 * frame / SAMPLE_RATE)).toFloat()
            }
            files += file
        }

    private fun processedFrames(): Long = service.probeDriverStatus()?.processedFrames ?: 0L

    private fun assertProcessing(what: String) {
        val start = processedFrames()
        awaitTrue("$what: DSP processing") { processedFrames() - start > SAMPLE_RATE / 4 }
    }

    private fun assertBypassed(what: String) {
        settle()
        val start = processedFrames()
        Thread.sleep(1000)
        assertEquals("$what: DSP still processing", start, processedFrames())
    }

    /** Lets the service's main-thread work (attach and toggle coroutines, a buffer in flight) finish. */
    private fun settle() {
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
    }

    private fun awaitTrue(
        what: String,
        timeoutMs: Long = 5000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }
}
