package dev.theiceji.viper4shizuku.effect

import kotlin.math.ln
import kotlin.math.pow

private val LogGainPerDb = (ln(10.0) / 20.0).toFloat()
private const val AdaptBase = 4.0

fun compressorDbToRaw(db: Float): Float = db * LogGainPerDb

fun compressorRawToDb(raw: Float): Float = raw / LogGainPerDb

fun compressorMsToSeconds(ms: Float): Float = ms / 1000.0f

fun compressorSecondsToMs(seconds: Float): Float = seconds * 1000.0f

fun compressorRatioToRaw(ratio: Float): Float = -ratio

fun compressorRawToRatio(raw: Float): Float = -raw

fun compressorAdaptAmountToSeconds(amount: Float): Float = AdaptBase.pow(amount.toDouble()).toFloat()
