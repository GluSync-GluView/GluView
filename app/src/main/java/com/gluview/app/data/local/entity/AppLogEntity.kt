package com.gluview.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * F1/T04 — Log do app gravado em banco local (Room), além do arquivo de texto.
 * `origin` é capturado automaticamente (classe.método:linha) pelo AppLogger (T02).
 */
@Entity(tableName = "app_log")
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long      = System.currentTimeMillis(),
    val level: String,          // "usage" | "error"
    val tag: String,            // categoria manual (ex.: SIBIONICS_SRC)
    val origin: String = "",    // classe.método:linha (automático)
    val msg: String,
    val data: String  = ""      // JSON dos campos extras
)
