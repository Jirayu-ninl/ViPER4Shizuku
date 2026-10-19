package dev.theiceji.viper4shizuku.viper

import dev.theiceji.viper4shizuku.effect.EffectState

sealed interface ParamValue {
    val index: Int

    data class Bool(
        val v: Boolean,
        override val index: Int = -1,
    ) : ParamValue

    data class IntV(
        val v: Int,
        override val index: Int = -1,
    ) : ParamValue

    data class FloatV(
        val v: Float,
        override val index: Int = -1,
    ) : ParamValue

    data class Ints(
        val v: IntArray,
        override val index: Int = -1,
    ) : ParamValue {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Ints

            if (index != other.index) return false
            if (!v.contentEquals(other.v)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + v.contentHashCode()
            return result
        }
    }

    data class Floats(
        val v: FloatArray,
        override val index: Int = -1,
    ) : ParamValue {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Floats

            if (index != other.index) return false
            if (!v.contentEquals(other.v)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + v.contentHashCode()
            return result
        }
    }

    data class Bytes(
        val v: ByteArray,
        override val index: Int = -1,
    ) : ParamValue {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Bytes

            if (index != other.index) return false
            if (!v.contentEquals(other.v)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + v.contentHashCode()
            return result
        }
    }
}

enum class BulkKind {
    CONVOLVER,
    DDC,
}

data class DriverStatus(
    val enabled: Boolean,
    val sampleRate: Int,
    val processedFrames: Long,
    val kernelId: Int,
    val versionCode: Int,
    val versionName: String,
    val arch: String,
)

interface ViperTransport {
    var bulkSideChannels: ((ddcDevice: String, convolverKernel: String, force: Boolean) -> Unit)?

    fun set(
        param: Int,
        value: ParamValue,
    )

    fun pushFullState(state: EffectState)

    fun probeStatus(): DriverStatus?

    fun shouldStream(
        kind: BulkKind,
        key: String,
        force: Boolean,
    ): Boolean

    fun markStreamed(
        kind: BulkKind,
        key: String,
    )
}
