package com.gluview.app.wearables.hub

import androidx.annotation.StringRes
import com.gluview.app.R
import com.gluview.wearables.core.VibrationStyle

/**
 * Plano 21, F7.1 — tipos de aparelho que recebem os alertas do GluView, cada um com os seus ajustes de
 * vibração. [supported] = estilos que ele toca de verdade (CALL só nas Huami; nos outros vira INSISTENT).
 */
enum class WearableFamily(
    val id: String,
    @StringRes val label: Int,
    @StringRes val hint: Int,
    val supported: Set<VibrationStyle>,
    val canFind: Boolean
) {
    HUAMI("huami", R.string.wearable_family_huami, R.string.wearable_family_huami_hint, VibrationStyle.entries.toSet(), true),
    XIAOMI("xiaomi", R.string.wearable_family_xiaomi, R.string.wearable_family_xiaomi_hint, VibrationStyle.BASIC, true),
    WEAR_OS("wearos", R.string.wearable_family_wearos, R.string.wearable_family_wearos_hint, VibrationStyle.BASIC, false),
    GARMIN("garmin", R.string.wearable_family_garmin, R.string.wearable_family_garmin_hint, VibrationStyle.BASIC, false),
    ZEPP("zepp", R.string.wearable_family_zepp, R.string.wearable_family_zepp_hint, VibrationStyle.BASIC, false);

    val supportsCall: Boolean get() = VibrationStyle.CALL in supported
}
