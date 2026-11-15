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
            // Um só fundo de widget (a Row): marcar o valor e o lápis como fundo derrubava o widget
            // com "Não é possível mostrar o conteúdo" (IllegalStateException do Glance).
            Row(GlanceModifier.fillMaxSize().appWidgetBackground(), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.defaultWeight().fillMaxHeight()) {
                    WidgetContent(
                        sgv           = prefs[GluViewWidget.KEY_SGV]     ?: 0f,
                        arrow         = prefs[GluViewWidget.KEY_ARROW]   ?: "–",
                        zone          = prefs[GluViewWidget.KEY_ZONE]    ?: "NORMAL",
                        isStale       = prefs[GluViewWidget.KEY_STALE]   ?: true,
                        unitStr       = prefs[GluViewWidget.KEY_UNIT]    ?: "MG_DL",
                        lastMsStr     = prefs[GluViewWidget.KEY_LAST_MS] ?: "0",
                        timeFormatStr = prefs[GluViewWidget.KEY_TIME_FORMAT] ?: "H24",
                        lang          = lang,
                        asBackground  = false
                    )
                }
                Box(GlanceModifier.width(4.dp)) {}
                QuickAddButton(GlanceModifier.width(52.dp).fillMaxHeight(), lang, showLabel = false, asBackground = false)
            }
        }
    }
}

class GluViewDiaryWidgetReceiver : GlucoseWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GluViewDiaryWidget()
}

/**
 * Só o botão "Registrar no diário" (1×1). AppWidgetProvider simples (RemoteViews), não Glance: não
 * tem dado para mostrar, e assim desenha sempre — com Glance ele ficava em branco quando o
 * processo reiniciava (sessão ainda não existia ao atualizar o app).
 */
class GluViewQuickAddWidgetReceiver : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        fun views(context: Context): android.widget.RemoteViews =
            android.widget.RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
                val pi = android.app.PendingIntent.getActivity(
                    context, 70_001,
                    android.content.Intent(context, QuickAddActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.quick_add_root, pi)
                setContentDescription(R.id.quick_add_root, context.getString(R.string.widget_quickadd_button))
            }

        /** Redesenha os botões já colocados (idioma novo, app atualizado). */
        fun refreshAll(context: Context) {
            val m = android.appwidget.AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(android.content.ComponentName(context, GluViewQuickAddWidgetReceiver::class.java))
            ids.forEach { m.updateAppWidget(it, views(context)) }
        }
    }
}

private val QUICK_ADD_BG = Color(0xFF1E5AA8)

@Composable
private fun QuickAddButton(modifier: GlanceModifier, lang: String?, showLabel: Boolean, asBackground: Boolean = true) {
    val ctx = LocalContext.current.widgetContext(lang)
    val label = ctx.getString(R.string.widget_quickadd_button)
    Box(
        modifier = modifier
            .let { if (asBackground) it.appWidgetBackground() else it }
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
