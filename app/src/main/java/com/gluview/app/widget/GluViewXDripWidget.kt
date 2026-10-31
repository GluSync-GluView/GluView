package com.gluview.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gluview.app.MainActivity
import com.gluview.app.R
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.theme.GlucoseZoneColors
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.epochMsToTimeOfDay
import java.io.ByteArrayOutputStream

class GluViewXDripWidget : GlanceAppWidget() {

    companion object {
        val KEY_SGV         = floatPreferencesKey("xw_sgv")
        val KEY_ARROW       = stringPreferencesKey("xw_arrow")
        val KEY_ZONE        = stringPreferencesKey("xw_zone")
        val KEY_STALE       = booleanPreferencesKey("xw_stale")
        val KEY_UNIT        = stringPreferencesKey("xw_unit")
        val KEY_DELTA       = stringPreferencesKey("xw_delta")
        val KEY_LAST_MS     = stringPreferencesKey("xw_last_ms")   // Long como string
        val KEY_DOTS        = stringPreferencesKey("xw_dots")      // base64 PNG
        val KEY_IS_DARK     = booleanPreferencesKey("xw_is_dark")
        val KEY_TIME_FORMAT = stringPreferencesKey("xw_time_format")
        val KEY_LANG        = stringPreferencesKey("xw_lang")

        fun renderDotsToBase64(readings: List<GraphReading>, hours: Int, hipo: Float = 70f, hiper: Float = 180f,
                               urgentLow: Float = 55f, urgentHigh: Float = 250f): String {
            // Portrait 160×220px → encaixa no painel direito do widget (≈80dp × 90dp)
            // sem distorção. O painel direito é mais alto do que largo.
            val bmp = GlucoseGraphRenderer.renderDots(readings, 160, 220, hours, hipo = hipo, hiper = hiper,
                urgentLow = urgentLow, urgentHigh = urgentHigh)
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, baos)
            return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
        }

        fun decodeBase64Bitmap(b64: String): Bitmap? = runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            XDripContent(
                sgv           = prefs[KEY_SGV]     ?: 0f,
                arrow         = prefs[KEY_ARROW]   ?: "",
                zone          = prefs[KEY_ZONE]    ?: "NORMAL",
                isStale       = prefs[KEY_STALE]   ?: true,
                unitStr       = prefs[KEY_UNIT]    ?: "MG_DL",
                delta         = prefs[KEY_DELTA]   ?: "",
                lastMs        = prefs[KEY_LAST_MS]?.toLongOrNull() ?: 0L,
                dotsBitmap    = prefs[KEY_DOTS]?.takeIf { it.isNotBlank() }?.let(::decodeBase64Bitmap),
                dark          = prefs[KEY_IS_DARK] ?: false,
                timeFormatStr = prefs[KEY_TIME_FORMAT] ?: "H24",
                lang          = prefs[KEY_LANG]
            )
        }
    }
}

/**
 * Widget estilo xDrip (redesign visual, Fase 5): valor na cor de TEXTO da zona (contraste nos dois
 * temas; queda rápida com a cor própria em vez de verde), "Sem sinal" escrito em vez de "– ↗" e
 * textos no idioma do app. Os pontos à direita são a variante intencional deste widget (G10).
 */
@Composable
internal fun XDripContent(
    sgv: Float,
    arrow: String,
    zone: String,
    isStale: Boolean,
    unitStr: String,
    delta: String,
    lastMs: Long,
    dotsBitmap: Bitmap?,
    dark: Boolean,
    timeFormatStr: String,
    lang: String?
) {
    val ctx = LocalContext.current.widgetContext(lang)
    val noData = isStale || sgv == 0f
    val unit = GlucoseUnit.fromString(unitStr)
    val value = with(unit) { sgv.toDouble().toDisplay() }
    val zoneEnum = if (noData) GlucoseZone.STALE else runCatching { GlucoseZone.valueOf(zone) }.getOrDefault(GlucoseZone.NORMAL)
    val title = when {
        noData -> ctx.getString(R.string.zone_stale)
        arrow.isBlank() -> value
        else -> "$value $arrow"
    }
    val detail = if (noData) null else delta.ifBlank { null }?.let { "$it ${unit.label}" } ?: unit.label
    val time = if (lastMs > 0L) epochMsToTimeOfDay(lastMs, TimeFormat.fromString(timeFormatStr)) else null
    val timeLine = when {
        time == null -> null
        noData -> ctx.getString(R.string.notif_last_reading, time)
        else -> time
    }

    // Fundo semi-transparente (estilo xDrip) — preto em modo escuro, branco em modo claro
    val bgColor = if (dark) Color(0xCC000000.toInt()) else Color(0xCCFFFFFF.toInt())
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ColorProvider(bgColor))
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier          = GlanceModifier.fillMaxSize().padding(10.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            // O texto tem a largura dele ("96 ↓↓" não quebra); os pontos ficam com o que sobra
            // (antes 80dp fixos espremiam o valor).
            Column(horizontalAlignment = Alignment.Horizontal.Start) {
                Text(
                    text  = title,
                    style = TextStyle(
                        color      = ColorProvider(GlucoseZoneColors.text(zoneEnum, dark)),
                        fontSize   = if (noData) 20.sp else 30.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                if (detail != null) {
                    Spacer(GlanceModifier.size(2.dp))
                    Text(detail, style = TextStyle(color = ColorProvider(widgetInk(dark, 0xCCFFFFFFL)), fontSize = 13.sp))
                }
                if (timeLine != null) {
                    Spacer(GlanceModifier.size(4.dp))
                    Text(timeLine, style = TextStyle(color = ColorProvider(widgetInk(dark, 0x99FFFFFFL)), fontSize = 11.sp))
                }
            }

            Spacer(GlanceModifier.size(6.dp))

            // ── Lado direito: mini scatter plot de pontos ─────────────────────
            if (dotsBitmap != null) {
                Image(
                    provider           = ImageProvider(dotsBitmap),
                    contentDescription = ctx.getString(R.string.widget_chart_description_short),
                    modifier           = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                )
            }
        }
    }
}
