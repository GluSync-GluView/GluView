package com.gluview.wearables.xiaomi

/**
 * Plano 21, F7.4 (Sem teste com hardware real).
 * Módulo separado AGPL-3.0 para suporte a Mi Band 7/8/9 e dispositivos Xiaomi novos
 * (protocolo protobuf/autenticação por versão), a ser integrado ao núcleo de wearables
 * do GluView. Código derivado/adaptado do Gadgetbridge (AGPLv3) deve manter cabeçalhos
 * originais nos arquivos copiados.
 *
 * Implementação mínima de integração: contrato de envio de glicemia/notificação e
 * identificação de modelos suportados; o transporte/protocolo completo depende de
 * aparelhos reais e vetores de captura.
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
