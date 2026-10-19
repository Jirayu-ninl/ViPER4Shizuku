package dev.theiceji.viper4shizuku.viper

import dev.theiceji.viper4shizuku.effect.EffectState
import dev.theiceji.viper4shizuku.engine.NativeDsp
import dev.theiceji.viper4shizuku.utils.FileLogger
import java.util.concurrent.ConcurrentHashMap

class LocalTransport(
    private val dsps: () -> List<NativeDsp>,
) : ViperTransport {
    override var bulkSideChannels: ((ddcDevice: String, convolverKernel: String, force: Boolean) -> Unit)? = null

    private val bulkKeys = ConcurrentHashMap<BulkKind, String>()

    override fun set(
        param: Int,
        value: ParamValue,
    ) {
        val payload = ViperParamPayload.encode(value)
        dsps().forEach { it.dispatch(param, payload) }
    }

    override fun pushFullState(state: EffectState) {
        val snapshot = ParamValue.Bytes(ViperParamsSerializer.toByteArray(state))
        val payload = ViperParamPayload.encode(snapshot)
        dsps().forEach {
            val snapshotApplied = it.dispatch(ViperParams.PARAM_SET_FULL_PARAMS, payload)
            if (!snapshotApplied) {
                FileLogger.w("LocalTransport", "Full snapshot dispatch failed")
            }
        }
        val hasDdc = state.ddc.enable && state.ddc.device.isNotEmpty()
        val hasKernel = state.convolver.enable && state.convolver.kernelFile.isNotEmpty()
        if (hasDdc || hasKernel) {
            bulkSideChannels?.invoke(
                if (hasDdc) state.ddc.device else "",
                if (hasKernel) state.convolver.kernelFile else "",
                true,
            )
        }
    }

    override fun probeStatus(): DriverStatus? {
        val dsp = dsps().firstOrNull() ?: return null
        return DriverStatus(
            enabled = true,
            sampleRate = dsp.sampleRate,
            processedFrames = dsp.processedFrames,
            kernelId = dsp.kernelId,
            versionCode = 0,
            versionName = NativeDsp.version,
            arch = NativeDsp.architecture,
        )
    }

    override fun shouldStream(
        kind: BulkKind,
        key: String,
        force: Boolean,
    ): Boolean = force || bulkKeys[kind] != key

    override fun markStreamed(
        kind: BulkKind,
        key: String,
    ) {
        bulkKeys[kind] = key
    }

    fun invalidateBulk() {
        bulkKeys.clear()
    }
}
