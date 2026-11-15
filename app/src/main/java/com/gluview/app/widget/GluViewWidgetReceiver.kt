package com.gluview.app.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class GluViewWidgetReceiver : GlucoseWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GluViewWidget()
}
