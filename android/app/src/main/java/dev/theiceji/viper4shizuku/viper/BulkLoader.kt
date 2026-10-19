package dev.theiceji.viper4shizuku.viper

import dev.theiceji.viper4shizuku.utils.FileLogger
import dev.theiceji.viper4shizuku.utils.WavDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Streams the inputs that do not fit in ViPERParams into every DSP on [transport]: the convolver
 * kernel (a WAV in [kernelDir], sent as prepare / set-buffer chunks / commit, which the DSP checks
 * against a CRC) and the DDC filter (a .vdc in [ddcDir], one message holding the 44.1 kHz then the
 * 48 kHz biquad sections).
 *
 * Loads are serialised. A DSP has one staging buffer for a kernel, so two streams interleaving
 * their chunks (playback attaching a DSP while the user picks a kernel) would make both commits
 * fail the CRC and leave the old kernel, or none.
 */
class BulkLoader(
    private val transport: ViperTransport,
    private val kernelDir: File,
    private val ddcDir: File,
) {
    private class DecodedKernel(
        val samples: FloatArray,
        val channels: Int,
        val crc: Int,
    )

    private val lock = Any()

    // Keyed by path, size and mtime, so a file re-imported under the same name is decoded again.
    private val kernelCache =
        object : LinkedHashMap<String, DecodedKernel>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DecodedKernel>?) = size > 3
        }

    /**
     * Streams kernel [fileName] from [kernelDir]; an empty name unloads the kernel. Skipped when
     * that name was already streamed, unless [force]. Returns whether anything was sent.
     */
    fun applyConvolverKernel(
        fileName: String,
        force: Boolean = false,
    ): Boolean =
        synchronized(lock) {
            if (!transport.shouldStream(BulkKind.CONVOLVER, fileName, force)) return false
            if (fileName.isEmpty()) {
                sendInts(ViperParams.PARAM_CONVOLVER_PREPARE_BUFFER, 0, 0, 1)
                transport.markStreamed(BulkKind.CONVOLVER, fileName)
                return true
            }
            val file = File(kernelDir, fileName)
            if (!file.isFile) {
                FileLogger.w(TAG, "Kernel file missing: $fileName")
                return false
            }
            try {
                val kernel = decodeKernel(file)
                val total = kernel.samples.size
                sendInts(ViperParams.PARAM_CONVOLVER_PREPARE_BUFFER, total, kernel.channels, 0)
                var offset = 0
                var chunks = 0
                while (offset < total) {
                    val n = minOf(total - offset, MAX_FLOATS_PER_CHUNK)
                    transport.set(
                        ViperParams.PARAM_CONVOLVER_SET_BUFFER,
                        ParamValue.Floats(kernel.samples.copyOfRange(offset, offset + n)),
                    )
                    offset += n
                    chunks++
                }
                sendInts(ViperParams.PARAM_CONVOLVER_COMMIT_BUFFER, total, kernel.crc, kernelId(fileName))
                FileLogger.i(TAG, "Kernel streamed: $fileName chunks=$chunks crc=0x${kernel.crc.toUInt().toString(16)}")
                transport.markStreamed(BulkKind.CONVOLVER, fileName)
                true
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to stream kernel: $fileName", e)
                false
            }
        }

    /**
     * Sends DDC device [name] (`<name>.vdc` in [ddcDir]); an empty name clears the filter. Skipped
     * when that name was already sent, unless [force]. Returns whether anything was sent.
     */
    fun applyDdcDevice(
        name: String,
        force: Boolean = false,
    ): Boolean =
        synchronized(lock) {
            if (!transport.shouldStream(BulkKind.DDC, name, force)) return false
            if (name.isEmpty()) {
                transport.set(ViperParams.PARAM_DDC_COEFFICIENTS, ParamValue.Floats(FloatArray(0)))
                transport.markStreamed(BulkKind.DDC, name)
                return true
            }
            val file = File(ddcDir, "$name.vdc")
            if (!file.isFile) {
                FileLogger.w(TAG, "DDC file missing: $name")
                return false
            }
            val (sec44100, sec48000) = parseVdc(file) ?: return false
            val coeffs = FloatArray((sec44100.size + sec48000.size) * 5)
            var i = 0
            for (s in sec44100) for (v in s) coeffs[i++] = v
            for (s in sec48000) for (v in s) coeffs[i++] = v
            transport.set(ViperParams.PARAM_DDC_COEFFICIENTS, ParamValue.Floats(coeffs))
            transport.markStreamed(BulkKind.DDC, name)
            true
        }

    private fun decodeKernel(file: File): DecodedKernel {
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        kernelCache[key]?.let { return it }
        val wav = WavDecoder.decode(file.readBytes())
        val bytes =
            ByteBuffer
                .allocate(wav.samples.size * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .also { for (f in wav.samples) it.putFloat(f) }
                .array()
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        FileLogger.i(TAG, "Kernel decoded: ${file.name} samples=${wav.samples.size} ch=${wav.channels}")
        return DecodedKernel(wav.samples, wav.channels, crc).also { kernelCache[key] = it }
    }

    private fun sendInts(
        param: Int,
        a: Int,
        b: Int,
        c: Int,
    ) = transport.set(param, ParamValue.Ints(intArrayOf(a, b, c)))

    companion object {
        private const val TAG = "BulkLoader"

        /** Upstream streamed under the effect HAL's parameter size limit; the DSP side is unchanged. */
        const val MAX_FLOATS_PER_CHUNK = 2046

        /** Id the DSP reports for a kernel loaded from [fileName]. */
        fun kernelId(fileName: String): Int = fileName.hashCode()

        /**
         * Parses a ViPER DDC `.vdc` file: `SR_44100:` and `SR_48000:` lines of comma separated
         * biquad sections (b0, b1, b2, a1, a2), the same number for both rates.
         */
        fun parseVdc(file: File): Pair<List<FloatArray>, List<FloatArray>>? {
            try {
                var coeffs44100: FloatArray? = null
                var coeffs48000: FloatArray? = null
                for (line in file.readLines()) {
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("SR_44100:") -> coeffs44100 = parseFloats(trimmed.removePrefix("SR_44100:"))
                        trimmed.startsWith("SR_48000:") -> coeffs48000 = parseFloats(trimmed.removePrefix("SR_48000:"))
                    }
                }
                val a = coeffs44100
                val b = coeffs48000
                if (a == null || b == null || a.isEmpty() || a.size != b.size || a.size % 5 != 0) {
                    FileLogger.w(TAG, "DDC coefficient parse failure: ${file.name} 44100=${a?.size} 48000=${b?.size}")
                    return null
                }
                return a.toList().chunked(5).map { it.toFloatArray() } to b.toList().chunked(5).map { it.toFloatArray() }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to parse DDC: ${file.name}", e)
                return null
            }
        }

        private fun parseFloats(csv: String): FloatArray = csv.split(",").map { it.trim().toFloat() }.toFloatArray()
    }
}
