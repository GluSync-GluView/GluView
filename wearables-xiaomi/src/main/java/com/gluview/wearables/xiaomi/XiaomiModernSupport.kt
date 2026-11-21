package com.gluview.wearables.xiaomi

import com.gluview.wearables.core.WearableCapabilities
import com.gluview.wearables.core.WearableDeviceInfo
import com.gluview.wearables.xiaomi.modern.XiaomiModernCoordinator
import com.gluview.wearables.xiaomi.modern.XiaomiModernTransport
import kotlinx.coroutines.CoroutineScope

/**
 * F7.4 (plano 21): Suporte a dispositivos Xiaomi modernos (Mi Band 7/8/9).
 * Módulo separado AGPL-3.0 para protocolo protobuf/autenticação por versão.
 * Código derivado/adaptado do Gadgetbridge (AGPLv3) - headers preservados.
 *
 * Implementação mínima de integração: contrato de envio de glicemia/notificação
 * e identificação de modelos suportados; o transporte/protocolo completo depende
 * de aparelhos reais e vetores de captura.
 *
 * SEM TESTE COM HARDWARE REAL.
 */
enum class XiaomiModernModel(val id: String, val displayName: String) {
    MI_BAND_7("mi_band_7", "Xiaomi Smart Band 7"),
    MI_BAND_7_PRO("mi_band_7_pro", "Xiaomi Smart Band 7 Pro"),
    MI_BAND_8("mi_band_8", "Xiaomi Smart Band 8"),
    MI_BAND_8_PRO("mi_band_8_pro", "Xiaomi Smart Band 8 Pro"),
    MI_BAND_9("mi_band_9", "Xiaomi Smart Band 9"),
    MI_BAND_9_PRO("mi_band_9_pro", "Xiaomi Smart Band 9 Pro");

    companion object {
        fun fromId(id: String?): XiaomiModernModel? = entries.firstOrNull { it.id == id }
    }

    fun deviceInfo(): WearableDeviceInfo = WearableDeviceInfo(
        id = id,
        displayName = displayName,
        family = "xiaomi_modern",
        capabilities = WearableCapabilities(
            canShowGlucose = true,
            canShowGraph = true,
            canVibrate = true,
            canReceiveSnooze = true,
            canReportBattery = true,
            canSendWatchface = true
        ),
        maturityLabel = "Sem teste com hardware real"
    )

    /**
     * Cria transporte para integração com WearableDeviceManager (F7.1 core).
     */
    fun createTransport(scope: CoroutineScope): XiaomiModernTransport =
        XiaomiModernTransport(this, scope)

    /**
     * Cria coordenador para autenticação protobuf e gerenciamento de sessão.
     */
    fun createCoordinator(scope: CoroutineScope): XiaomiModernCoordinator =
        XiaomiModernCoordinator(this, scope)
}

data class XiaomiGlucoseUpdate(
    val mgdl: Int,
    val deltaMgdl: Int?,
    val direction: String,
    val timestampMs: Long,
    val stale: Boolean
)

interface XiaomiModernGlucoseSink {
    /** Envia atualização de glicemia (notificação/visor/widget conforme suporte). Sem teste. */
    suspend fun pushGlucose(update: XiaomiGlucoseUpdate): Boolean
}
