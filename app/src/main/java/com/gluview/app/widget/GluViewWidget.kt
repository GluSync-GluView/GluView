package com.gluview.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.gluview.app.MainActivity
import com.gluview.app.R
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.epochMsToTimeOfDay
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class GluViewWidget : GlanceAppWidget() {

    companion object {
        val KEY_SGV         = floatPreferencesKey("w_sgv")
        val KEY_ARROW       = stringPreferencesKey("w_arrow")
        val KEY_ZONE        = stringPreferencesKey("w_zone")
        val KEY_STALE       = booleanPreferencesKey("w_stale")
        val KEY_UNIT        = stringPreferencesKey("w_unit")
        val KEY_LAST_MS     = stringPreferencesKey("w_last_ms")
        val KEY_TIME_FORMAT = stringPreferencesKey("w_time_format")
        val KEY_LANG        = stringPreferencesKey("w_lang")
    }

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            WidgetContent(
                sgv           = prefs[KEY_SGV]     ?: 0f,
                arrow         = prefs[KEY_ARROW]   ?: "–",
                zone          = prefs[KEY_ZONE]    ?: "NORMAL",
                isStale       = prefs[KEY_STALE]   ?: true,
                unitStr       = prefs[KEY_UNIT]    ?: "MG_DL",
                lastMsStr     = prefs[KEY_LAST_MS] ?: "0",
                timeFormatStr = prefs[KEY_TIME_FORMAT] ?: "H24",
                lang          = prefs[KEY_LANG]
            )
        }
    }
}

/** Widget simples (redesign visual, Fase 5): queda rápida com cor própria (antes verde), "Sem
 *  sinal" no idioma do app e cantos arredondados como o resto da família. */
@Composable
internal fun WidgetContent(
    sgv: Float,
    arrow: String,
    zone: String,
    isStale: Boolean,
    unitStr: String,
    lastMsStr: String,
    timeFormatStr: String,
    lang: String? = null
) {
    val ctx = LocalContext.current.widgetContext(lang)
    val noData = isStale || sgv == 0f

    val bgColor = simpleWidgetBackground(zone, noData)

    val displaySgv = when {
        noData              -> "–"
        unitStr == "MMOL_L" -> String.format("%.1f", sgv / 18f)
        else                -> sgv.toInt().toString()
    }
    val unitLabel = if (unitStr == "MMOL_L") "mmol/L" else "mg/dL"
    val subtext   = if (noData) ctx.getString(R.string.zone_stale) else "$arrow  $unitLabel"

    val lastMs   = lastMsStr.toLongOrNull() ?: 0L
    val timeText = if (noData || lastMs <= 0L) "" else epochMsToTimeOfDay(lastMs, TimeFormat.fromString(timeFormatStr))

    Box(
        modifier         = GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(ColorProvider(bgColor))
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier            = GlanceModifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text  = displaySgv,
                style = TextStyle(
                    color      = ColorProvider(Color.White),
                    fontSize   = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )
            )
            Text(
                text  = subtext,
                style = TextStyle(
                    color     = ColorProvider(Color.White),
                    fontSize  = 12.sp,
                    textAlign = TextAlign.Center
                )
            )
            if (timeText.isNotEmpty()) {
                Spacer(GlanceModifier.size(2.dp))
                Text(
                    text  = timeText,
                    style = TextStyle(
                        color     = ColorProvider(Color(0xBBFFFFFF)),
                        fontSize  = 10.sp,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}
