package com.gluview.app.util.graph

import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import java.time.ZoneId

/** Janela do eixo X. */
sealed interface GraphWindow {
    /** Últimas [hours] horas até agora (ou até a leitura mais nova, se um pouco à frente). */
    data class Trailing(val hours: Int) : GraphWindow
    /** Intervalo fixo — Histórico: não anda com o relógio (um tick desenharia lacuna falsa). */
    data class Fixed(val fromMs: Long, val toMs: Long) : GraphWindow
}

/** Variante de desenho: o modelo da Home (app, notificação, widgets, Auto, Dream) ou o visor. */
enum class GraphVariant { APP, WATCH }

/**
 * O que desenhar (redesign visual, Fase 2). Os parâmetros da 1ª linha reproduzem as chamadas de
 * hoje; os de "extensões" nascem com default igual ao desenho de hoje e só mudam o resultado
 * quando a tela pede (Fase 3+). O oráculo prova que, nos defaults, nenhum pixel muda.
 */
data class GraphSpec(
    val widthPx: Int,
    val heightPx: Int,
    val window: GraphWindow,
    val nowMs: Long,
    val variant: GraphVariant = GraphVariant.APP,
    val dark: Boolean = true,
    val hipo: Float = GraphScale.DEFAULT_HIPO,
    val hiper: Float = GraphScale.DEFAULT_HIPER,
    /** Limites urgentes de Ajustes > Alertas (faixa vermelha e ponto urgente); default 55/250 (L2). */
    val urgentLow: Float = GraphScale.URGENT_LOW,
    val urgentHigh: Float = GraphScale.URGENT_HIGH,
    /** null = eixos automáticos (altura >= 200px); true/false = forçado (widgets passam false). */
    val forceAxes: Boolean? = null,

    // ---- extensões (default = desenho de hoje) ----
    /** Unidade dos rótulos do eixo Y. As LINHAS ficam nos mesmos níveis (decisão 5.2 #1). */
    val unit: GlucoseUnit = GlucoseUnit.MG_DL,
    /** null = "HH:mm" no idioma padrão (hoje); H12 = "2 PM" (decisão 5.2 #2); H24 = "HH:mm". */
    val timeFormat: TimeFormat? = null,
    /** null = fuso do sistema (hoje). */
    val zone: ZoneId? = null,
    /** null = 1h até 6h de janela, 2h até 12h, 4h acima (hoje). */
    val xTickStepHours: Int? = null,
    /** Rótulo do eixo X não corta nas bordas (hoje corta nos ~3 min depois da hora cheia). */
    val clampXLabels: Boolean = false,
    /** Rótulos do eixo Y não se sobrepõem quando um limiar fica perto de 100/150/250. */
    val avoidYLabelCollision: Boolean = false,
    /** Tamanho do rótulo em px já calculado pelo hospedeiro (sp com teto); null = 8% da altura (hoje). */
    val labelSizePx: Float? = null,
    /** Fundo transparente (o hospedeiro pinta o painel). */
    val transparentBackground: Boolean = false,
    /** false = nenhum texto no canvas (fallback do Moto: rótulos desenhados pelo hospedeiro). */
    val drawText: Boolean = true,
    /** Quebra a linha entre leituras mais distantes que isto (decisão 5.2 #5). null = não quebra (hoje). */
    val gapBreakMs: Long? = null,
    /** Marca "LO"/"HI" em valores fora da escala 40–300 (decisão 5.2 #4). */
    val outOfRangeMarkers: Boolean = false,
    /** Gráfico claro com linhas e pontos um tom mais escuros, >= 3:1 (decisão 5.2 #8). */
    val highContrastLight: Boolean = false
)
