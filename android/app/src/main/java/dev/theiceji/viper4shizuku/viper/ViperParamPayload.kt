package dev.theiceji.viper4shizuku.viper

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ViperParamPayload {
    private const val TYPE_BOOL = 1
    private const val TYPE_INT = 2
    private const val TYPE_FLOAT = 3
    private const val TYPE_FLOAT_ARRAY = 4
    private const val TYPE_BYTES = 5
    private const val TYPE_INT_ARRAY = 6
    private const val NO_INDEX = -1
    private const val HEADER_SIZE = 12

    fun bool(
        value: Boolean,
        index: Int = NO_INDEX,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TYPE_BOOL)
            .putInt(index)
            .putInt(1)
            .put((if (value) 1 else 0).toByte())
            .array()

    fun int(
        value: Int,
        index: Int = NO_INDEX,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE + Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TYPE_INT)
            .putInt(index)
            .putInt(1)
            .putInt(value)
            .array()

    fun float(
        value: Float,
        index: Int = NO_INDEX,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE + Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TYPE_FLOAT)
            .putInt(index)
            .putInt(1)
            .putFloat(value)
            .array()

    fun floatArray(
        values: FloatArray,
        index: Int = NO_INDEX,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE + values.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TYPE_FLOAT_ARRAY)
            .putInt(index)
            .putInt(values.size)
            .also { buf -> values.forEach { buf.putFloat(it) } }
            .array()

    fun bytes(
        values: ByteArray,
        index: Int = NO_INDEX,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE + values.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TYPE_BYTES)
            .putInt(index)
            .putInt(values.size)
            .put(values)
            .array()

    fun intArray(
        values: IntArray,
        index: Int = NO_INDEX,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE + values.size * Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TYPE_INT_ARRAY)
            .putInt(index)
            .putInt(values.size)
            .also { buf -> values.forEach { buf.putInt(it) } }
            .array()

    fun encode(value: ParamValue): ByteArray =
        when (value) {
            is ParamValue.Bool -> bool(value.v, value.index)
            is ParamValue.IntV -> int(value.v, value.index)
            is ParamValue.FloatV -> float(value.v, value.index)
            is ParamValue.Ints -> intArray(value.v, value.index)
            is ParamValue.Floats -> floatArray(value.v, value.index)
            is ParamValue.Bytes -> bytes(value.v, value.index)
        }
}
