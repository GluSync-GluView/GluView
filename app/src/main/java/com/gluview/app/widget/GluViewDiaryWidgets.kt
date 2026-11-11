package com.gluview.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gluview.app.QuickAddActivity
import com.gluview.app.R

/**
 * Widget de glicemia com o lápis do diário (plano 17, pedido do Felipe 10/11/2026): o valor abre o
 * app (igual ao widget simples) e o lápis abre só a janela de registro rápido. Usa as mesmas
 * chaves de estado do [GluViewWidget] — o serviço atualiza os dois juntos.
 */
class GluViewDiaryWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            val lang = prefs[GluViewWidget.KEY_LANG]
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.defaultWeight().fillMaxHeight()) {
                    WidgetContent(
                        sgv           = prefs[GluViewWidget.KEY_SGV]     ?: 0f,
                        arrow         = prefs[GluViewWidget.KEY_ARROW]   ?: "–",
                        zone          = prefs[GluViewWidget.KEY_ZONE]    ?: "NORMAL",
                        isStale       = prefs[GluViewWidget.KEY_STALE]   ?: true,
                        unitStr       = prefs[GluViewWidget.KEY_UNIT]    ?: "MG_DL",
                        lastMsStr     = prefs[GluViewWidget.KEY_LAST_MS] ?: "0",
                        timeFormatStr = prefs[GluViewWidget.KEY_TIME_FORMAT] ?: "H24",
                        lang          = lang
                    )
                }
                Box(GlanceModifier.width(4.dp)) {}
                QuickAddButton(GlanceModifier.width(52.dp).fillMaxHeight(), lang, showLabel = false)
            }
        }
    }
}

class GluViewDiaryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GluViewDiaryWidget()
}

/** Só o botão "Registrar no diário" (1×1). Sem dado de glicemia, não precisa de atualização. */
class GluViewQuickAddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { QuickAddButton(GlanceModifier.fillMaxSize(), null, showLabel = true) }
    }
}

class GluViewQuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GluViewQuickAddWidget()
}

private val QUICK_ADD_BG = Color(0xFF1E5AA8)

@Composable
private fun QuickAddButton(modifier: GlanceModifier, lang: String?, showLabel: Boolean) {
    val ctx = LocalContext.current.widgetContext(lang)
    val label = ctx.getString(R.string.widget_quickadd_button)
    Box(
        modifier = modifier
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(ColorProvider(QUICK_ADD_BG))
            .clickable(actionStartActivity<QuickAddActivity>())
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Column(GlanceModifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(ImageProvider(R.drawable.ic_diary_edit), contentDescription = null,
                modifier = GlanceModifier.size(26.dp), colorFilter = ColorFilter.tint(ColorProvider(Color.White)))
            if (showLabel) Text(
                ctx.getString(R.string.widget_quickadd_short),
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            )
        }
    }
}
