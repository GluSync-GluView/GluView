package com.gluview.app.domain.model

/**
 * Os 4 limites de Ajustes > Alertas, e a ÚNICA regra que transforma glicemia em zona no app
 * (L2, decisão do mantenedor em 18/10/2026): card, ponto do gráfico, widget, notificação E alarme
 * local usam estes valores. Mesma regra do AlertEngine.ClassifyZone do GluSync (Pushover/IFTTT/
 * seguidores): o valor do limite conta como FORA — 70 com "Baixo" em 70 é baixa, 180 com "Alto"
 * em 180 é alta. Antes o alarme local e o card usavam 55/70/180/250 fixos e o ponto usava os
 * limites do usuário com a regra oposta (70 saía verde com o card dizendo "baixa").
 */
data class GlucoseThresholds(
    val urgentLow: Float = 55f,
    val low: Float = 70f,
    val high: Float = 180f,
    val urgentHigh: Float = 250f
) {
    /** Zona da leitura. A ordem dos testes segue o GluSync (vale com urgentLow < low < high < urgentHigh). */
    fun classify(sgvMgDl: Double, arrow: String = "", stale: Boolean = false): GlucoseZone = when {
        stale -> GlucoseZone.STALE
        arrow == "↓↓" -> GlucoseZone.QUICK_DROP
        sgvMgDl <= urgentLow -> GlucoseZone.URGENT_LOW
        sgvMgDl <= low -> GlucoseZone.LOW
        sgvMgDl >= urgentHigh -> GlucoseZone.URGENT_HIGH
        sgvMgDl >= high -> GlucoseZone.HIGH
        else -> GlucoseZone.NORMAL
    }

    /** O mesmo, no texto gravado em `GlucoseEntity.status` (urgent_low/low/normal/high/urgent_high). */
    fun status(sgvMgDl: Double): String = when {
        sgvMgDl <= urgentLow -> "urgent_low"
        sgvMgDl <= low -> "low"
        sgvMgDl >= urgentHigh -> "urgent_high"
        sgvMgDl >= high -> "high"
        else -> "normal"
    }

    companion object {
        val DEFAULT = GlucoseThresholds()
    }
}
