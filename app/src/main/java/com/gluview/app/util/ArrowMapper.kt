package com.gluview.app.util

/** Converte a seta de tendência (glifo Unicode, ex.: "↑↑") para o nome do PNG Nightscout-style
 *  usado tanto no upload xDrip (GluSyncService) quanto nos ícones/visores em assets/arrows. */
object ArrowMapper {
    fun toAssetName(arrow: String): String = when (arrow) {
        "↑↑" -> "DoubleUp"
        "↑"  -> "SingleUp"
        "↗"  -> "FortyFiveUp"
        "→"  -> "Flat"
        "↘"  -> "FortyFiveDown"
        "↓"  -> "SingleDown"
        "↓↓" -> "DoubleDown"
        else -> "Flat"
    }
}
