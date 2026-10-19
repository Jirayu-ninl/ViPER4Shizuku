package dev.theiceji.viper4shizuku.effect

import dev.theiceji.viper4shizuku.R

object BuiltinPresets {
    data class BuiltinEqPreset(
        val key: String,
        val nameRes: Int,
        val bands10: String,
        val bands15: String,
        val bands25: String,
        val bands31: String,
    )

    @Suppress("ktlint:standard:max-line-length")
    val BUILTIN_EQ_PRESETS: List<BuiltinEqPreset> =
        listOf(
            BuiltinEqPreset(
                key = "eq_preset_acoustic",
                nameRes = R.string.eq_preset_acoustic,
                bands10 = "4.5;4.5;3.5;1.2;1.0;0.5;1.4;1.75;3.5;2.5;",
                bands15 = "4.5;4.5;4.5;4.0;2.5;1.0;1.0;1.0;0.5;1.0;1.5;2.0;3.0;3.0;2.5;",
                bands25 = "4.5;4.5;4.5;4.5;4.0;4.0;3.5;2.5;1.0;1.0;1.0;1.0;0.5;0.5;1.0;1.0;1.5;1.5;2.0;2.5;3.5;3.0;3.0;2.5;2.5;",
                bands31 = "4.5;4.5;4.5;4.5;4.5;4.5;4.0;4.0;3.5;2.5;2.0;1.0;1.0;1.0;1.0;1.0;0.5;0.5;1.0;1.0;1.5;1.5;1.5;2.0;2.5;3.0;3.5;3.0;3.0;2.5;2.5;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_bass_booster",
                nameRes = R.string.eq_preset_bass_booster,
                bands10 = "6.0;4.0;2.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands15 = "6.0;5.5;4.0;2.5;1.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands25 = "6.0;6.0;5.5;4.5;3.5;2.5;2.0;1.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands31 = "6.0;6.0;6.0;5.5;4.5;4.0;3.5;2.5;2.0;1.5;0.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_bass_reducer",
                nameRes = R.string.eq_preset_bass_reducer,
                bands10 = "-6.0;-4.0;-2.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands15 = "-6.0;-5.5;-4.0;-2.5;-1.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands25 = "-6.0;-6.0;-5.5;-4.5;-3.5;-2.5;-2.0;-1.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands31 = "-6.0;-6.0;-6.0;-5.5;-4.5;-4.0;-3.5;-2.5;-2.0;-1.5;-0.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_classical",
                nameRes = R.string.eq_preset_classical,
                bands10 = "0.0;0.0;0.0;0.0;0.0;0.0;-3.0;-3.0;-3.0;-5.0;",
                bands15 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;-2.0;-3.0;-3.0;-3.0;-3.5;-5.0;",
                bands25 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;-1.0;-2.0;-3.0;-3.0;-3.0;-3.0;-3.0;-3.5;-4.5;-5.0;-5.0;",
                bands31 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;-1.0;-2.0;-3.0;-3.0;-3.0;-3.0;-3.0;-3.0;-3.0;-3.5;-4.5;-5.0;-5.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_deep",
                nameRes = R.string.eq_preset_deep,
                bands10 = "3.0;2.0;1.0;0.5;0.5;0.0;-1.0;-2.0;-3.0;-3.5;",
                bands15 = "3.0;2.5;2.0;1.5;1.0;0.5;0.5;0.5;0.0;-0.5;-1.5;-2.0;-2.5;-3.0;-3.5;",
                bands25 = "3.0;3.0;2.5;2.5;1.5;1.5;1.0;1.0;0.5;0.5;0.5;0.5;0.0;0.0;-0.5;-0.5;-1.5;-1.5;-2.0;-2.5;-3.0;-3.0;-3.5;-3.5;-3.5;",
                bands31 = "3.0;3.0;3.0;2.5;2.5;2.0;1.5;1.5;1.0;1.0;0.5;0.5;0.5;0.5;0.5;0.5;0.0;0.0;-0.5;-0.5;-1.0;-1.5;-1.5;-2.0;-2.5;-2.5;-3.0;-3.0;-3.5;-3.5;-3.5;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_flat",
                nameRes = R.string.eq_preset_flat,
                bands10 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands15 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands25 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands31 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_rnb",
                nameRes = R.string.eq_preset_rnb,
                bands10 = "3.0;6.0;4.0;1.0;-1.0;-0.5;1.0;1.5;2.5;3.0;",
                bands15 = "3.0;4.0;6.0;4.5;3.0;1.0;-0.5;-1.0;-0.5;0.5;1.0;1.5;2.0;2.5;3.0;",
                bands25 = "3.0;3.0;4.0;5.0;5.5;4.5;4.0;3.0;1.0;0.5;-0.5;-1.0;-0.5;-0.5;0.0;0.5;1.0;1.5;1.5;2.0;2.5;2.5;3.0;3.0;3.0;",
                bands31 = "3.0;3.0;3.0;4.0;5.0;6.0;5.5;4.5;4.0;3.0;2.0;1.0;0.5;-0.5;-1.0;-1.0;-0.5;-0.5;0.0;0.5;1.0;1.0;1.5;1.5;2.0;2.0;2.5;2.5;3.0;3.0;3.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_rock",
                nameRes = R.string.eq_preset_rock,
                bands10 = "4.0;3.0;1.0;0.0;-0.5;0.0;1.5;2.5;3.5;4.0;",
                bands15 = "4.0;3.5;3.0;1.5;0.5;0.0;-0.5;-0.5;0.0;1.0;2.0;2.5;3.0;3.5;4.0;",
                bands25 = "4.0;4.0;3.5;3.5;2.5;1.5;1.0;0.5;0.0;0.0;-0.5;-0.5;0.0;0.0;0.5;1.0;2.0;2.0;2.5;3.0;3.5;3.5;4.0;4.0;4.0;",
                bands31 = "4.0;4.0;4.0;3.5;3.5;3.0;2.5;1.5;1.0;0.5;0.5;0.0;0.0;-0.5;-0.5;-0.5;0.0;0.0;0.5;1.0;1.5;2.0;2.0;2.5;3.0;3.0;3.5;3.5;4.0;4.0;4.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_small_speakers",
                nameRes = R.string.eq_preset_small_speakers,
                bands10 = "3.0;2.0;1.5;1.0;0.5;-0.5;-1.5;-2.0;-3.0;-3.5;",
                bands15 = "3.0;2.5;2.0;1.5;1.5;1.0;0.5;0.0;-0.5;-1.0;-1.5;-2.0;-2.5;-3.0;-3.5;",
                bands25 = "3.0;3.0;2.5;2.5;2.0;1.5;1.5;1.5;1.0;1.0;0.5;0.5;0.0;-0.5;-1.0;-1.0;-1.5;-2.0;-2.0;-2.5;-3.0;-3.0;-3.5;-3.5;-3.5;",
                bands31 = "3.0;3.0;3.0;2.5;2.5;2.0;2.0;1.5;1.5;1.5;1.0;1.0;1.0;0.5;0.5;0.0;0.0;-0.5;-1.0;-1.0;-1.5;-1.5;-2.0;-2.0;-2.5;-2.5;-3.0;-3.0;-3.5;-3.5;-3.5;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_treble_booster",
                nameRes = R.string.eq_preset_treble_booster,
                bands10 = "0.0;0.0;0.0;0.0;0.0;1.0;2.0;3.0;4.0;5.0;",
                bands15 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.5;1.0;1.5;2.5;3.0;3.5;4.5;5.0;",
                bands25 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.5;1.0;1.5;1.5;2.5;2.5;3.0;3.5;4.0;4.5;4.5;5.0;5.0;",
                bands31 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.5;0.5;1.0;1.5;1.5;2.0;2.5;2.5;3.0;3.5;3.5;4.0;4.5;4.5;5.0;5.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_treble_reducer",
                nameRes = R.string.eq_preset_treble_reducer,
                bands10 = "0.0;0.0;0.0;0.0;0.0;-1.0;-2.0;-3.0;-4.0;-5.0;",
                bands15 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;-0.5;-1.0;-1.5;-2.5;-3.0;-3.5;-4.5;-5.0;",
                bands25 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;-0.5;-1.0;-1.5;-1.5;-2.5;-2.5;-3.0;-3.5;-4.0;-4.5;-4.5;-5.0;-5.0;",
                bands31 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;-0.5;-0.5;-1.0;-1.5;-1.5;-2.0;-2.5;-2.5;-3.0;-3.5;-3.5;-4.0;-4.5;-4.5;-5.0;-5.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_vocal_booster",
                nameRes = R.string.eq_preset_vocal_booster,
                bands10 = "-1.0;-0.5;0.0;1.5;3.0;3.0;2.0;1.0;0.0;-1.0;",
                bands15 = "-1.0;-1.0;-0.5;0.0;0.5;1.5;2.5;3.0;3.0;2.5;1.5;1.0;0.5;-0.5;-1.0;",
                bands25 = "-1.0;-1.0;-1.0;-0.5;-0.5;0.0;0.0;0.5;1.5;2.0;2.5;3.0;3.0;3.0;2.5;2.5;1.5;1.5;1.0;0.5;0.0;-0.5;-0.5;-1.0;-1.0;",
                bands31 = "-1.0;-1.0;-1.0;-1.0;-0.5;-0.5;-0.5;0.0;0.0;0.5;1.0;1.5;2.0;2.5;3.0;3.0;3.0;3.0;2.5;2.5;2.0;1.5;1.5;1.0;0.5;0.5;0.0;-0.5;-0.5;-1.0;-1.0;",
            ),
        )

    val EQ_PRESET_NAME_RES: Map<String, Int> =
        BUILTIN_EQ_PRESETS.associate { it.key to it.nameRes }

    data class BuiltinDsPreset(
        val key: String,
        val nameRes: Int,
        val xLow: Int,
        val xHigh: Int,
        val yLow: Int,
        val yHigh: Int,
        val sideGainLow: Float,
        val sideGainHigh: Float,
    )

    val BUILTIN_DS_PRESETS: List<BuiltinDsPreset> =
        listOf(
            BuiltinDsPreset(
                key = "ds_device_extreme_headphone_v2",
                nameRes = R.string.ds_device_extreme_headphone_v2,
                xLow = 140,
                xHigh = 6200,
                yLow = 40,
                yHigh = 60,
                sideGainLow = 0.10f,
                sideGainHigh = 0.80f,
            ),
            BuiltinDsPreset(
                key = "ds_device_high_end_headphone_v2",
                nameRes = R.string.ds_device_high_end_headphone_v2,
                xLow = 180,
                xHigh = 5800,
                yLow = 55,
                yHigh = 80,
                sideGainLow = 0.10f,
                sideGainHigh = 0.70f,
            ),
            BuiltinDsPreset(
                key = "ds_device_common_headphone_v2",
                nameRes = R.string.ds_device_common_headphone_v2,
                xLow = 300,
                xHigh = 5600,
                yLow = 60,
                yHigh = 105,
                sideGainLow = 0.10f,
                sideGainHigh = 0.50f,
            ),
            BuiltinDsPreset(
                key = "ds_device_low_end_headphone_v2",
                nameRes = R.string.ds_device_low_end_headphone_v2,
                xLow = 600,
                xHigh = 5400,
                yLow = 60,
                yHigh = 105,
                sideGainLow = 0.10f,
                sideGainHigh = 0.20f,
            ),
            BuiltinDsPreset(
                key = "ds_device_common_earphone_v2",
                nameRes = R.string.ds_device_common_earphone_v2,
                xLow = 100,
                xHigh = 5600,
                yLow = 40,
                yHigh = 80,
                sideGainLow = 0.50f,
                sideGainHigh = 0.50f,
            ),
            BuiltinDsPreset(
                key = "ds_device_extreme_headphone_v1",
                nameRes = R.string.ds_device_extreme_headphone_v1,
                xLow = 1200,
                xHigh = 6200,
                yLow = 40,
                yHigh = 80,
                sideGainLow = 0.0f,
                sideGainHigh = 0.20f,
            ),
            BuiltinDsPreset(
                key = "ds_device_high_end_headphone_v1",
                nameRes = R.string.ds_device_high_end_headphone_v1,
                xLow = 1000,
                xHigh = 6200,
                yLow = 40,
                yHigh = 80,
                sideGainLow = 0.0f,
                sideGainHigh = 0.10f,
            ),
            BuiltinDsPreset(
                key = "ds_device_common_headphone_v1",
                nameRes = R.string.ds_device_common_headphone_v1,
                xLow = 800,
                xHigh = 6200,
                yLow = 40,
                yHigh = 80,
                sideGainLow = 0.10f,
                sideGainHigh = 0.0f,
            ),
            BuiltinDsPreset(
                key = "ds_device_common_earphone_v1",
                nameRes = R.string.ds_device_common_earphone_v1,
                xLow = 400,
                xHigh = 6200,
                yLow = 40,
                yHigh = 80,
                sideGainLow = 0.10f,
                sideGainHigh = 0.0f,
            ),
        )

    val DS_PRESET_NAME_RES: Map<String, Int> =
        BUILTIN_DS_PRESETS.associate { it.key to it.nameRes }

    val EQ_GRAPH_LABELS_10 =
        listOf(
            "31",
            "62",
            "125",
            "250",
            "500",
            "1k",
            "2k",
            "4k",
            "8k",
            "16k",
        )
    val EQ_GRAPH_LABELS_15 =
        listOf(
            "25",
            "40",
            "63",
            "100",
            "160",
            "250",
            "400",
            "630",
            "1k",
            "1.6k",
            "2.5k",
            "4k",
            "6.3k",
            "10k",
            "16k",
        )
    val EQ_GRAPH_LABELS_25 =
        listOf(
            "20",
            "31",
            "40",
            "50",
            "80",
            "100",
            "125",
            "160",
            "250",
            "315",
            "400",
            "500",
            "800",
            "1k",
            "1.25k",
            "1.6k",
            "2.5k",
            "3.15k",
            "4k",
            "5k",
            "8k",
            "10k",
            "12.5k",
            "16k",
            "20k",
        )
    val EQ_GRAPH_LABELS_31 =
        listOf(
            "20",
            "25",
            "31",
            "40",
            "50",
            "63",
            "80",
            "100",
            "125",
            "160",
            "200",
            "250",
            "315",
            "400",
            "500",
            "630",
            "800",
            "1k",
            "1.25k",
            "1.6k",
            "2k",
            "2.5k",
            "3.15k",
            "4k",
            "5k",
            "6.3k",
            "8k",
            "10k",
            "12.5k",
            "16k",
            "20k",
        )

    val EQ_BAND_LABELS_10 = EQ_GRAPH_LABELS_10.map { "${it}Hz" }
    val EQ_BAND_LABELS_15 = EQ_GRAPH_LABELS_15.map { "${it}Hz" }
    val EQ_BAND_LABELS_25 = EQ_GRAPH_LABELS_25.map { "${it}Hz" }
    val EQ_BAND_LABELS_31 = EQ_GRAPH_LABELS_31.map { "${it}Hz" }

    fun eqBandLabelsForCount(count: Int): List<String> =
        when (count) {
            15 -> EQ_BAND_LABELS_15
            25 -> EQ_BAND_LABELS_25
            31 -> EQ_BAND_LABELS_31
            else -> EQ_BAND_LABELS_10
        }

    fun eqGraphLabelsForCount(count: Int): List<String> =
        when (count) {
            15 -> EQ_GRAPH_LABELS_15
            25 -> EQ_GRAPH_LABELS_25
            31 -> EQ_GRAPH_LABELS_31
            else -> EQ_GRAPH_LABELS_10
        }
}
