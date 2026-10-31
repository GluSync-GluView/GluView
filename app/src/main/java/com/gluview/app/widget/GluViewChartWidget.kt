package com.gluview.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gluview.app.MainActivity
import com.gluview.app.R
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.epochMsToTimeOfDay
import com.gluview.app.util.graph.surfaceLabelPx
import com.gluview.app.util.graph.surfaceSpec
import kotlin.math.roundToInt

/**
 * Widget de gráfico (4×2, 3×2 e faixa 2×1 — três receivers, esta classe). Redesign visual, Fase 5:
 * - [SizeMode.Exact]: o LocalSize é o tamanho REAL (antes o mínimo do provider, e os três caíam
 *   no layout sobreposto);
 * - o gráfico da Home desenhado no tamanho da caixa, com fundo transparente sobre a cor da zona
 *   (antes 800×300 reescalado, com um retângulo #1C1C1E/#FAFAFA escondendo a zona) e eixos na
 *   unidade e no formato de hora do usuário quando há altura;
 * - cabeçalho "165 ↗" + "+4 mg/dL · 14:05"; sem sinal escrito, sem seta solta;
 * - nos widgets de 1 linha, cabeçalho baixo e o gráfico com o resto (antes texto sobre o gráfico,
 *   nos três tamanhos); textos no idioma do app.
 */
class GluViewChartWidget : GlanceAppWidget() {

    companion object {
        val KEY_SGV         = floatPreferencesKey("cw_sgv")
        val KEY_ARROW       = stringPreferencesKey("cw_arrow")
        val KEY_ZONE        = stringPreferencesKey("cw_zone")
        val KEY_STALE       = booleanPreferencesKey("cw_stale")
        val KEY_UNIT        = stringPreferencesKey("cw_unit")
        val KEY_DELTA       = stringPreferencesKey("cw_delta")
        val KEY_HOURS       = floatPreferencesKey("cw_hours")
        val KEY_LAST_MS     = stringPreferencesKey("cw_last_ms")
        val KEY_IS_DARK     = booleanPreferencesKey("cw_is_dark")
        val KEY_TIME_FORMAT = stringPreferencesKey("cw_time_format")
        /** Leituras compactas ([encodeWidgetPoints]) — o widget desenha no tamanho dele. */
        val KEY_POINTS      = stringPreferencesKey("cw_points")
        val KEY_HIPO        = floatPreferencesKey("cw_hipo")
        val KEY_HIPER       = floatPreferencesKey("cw_hiper")
        val KEY_URGENT_LOW  = floatPreferencesKey("cw_urgent_low")
        val KEY_URGENT_HIGH = floatPreferencesKey("cw_urgent_high")
        val KEY_LANG        = stringPreferencesKey("cw_lang")
    }

    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { ChartWidgetBody(ChartWidgetData.from(currentState())) }
    }
}

/** O que o widget de gráfico mostra (lido do state que o serviço grava). */
internal data class ChartWidgetData(
    val sgv: Float,
    val arrow: String,
    val zone: String,
    val isStale: Boolean,
    val unit: GlucoseUnit,
    val delta: String,
    val lastMs: Long,
    val points: List<GraphReading>,
    val hours: Int,
    val dark: Boolean,
    val timeFormat: TimeFormat,
    val hipo: Float,
    val hiper: Float,
    val lang: String?,
    val urgentLow: Float = 55f,
    val urgentHigh: Float = 250f
) {
    companion object {
        fun from(prefs: Preferences) = ChartWidgetData(
            sgv        = prefs[GluViewChartWidget.KEY_SGV] ?: 0f,
            arrow      = prefs[GluViewChartWidget.KEY_ARROW] ?: "",
            zone       = prefs[GluViewChartWidget.KEY_ZONE] ?: "NORMAL",
            isStale    = prefs[GluViewChartWidget.KEY_STALE] ?: true,
            unit       = GlucoseUnit.fromString(prefs[GluViewChartWidget.KEY_UNIT] ?: "MG_DL"),
            delta      = prefs[GluViewChartWidget.KEY_DELTA] ?: "",
            lastMs     = prefs[GluViewChartWidget.KEY_LAST_MS]?.toLongOrNull() ?: 0L,
            points     = decodeWidgetPoints(prefs[GluViewChartWidget.KEY_POINTS] ?: ""),
            hours      = (prefs[GluViewChartWidget.KEY_HOURS] ?: 3f).toInt(),
            dark       = prefs[GluViewChartWidget.KEY_IS_DARK] ?: false,
            timeFormat = TimeFormat.fromString(prefs[GluViewChartWidget.KEY_TIME_FORMAT] ?: "H24"),
            hipo       = prefs[GluViewChartWidget.KEY_HIPO] ?: 70f,
            hiper      = prefs[GluViewChartWidget.KEY_HIPER] ?: 180f,
            lang       = prefs[GluViewChartWidget.KEY_LANG],
            urgentLow  = prefs[GluViewChartWidget.KEY_URGENT_LOW] ?: 55f,
            urgentHigh = prefs[GluViewChartWidget.KEY_URGENT_HIGH] ?: 250f
        )
    }
}

// Troca mecânica preservando o alpha original: tinta clara (branco) sobre fundo escuro vira
// tinta escura sobre fundo claro. `internal` porque GluViewXDripWidget.kt reaproveita.
internal fun widgetInk(dark: Boolean, darkArgb: Long): Color {
    if (dark) return Color(darkArgb)
    val alpha = (darkArgb shr 24) and 0xFFL
    return Color((alpha shl 24) or 0x1A1A1AL)
}

/** Altura do cabeçalho (valor em 26sp, ou 18sp na faixa, + respiro); o gráfico fica com o resto. */
private val HEADER_DP = 38.dp
private val PAD_DP = 10.dp
private val STRIP_HEADER_DP = 22.dp
private val STRIP_PAD_DP = 4.dp
/** Abaixo disto (1 linha da grade da tela inicial) o widget usa o layout compacto. */
private val COMPACT_MAX_HEIGHT = 130.dp

@Composable
internal fun ChartWidgetBody(d: ChartWidgetData, nowMs: Long = System.currentTimeMillis()) {
    val size = LocalSize.current
    val ctx = LocalContext.current.widgetContext(d.lang)
    val res = ctx.resources
    val density = res.displayMetrics.density
    // Widget de 1 linha (no S23 FE, 188×96dp): cabeçalho baixo numa linha só e o gráfico sem
    // eixos com o resto. Antes o corte era 90dp, o widget de 96dp caía no layout grande e o
    // gráfico ficava com ~34dp de altura.
    val strip = size.height < COMPACT_MAX_HEIGHT

    val noData = d.isStale || d.sgv == 0f
    val value = with(d.unit) { d.sgv.toDouble().toDisplay() }
    val time = if (d.lastMs <= 0L) null else epochMsToTimeOfDay(d.lastMs, d.timeFormat)
    // Sem sinal escrito no lugar do valor (antes "–" solto), com a hora da última leitura.
    val title = when {
        noData -> ctx.getString(R.string.zone_stale)
        d.arrow.isBlank() -> value
        else -> "$value ${d.arrow}"
    }
    val detail = if (noData) time?.let { ctx.getString(R.string.notif_last_reading, it) }.orEmpty()
                 else listOfNotNull(d.delta.ifBlank { null }?.let { "$it ${d.unit.label}" } ?: d.unit.label, time).joinToString(" · ")

    val pad = if (strip) STRIP_PAD_DP else PAD_DP
    val header = if (strip) STRIP_HEADER_DP else HEADER_DP
    val wPx = ((size.width - pad * 2).value * density).roundToInt()
    val hPx = ((size.height - pad * 2 - header).value * density).roundToInt()
    val chart = remember(d, wPx, hPx) {
        if (d.points.isEmpty() || wPx <= 0 || hPx <= 0) null
        else GlucoseGraphRenderer.render(d.points, surfaceSpec(
            wPx, hPx, d.hours, nowMs, d.dark, d.hipo, d.hiper, d.unit, d.timeFormat,
            labelSizePx = surfaceLabelPx(res, 10f), forceAxes = if (strip) false else null,
            urgentLow = d.urgentLow, urgentHigh = d.urgentHigh
        ))
    }
    val chartDescription = ctx.getString(R.string.widget_chart_description, d.hours)

    Box(
        modifier = GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(ColorProvider(widgetZoneBackground(d.zone, noData, d.dark)))
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.TopStart
    ) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(pad)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(title, style = TextStyle(color = ColorProvider(widgetInk(d.dark, 0xFFFFFFFFL)),
                    fontSize = if (strip) 17.sp else if (noData) 20.sp else 26.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                Text(detail, style = TextStyle(color = ColorProvider(widgetInk(d.dark, 0xCCFFFFFFL)),
                    fontSize = if (strip) 10.sp else 12.sp, textAlign = TextAlign.End), maxLines = if (strip) 1 else 2)
            }
            Spacer(GlanceModifier.size(if (strip) 1.dp else 4.dp))
            if (chart != null) {
                // O gráfico é o ÚLTIMO item e ocupa o resto (mesmo modelo da Home).
                // FillBounds: o bitmap já sai no tamanho da área; se a estimativa do cabeçalho errar
                // por alguns dp, ele estica um pouco em vez de encolher inteiro (Fit).
                Image(ImageProvider(chart), chartDescription, GlanceModifier.fillMaxWidth().defaultWeight(),
                    contentScale = ContentScale.FillBounds)
            } else {
                Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    Text(
                        text  = ctx.getString(if (noData) R.string.zone_stale else R.string.widget_waiting),
                        style = TextStyle(color = ColorProvider(widgetInk(d.dark, 0x99FFFFFFL)), fontSize = 12.sp, textAlign = TextAlign.Center)
                    )
                }
            }
        }
    }
}
