package com.gluview.app.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class GluViewXDripWidgetReceiver : GlucoseWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GluViewXDripWidget()
}
