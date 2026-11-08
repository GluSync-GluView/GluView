package com.gluview.wear.ui

import com.gluview.wear.contract.SnapshotZone
import com.gluview.wear.contract.WatchFaceSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cores por zona (ARGB) — as mesmas famílias do app do celular; cinza quando o dado é velho. */
object Display {
    const val COLOR_STALE = 0xFF9E9E9E.toInt()
    const val COLOR_TEXT_DIM = 0xFFB0B0B0.toInt()

    fun zoneColor(snapshot: WatchFaceSnapshot?, nowMs: Long): Int {
        if (snapshot == null || snapshot.isStale(nowMs)) return COLOR_STALE
        return when (snapshot.zone) {
            SnapshotZone.URGENT_LOW, SnapshotZone.QUICK_DROP -> 0xFFE53935.toInt()
            SnapshotZone.LOW -> 0xFFFB8C00.toInt()
            SnapshotZone.NORMAL -> 0xFF43A047.toInt()
            SnapshotZone.HIGH -> 0xFFFDD835.toInt()
            SnapshotZone.URGENT_HIGH -> 0xFFFF7043.toInt()
        }
    }

    /** Hora da MEDIÇÃO (não "há X min" — decisão de 08/07 do plano 05), no formato do usuário. */
    fun measuredTime(snapshot: WatchFaceSnapshot, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat(if (snapshot.use24h) "HH:mm" else "h:mm a", locale).format(Date(snapshot.measuredAtMs))

    fun unitLabel(snapshot: WatchFaceSnapshot): String =
        if (snapshot.unit == com.gluview.wear.contract.SnapshotUnit.MMOL_L) "mmol/L" else "mg/dL"
}
