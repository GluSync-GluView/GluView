package com.gluview.app.data.local.entity

import androidx.room.ColumnInfo

data class DayStats(
    @ColumnInfo(name = "min_sgv") val minSgv: Double?,
    @ColumnInfo(name = "max_sgv") val maxSgv: Double?,
    @ColumnInfo(name = "avg_sgv") val avgSgv: Double?,
    @ColumnInfo(name = "count")   val count: Int
)
