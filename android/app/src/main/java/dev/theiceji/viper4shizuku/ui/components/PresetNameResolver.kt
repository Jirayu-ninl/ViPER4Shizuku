package dev.theiceji.viper4shizuku.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.theiceji.viper4shizuku.data.model.DsPreset
import dev.theiceji.viper4shizuku.data.model.EqPreset
import dev.theiceji.viper4shizuku.effect.BuiltinPresets

@Composable
fun resolvePresetName(preset: EqPreset): String {
    val resId = preset.nameKey?.let { BuiltinPresets.EQ_PRESET_NAME_RES[it] }
    return if (resId != null) stringResource(resId) else preset.name
}

@Composable
fun resolvePresetName(preset: DsPreset): String {
    val resId = preset.nameKey?.let { BuiltinPresets.DS_PRESET_NAME_RES[it] }
    return if (resId != null) stringResource(resId) else preset.name
}
