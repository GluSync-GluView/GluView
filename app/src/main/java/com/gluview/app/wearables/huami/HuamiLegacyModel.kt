package com.gluview.app.wearables.huami

import com.gluview.app.wearables.core.WearableCapabilities
import com.gluview.app.wearables.core.WearableDeviceInfo

/**
 * F7.2 (plano 21): modelos Huami/Amazfit antigos suportados por referência WatchDrip+
 * (GPLv3, `C:\claude\_research\wd_src\app\src\main\java\com\thatguysservice\huami_xdrip\watch\miband\`,
 * ex.: `MiBandType.java`, `Firmware/Sequence/SequenceState*.java`, `Firmware/operations/AuthOperations*.java`).
 * Todos **Sem teste com hardware real** nesta execução, exceto Mi Band 6 (caminho existente preservado).
 */
enum class HuamiLegacyModel(
    val id: String,
    val displayName: String,
    val family: String,
    val auth2021: Boolean,
    val supportsWatchfaceUpload: Boolean
) {
    MI_BAND_2("mi_band_2", "Mi Band 2", "miband2", false, false),
    MI_BAND_3("mi_band_3", "Mi Band 3", "miband3", false, false),
    MI_BAND_4("mi_band_4", "Mi Band 4", "miband4", false, true),
    MI_BAND_5("mi_band_5", "Mi Band 5", "miband5", false, true),
    MI_BAND_6("mi_band_6", "Mi Band 6", "miband6", false, true),
    AMAZFIT_BIP("amazfit_bip", "Amazfit Bip", "bip", false, true),
    AMAZFIT_BIP_LITE("amazfit_bip_lite", "Amazfit Bip Lite", "bip", false, true),
    AMAZFIT_BIP_S("amazfit_bip_s", "Amazfit Bip S", "bip_s", false, true),
    AMAZFIT_BIP_S_LITE("amazfit_bip_s_lite", "Amazfit Bip S Lite", "bip_s", false, true),
    AMAZFIT_GTR_47("amazfit_gtr_47", "Amazfit GTR 47mm", "amazfit_gtr", false, true),
    AMAZFIT_GTR_42("amazfit_gtr_42", "Amazfit GTR 42mm", "amazfit_gtr42", false, true),
    AMAZFIT_GTS("amazfit_gts", "Amazfit GTS", "verge", false, true),
    AMAZFIT_GTR2("amazfit_gtr2", "Amazfit GTR 2/2e", "amazfit_gtr2", true, true),
    AMAZFIT_GTS2("amazfit_gts2", "Amazfit GTS 2/2e", "amazfit_gts2", true, true),
    AMAZFIT_GTS2_MINI("amazfit_gts2_mini", "Amazfit GTS 2 mini", "amazfit_gts2_mini", true, true),
    AMAZFIT_BAND_5("amazfit_band5", "Amazfit Band 5", "miband5", false, true),
    AMAZFIT_T_REX_PRO("amazfit_t_rex_pro", "Amazfit T-Rex Pro", "amazfit_trex_pro", false, true);

    fun deviceInfo(): WearableDeviceInfo = WearableDeviceInfo(
        id = id,
        displayName = displayName,
        family = "huami_legacy",
        capabilities = WearableCapabilities(
            canShowGlucose = true,
            canShowGraph = supportsWatchfaceUpload,
            canVibrate = false, // sem confirmação de comando por modelo nesta execução
            canReceiveSnooze = false,
            canReportBattery = false,
            canSendWatchface = supportsWatchfaceUpload
        ),
        maturityLabel = if (this == MI_BAND_6) "Funciona (Mi Band 6 do Felipe)" else "Sem teste com hardware real"
    )

    companion object {
        fun fromId(id: String?): HuamiLegacyModel? = entries.firstOrNull { it.id == id }
    }
}
