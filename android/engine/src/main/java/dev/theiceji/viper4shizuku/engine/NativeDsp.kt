package dev.theiceji.viper4shizuku.engine

import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One ViPERDSP instance running in the ViPER4Shizuku process.
 *
 * Audio is interleaved stereo. [dispatch] takes the same param id + payload bytes that the
 * upstream app sent to the effect driver (see `ViperParamPayload`), so the UI, presets and
 * serializer stay unchanged. All calls are thread-safe; process() and dispatch() share a lock
 * exactly like the effect framework serialises command() and process().
 */
class NativeDsp(
    sampleRate: Int,
) : AutoCloseable {
    @Volatile
    private var handle: Long = NativeDspJni.nativeCreate(sampleRate)

    // Calls from other threads (the audio thread, kernel streaming) can be in flight when close()
    // runs. It waits for them, and once the engine is freed every call is a no-op.
    private val lifecycle = ReentrantReadWriteLock()

    val isReleased: Boolean get() = handle == 0L

    val sampleRate: Int get() = withHandle(0) { NativeDspJni.nativeGetSampleRate(it) }

    val processedFrames: Long get() = withHandle(0L) { NativeDspJni.nativeGetProcessedFrames(it) }

    val kernelId: Int get() = withHandle(0) { NativeDspJni.nativeGetKernelId(it) }

    fun setSampleRate(rate: Int) = withHandle(Unit) { NativeDspJni.nativeSetSampleRate(it, rate) }

    fun reset() = withHandle(Unit) { NativeDspJni.nativeReset(it) }

    fun dispatch(
        param: Int,
        payload: ByteArray,
    ): Boolean = withHandle(false) { NativeDspJni.nativeDispatch(it, param, payload) }

    /** Processes [frames] stereo frames of [samples] in place, starting at [offset] samples. */
    fun process(
        samples: FloatArray,
        frames: Int,
        offset: Int = 0,
    ) = withHandle(Unit) { NativeDspJni.nativeProcessFloatArray(it, samples, offset, frames) }

    /**
     * Processes between direct buffers. [encoding] is `AudioFormat.ENCODING_PCM_FLOAT` or
     * `AudioFormat.ENCODING_PCM_16BIT`; offsets are in bytes.
     */
    fun process(
        input: ByteBuffer,
        inputOffset: Int,
        output: ByteBuffer,
        outputOffset: Int,
        frames: Int,
        encoding: Int,
    ): Boolean {
        require(input.isDirect && output.isDirect) { "direct buffers required" }
        return withHandle(false) {
            NativeDspJni.nativeProcessDirect(it, input, inputOffset, output, outputOffset, frames, encoding)
        }
    }

    /** Raw `viper::ViPERParams` bytes last applied through `PARAM_SET_FULL_PARAMS`. */
    fun currentParams(): ByteArray = withHandle(ByteArray(0)) { NativeDspJni.nativeGetCurrentParams(it) }

    override fun close() {
        lifecycle.write {
            val h = handle
            handle = 0L
            if (h != 0L) NativeDspJni.nativeRelease(h)
        }
    }

    private inline fun <T> withHandle(
        fallback: T,
        block: (Long) -> T,
    ): T =
        lifecycle.read {
            val h = handle
            if (h == 0L) fallback else block(h)
        }

    companion object {
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4

        val architecture: String get() = NativeDspJni.nativeGetArchitecture()
        val version: String get() = NativeDspJni.nativeGetVersion()

        /** sizeof(viper::ViPERParams); must equal ViperParamsLayout.TOTAL_SIZE in the app. */
        val paramsSize: Int get() = NativeDspJni.nativeGetParamsSize()
    }
}

/** JNI entry points, registered by class name in viper4shizuku_jni.cpp. */
internal object NativeDspJni {
    init {
        System.loadLibrary("viper4shizuku_dsp")
    }

    @JvmStatic external fun nativeCreate(sampleRate: Int): Long

    @JvmStatic external fun nativeRelease(handle: Long)

    @JvmStatic external fun nativeSetSampleRate(
        handle: Long,
        sampleRate: Int,
    )

    @JvmStatic external fun nativeReset(handle: Long)

    @JvmStatic external fun nativeDispatch(
        handle: Long,
        param: Int,
        payload: ByteArray,
    ): Boolean

    @JvmStatic external fun nativeProcessFloatArray(
        handle: Long,
        samples: FloatArray,
        offset: Int,
        frames: Int,
    )

    @JvmStatic external fun nativeProcessDirect(
        handle: Long,
        input: ByteBuffer,
        inputOffset: Int,
        output: ByteBuffer,
        outputOffset: Int,
        frames: Int,
        encoding: Int,
    ): Boolean

    @JvmStatic external fun nativeGetSampleRate(handle: Long): Int

    @JvmStatic external fun nativeGetProcessedFrames(handle: Long): Long

    @JvmStatic external fun nativeGetKernelId(handle: Long): Int

    @JvmStatic external fun nativeGetCurrentParams(handle: Long): ByteArray

    @JvmStatic external fun nativeGetParamsSize(): Int

    @JvmStatic external fun nativeGetArchitecture(): String

    @JvmStatic external fun nativeGetVersion(): String

}
