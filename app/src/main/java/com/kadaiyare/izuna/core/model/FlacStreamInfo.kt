package com.kadaiyare.izuna.core.model

data class FlacStreamInfo(val sampleRateHz: Int, val bitDepth: Int, val channels: Int) {
    // JEITA: neither dimension may fall below CD quality (44.1 kHz / 16 bit).
    val isHiRes: Boolean
        get() = sampleRateHz >= 44_100 && bitDepth >= 16 && (sampleRateHz > 48_000 || bitDepth > 16)

    val sampleRateLabel: String
        get() = "${java.math.BigDecimal(sampleRateHz).movePointLeft(3).stripTrailingZeros().toPlainString()} kHz"

    val channelsLabel: String
        get() = when (channels) {
            1 -> "モノラル"
            2 -> "ステレオ"
            else -> "$channels ch"
        }
}
