package com.gluview.app.util

enum class GlucoseUnit {
    MG_DL, MMOL_L;

    val label get() = if (this == MG_DL) "mg/dL" else "mmol/L"

    fun Double.toDisplay(): String = when (this@GlucoseUnit) {
        MG_DL  -> this.toInt().toString()
        MMOL_L -> String.format("%.1f", this / 18.0)
    }

    fun Double.deltaToDisplay(delta: Int): String {
        val converted = if (this@GlucoseUnit == MMOL_L) delta / 18.0 else delta.toDouble()
        return when {
            delta > 0 -> "+${if (this@GlucoseUnit == MG_DL) delta.toString() else String.format("%.1f", converted)}"
            delta < 0 -> if (this@GlucoseUnit == MG_DL) delta.toString() else String.format("%.1f", converted)
            else      -> "0"
        }
    }

    companion object {
        fun fromString(s: String) = entries.firstOrNull { it.name == s } ?: MG_DL
    }
}
