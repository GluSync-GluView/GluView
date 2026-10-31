package com.gluview.app.util

import android.content.Context
import android.os.BatteryManager

/** Leitura pontual da bateria do celular — usada pelo componente "bateria do celular" do visor. */
object PhoneBattery {
    fun currentPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 0
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else 0
    }
}
