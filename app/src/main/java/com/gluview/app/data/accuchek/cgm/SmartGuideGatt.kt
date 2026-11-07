package com.gluview.app.data.accuchek.cgm

import java.util.UUID

/**
 * Perfil Bluetooth do Accu-Chek SmartGuide: o **Continuous Glucose Monitoring Service (0x181F)**
 * padrão da Bluetooth SIG, sem cripto proprietária e sem biblioteca nativa.
 *
 * Fontes (ver `Projeto DM1/GluView/12_sprint_accuchek_smartguide.md`):
 *  - Juggluco 11.5.7 (GPLv3) — `AccuGattCallback.java`, `cpp/accu/java.cpp`;
 *  - xDrip+ (GPLv3) — `libglupro` (`lwld.glucose.profile`, flavour ACCUCHEK_SMARTGUIDE);
 *  - bytes reais de um SmartGuide (97 registros com CRC conferido).
 *
 * Substitui o perfil de glicosímetro (Glucose Service 0x1808) usado pelo driver antigo, que não é o
 * do SmartGuide.
 */
object SmartGuideGatt {
    private fun sig(shortUuid: String): UUID =
        UUID.fromString("0000$shortUuid-0000-1000-8000-00805f9b34fb")

    val CGM_SERVICE: UUID          = sig("181f")
    val DEVICE_INFO_SERVICE: UUID  = sig("180a")

    /** notify — leituras (uma a cada 5 min; no histórico, vários registros por notificação). */
    val CGM_MEASUREMENT: UUID      = sig("2aa7")
    /** read — capacidades (E2E-CRC, tendência, qualidade, vários pareamentos). */
    val CGM_FEATURE: UUID          = sig("2aa8")
    /** read — minutos desde o início + avisos do sensor. */
    val CGM_STATUS: UUID           = sig("2aa9")
    /** read/write — início da sessão (o SmartGuide manda em UTC). */
    val SESSION_START_TIME: UUID   = sig("2aaa")
    /** read — vida útil prevista em horas (336 h = 14 dias). */
    val SESSION_RUN_TIME: UUID     = sig("2aab")
    /** write + indicate — Record Access Control Point (histórico). */
    val RACP: UUID                 = sig("2a52")
    /** write + indicate — CGM Specific Ops Control Point (intervalo, calibração). */
    val SOCP: UUID                 = sig("2aac")

    val SERIAL_NUMBER: UUID        = sig("2a25")
    val FIRMWARE_REVISION: UUID    = sig("2a26")
    val HARDWARE_REVISION: UUID    = sig("2a27")
    val MANUFACTURER_NAME: UUID    = sig("2a29")
    val MODEL_NUMBER: UUID         = sig("2a24")

    val CCCD: UUID                 = sig("2902")

    /** Prefixo do nome anunciado: `AC-<serial da embalagem>` (Juggluco `matchDeviceName`). */
    const val NAME_PREFIX = "AC-"

    fun looksLikeSmartGuide(advertisedName: String?): Boolean =
        advertisedName?.trim()?.startsWith(NAME_PREFIX, ignoreCase = true) == true
}
