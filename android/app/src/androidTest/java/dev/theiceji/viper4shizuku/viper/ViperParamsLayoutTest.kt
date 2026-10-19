package dev.theiceji.viper4shizuku.viper

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.theiceji.viper4shizuku.effect.BoolListPref
import dev.theiceji.viper4shizuku.effect.BoolPref
import dev.theiceji.viper4shizuku.effect.DoubleListPref
import dev.theiceji.viper4shizuku.effect.EFFECT_GROUPS
import dev.theiceji.viper4shizuku.effect.EffectGroup
import dev.theiceji.viper4shizuku.effect.ENABLE_PREF_BY_EFFECT_KEY
import dev.theiceji.viper4shizuku.effect.EffectPref
import dev.theiceji.viper4shizuku.effect.EffectState
import dev.theiceji.viper4shizuku.effect.FloatListPref
import dev.theiceji.viper4shizuku.effect.FloatPref
import dev.theiceji.viper4shizuku.effect.IntListPref
import dev.theiceji.viper4shizuku.effect.IntPref
import dev.theiceji.viper4shizuku.engine.NativeDsp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Serializer <-> C++ layout, checked against the real ViPERDSP build on the device ABI.
 *
 * `sizeof(ViPERParams)` matching [ViperParamsLayout.SIZE] says nothing about field offsets, and a
 * byte round trip through `PARAM_SET_FULL_PARAMS` is a memcpy, so neither can catch a field at
 * the wrong offset. Instead every param-backed field is set two ways: in a full-state blob (the
 * serializer places it by Kotlin offset) and as its own typed param (C++ routes it by param id to
 * the named struct member). If the Kotlin offset is wrong the two engines hold different values
 * and their output differs.
 */
@RunWith(AndroidJUnit4::class)
class ViperParamsLayoutTest {
    @Test
    fun nativeStructSize_matchesKotlinLayout() {
        assertEquals(ViperParamsLayout.SIZE, NativeDsp.paramsSize)
    }

    @Test
    fun fullStateBlob_isAcceptedWhole() {
        val state = buildCases().last().changed
        val blob = ViperParamsSerializer.toByteArray(state)
        NativeDsp(SAMPLE_RATE).use { dsp ->
            assertTrue(dsp.dispatch(ViperParams.PARAM_SET_FULL_PARAMS, fullState(state)))
            assertArrayEquals(blob, dsp.currentParams())
        }
    }

    @Test
    fun fullStateBlob_matchesPerParamDispatch_forEveryField() {
        // A field only proves its offset if changing it changes the output, and many are inert in
        // the default state (manual compressor values while "auto" is on, a reverb with no wet
        // mix). Each field is tried on progressively livelier baselines until it is observable.
        val variants = (0..MAX_VARIANT).map { variant -> buildCases(variant).associateBy { it.name } }
        val failures = mutableListOf<String>()
        val unobservable = mutableListOf<String>()
        var verified = 0

        for (name in variants[0].keys) {
            var outcome: Outcome = Outcome.Unobservable
            for (cases in variants) {
                outcome = check(cases[name] ?: continue)
                if (outcome != Outcome.Unobservable) break
            }
            when (outcome) {
                Outcome.Verified -> verified++
                Outcome.Unobservable -> unobservable += name
                is Outcome.Failed -> failures += outcome.why
            }
        }

        val total = variants[0].size
        Log.i(TAG, "fields: $total, verified: $verified, failed: ${failures.size}, unobservable: ${unobservable.size}")
        unobservable.forEach { Log.i(TAG, "unobservable: $it") }
        failures.forEach { Log.e(TAG, "FAIL $it") }
        assertTrue("${failures.size} field(s) disagree:\n" + failures.joinToString("\n"), failures.isEmpty())
        assertTrue("only $verified of $total fields changed the output", verified >= MIN_VERIFIED)
    }

    private sealed interface Outcome {
        data object Verified : Outcome

        /** The change does not reach the output, so this run says nothing about the offset. */
        data object Unobservable : Outcome

        data class Failed(
            val why: String,
        ) : Outcome
    }

    private val unchangedCache = HashMap<EffectState, FloatArray>()

    private fun check(case: Case): Outcome {
        val viaBlob = render(case.changed)
        var accepted = true
        val viaParam =
            render(case.baseline) { dsp ->
                accepted = dsp.dispatch(case.paramId, ViperParamPayload.encode(case.value))
            }
        val unchanged = unchangedCache.getOrPut(case.baseline) { render(case.baseline) }

        val mismatch = maxDiff(viaBlob, viaParam)
        val effect = maxDiff(viaBlob, unchanged)
        return when {
            !accepted -> Outcome.Failed("${case.name}: typed dispatch rejected ${case.value}")
            mismatch > TOLERANCE -> Outcome.Failed("${case.name}: blob vs param differ by $mismatch (change alone: $effect)")
            effect <= TOLERANCE -> Outcome.Unobservable
            else -> Outcome.Verified
        }
    }

    // --- cases ---------------------------------------------------------------------------------

    private class Case(
        val name: String,
        val paramId: Int,
        /** State without the change; the change arrives as a typed param. */
        val baseline: EffectState,
        /** Same state with the change folded into the full blob. */
        val changed: EffectState,
        val value: ParamValue,
    )

    /**
     * One case per scalar field and per list element, each in a state where its effect is on.
     * [variant] 1 also flips the group's mode switches (auto, no-clip, anti-pop, ...), and 2
     * moves every other field of the group off its default as well.
     */
    private fun buildCases(variant: Int = 0): List<Case> =
        EFFECT_GROUPS.flatMap { group ->
            val base = baseline(group, variant)
            group.prefs
                .filter { it.paramId != -1 }
                .flatMap { casesFor("${group.effectKey}.${it.jsonKey}", it, base) }
        }

    private fun baseline(
        group: EffectGroup,
        variant: Int,
    ): EffectState {
        val enable = ENABLE_PREF_BY_EFFECT_KEY[group.effectKey]
        var state = enable?.let { it.set(EffectState(), true) } ?: EffectState()
        // Switches that turn the effect or a band off stay on, or there is nothing to observe.
        val tunable = group.prefs.filter { it.paramId != -1 && !it.jsonKey.contains("nable") }
        if (variant >= 1) {
            for (pref in tunable) {
                state =
                    when (pref) {
                        is BoolPref -> pref.set(state, !pref.get(state))
                        is BoolListPref -> pref.set(state, pref.get(state).map { !it })
                        else -> state
                    }
            }
        }
        if (variant >= 2) {
            for (pref in tunable) {
                state =
                    when (pref) {
                        is IntPref -> pref.set(state, otherInt(pref.paramId, pref.get(state), pref.range))
                        is FloatPref -> pref.set(state, otherFloat(pref.get(state), pref.range))
                        is IntListPref -> pref.set(state, pref.get(state).map { otherInt(pref.paramId, it, pref.range) })
                        is FloatListPref -> pref.set(state, pref.get(state).map { otherFloat(it, pref.range) })
                        is DoubleListPref -> pref.set(state, pref.get(state).map { otherDouble(it, pref.range) })
                        else -> state
                    }
            }
        }
        return state
    }

    private fun casesFor(
        name: String,
        pref: EffectPref<*>,
        base: EffectState,
    ): List<Case> =
        when (pref) {
            is BoolPref -> {
                val v = !pref.get(base)
                listOf(Case(name, pref.paramId, base, pref.set(base, v), ParamValue.Bool(v)))
            }

            is IntPref -> {
                val v = otherInt(pref.paramId, pref.get(base), pref.range)
                listOf(Case(name, pref.paramId, base, pref.set(base, v), ParamValue.IntV(v)))
            }

            is FloatPref -> {
                val v = otherFloat(pref.get(base), pref.range)
                listOf(Case(name, pref.paramId, base, pref.set(base, v), ParamValue.FloatV(v)))
            }

            is BoolListPref ->
                pref.get(base).indices.map { i ->
                    val list = pref.get(base)
                    val v = !list[i]
                    Case("$name[$i]", pref.paramId, base, pref.set(base, list.with(i, v)), ParamValue.Bool(v, i))
                }

            is IntListPref ->
                pref.get(base).indices.map { i ->
                    val list = pref.get(base)
                    val v = otherInt(pref.paramId, list[i], pref.range)
                    Case("$name[$i]", pref.paramId, base, pref.set(base, list.with(i, v)), ParamValue.IntV(v, i))
                }

            is FloatListPref ->
                pref.get(base).indices.map { i ->
                    val list = pref.get(base)
                    val v = otherFloat(list[i], pref.range)
                    Case("$name[$i]", pref.paramId, base, pref.set(base, list.with(i, v)), ParamValue.FloatV(v, i))
                }

            // Sent whole, like MainViewModel.applyPref does.
            is DoubleListPref ->
                pref.get(base).indices.map { i ->
                    val list = pref.get(base)
                    val changed = list.with(i, otherDouble(list[i], pref.range))
                    Case("$name[$i]", pref.paramId, base, pref.set(base, changed), ParamValue.Floats(pref.toFloatArray(changed)))
                }

            // Strings (DDC device, convolver kernel) travel through the bulk side channels.
            else -> emptyList()
        }

    private fun otherInt(
        paramId: Int,
        current: Int,
        range: IntRange?,
    ): Int =
        when {
            // Band counts must stay valid UI choices.
            paramId == ViperParams.PARAM_EQUALIZER_BAND_COUNT -> if (current == 15) 10 else 15
            paramId == ViperParams.PARAM_DYNAMIC_EQ_BAND_COUNT -> if (current > 1) current - 1 else current + 1
            range != null -> ((range.first + range.last) / 2).let { if (it != current) it else range.first }
            else -> current + 1
        }

    private fun otherFloat(
        current: Float,
        range: ClosedFloatingPointRange<Float>?,
    ): Float {
        if (range == null) return current * 1.5f + 0.1f
        val span = range.endInclusive - range.start
        return listOf(0.3f, 0.7f, 0.5f)
            .map { range.start + span * it }
            .first { abs(it - current) > span * 1e-3f }
    }

    private fun otherDouble(
        current: Double,
        range: ClosedFloatingPointRange<Double>?,
    ): Double =
        if (range == null) {
            current + 3.0
        } else {
            otherFloat(current.toFloat(), range.start.toFloat()..range.endInclusive.toFloat()).toDouble()
        }

    private fun <E> List<E>.with(
        index: Int,
        value: E,
    ): List<E> = toMutableList().also { it[index] = value }

    // --- audio ---------------------------------------------------------------------------------

    private fun fullState(state: EffectState): ByteArray =
        ViperParamPayload.encode(ParamValue.Bytes(ViperParamsSerializer.toByteArray(state)))

    /** Applies [state] as a full blob, then [extra], and processes the test signal. */
    private fun render(
        state: EffectState,
        extra: (NativeDsp) -> Unit = {},
    ): FloatArray =
        NativeDsp(SAMPLE_RATE).use { dsp ->
            check(dsp.dispatch(ViperParams.PARAM_SET_FULL_PARAMS, fullState(state))) { "full blob rejected" }
            extra(dsp)
            dsp.reset()
            val buf = INPUT.copyOf()
            var frame = 0
            while (frame < FRAMES) {
                val n = minOf(CHUNK, FRAMES - frame)
                dsp.process(buf, n, frame * 2)
                frame += n
            }
            buf
        }

    private fun maxDiff(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        var d = 0f
        for (i in a.indices) d = max(d, abs(a[i] - b[i]))
        return d
    }

    private companion object {
        const val TAG = "ViperParamsLayoutTest"
        const val SAMPLE_RATE = 48_000
        const val FRAMES = SAMPLE_RATE
        const val CHUNK = 1024
        const val TOLERANCE = 1e-5f
        const val MAX_VARIANT = 2

        /**
         * Floor so the test cannot pass by observing nothing. 166 of 217 fields were observable
         * with ViPERDSP 6fc1f1b; the rest (convolver/DDC need a kernel file, some compressor
         * bands never engage on this signal) are logged as unobservable.
         */
        const val MIN_VERIFIED = 150

        /**
         * One second of deterministic stereo: tones from 60 Hz to 12 kHz that differ between the
         * channels, light noise, and a 4 Hz level step so dynamics processors have work to do.
         */
        val INPUT: FloatArray =
            FloatArray(FRAMES * 2).also { buf ->
                var seed = 0x5EEDL
                for (i in 0 until FRAMES) {
                    val t = i.toDouble() / SAMPLE_RATE
                    seed = seed * 6364136223846793005L + 1442695040888963407L
                    val noise = ((seed ushr 33).toDouble() / (1L shl 31) * 2.0 - 1.0) * 0.02
                    val level = if ((t * 4).toInt() % 2 == 0) 1.0 else 0.25
                    val l =
                        0.20 * sin(2 * PI * 60 * t) + 0.12 * sin(2 * PI * 440 * t) +
                            0.08 * sin(2 * PI * 3000 * t) + 0.05 * sin(2 * PI * 9000 * t)
                    val r =
                        0.15 * sin(2 * PI * 60 * t + 0.5) + 0.10 * sin(2 * PI * 523 * t) +
                            0.08 * sin(2 * PI * 3000 * t + 1.0) + 0.05 * sin(2 * PI * 12000 * t)
                    buf[i * 2] = ((l + noise) * level).toFloat()
                    buf[i * 2 + 1] = ((r + noise * 0.7) * level).toFloat()
                }
            }
    }
}
