package com.gluview.app.data.sibionics

/**
 * Modo de exibição das leituras Sibionics (Ajustes > Sensor).
 *
 * O sensor transmite um pacote bruto a cada ~1 minuto, mas a `.so` de calibração tem uma janela
 * de observação interna antes de "confirmar" um novo valor — na prática, o valor calculado muda
 * a cada ~5 minutos (validado empiricamente: 88% das mudanças reais acontecem em exatamente 5min,
 * batendo com o comportamento do app oficial SugarJoy). Ver [SibionicsProtocol.Reading.confirmed].
 *
 * - ALL:              todas as leituras (comportamento histórico do GluView, mais granular).
 * - CONFIRMED_ONLY:    só a leitura atual/alertas/notificação seguem as leituras confirmadas
 *                      (~5min), mais parecido com o app oficial. Nada deixa de ser GRAVADO —
 *                      só o que vira "leitura atual" muda.
 * - ALL_WITH_MARKERS:  igual a ALL na exibição, mas o gráfico de histórico marca visualmente
 *                      quais pontos são confirmados vs. provisórios.
 */
enum class GlucoseDisplayMode(val id: String, val label: String) {
    ALL("ALL", "Todas as medições"),
    CONFIRMED_ONLY("CONFIRMED_ONLY", "Precisas a cada 5min"),
    ALL_WITH_MARKERS("ALL_WITH_MARKERS", "Todas + distinção no gráfico");

    companion object {
        fun fromId(id: String?): GlucoseDisplayMode =
            entries.firstOrNull { it.id == id } ?: ALL
    }
}
