package com.gluview.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gluview.app.data.local.dao.AlertLogDao
import com.gluview.app.data.local.dao.AppLogDao
import com.gluview.app.data.local.dao.EventLogDao
import com.gluview.app.data.local.dao.FingerstickLogDao
import com.gluview.app.data.local.dao.GlucoseDao
import com.gluview.app.data.local.dao.SensorHistoryDao
import com.gluview.app.data.local.dao.WatchFaceDao
import com.gluview.app.data.local.entity.AlertLogEntity
import com.gluview.app.data.local.entity.AppLogEntity
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.FingerstickLogEntity
import com.gluview.app.data.local.entity.GlucoseEntity
import com.gluview.app.data.local.entity.SensorHistoryEntity
import com.gluview.app.data.local.entity.WatchFaceEntity

@Database(
    entities  = [
        GlucoseEntity::class,
        AlertLogEntity::class,
        EventLogEntity::class,
        FingerstickLogEntity::class,
        AppLogEntity::class,
        WatchFaceEntity::class,
        SensorHistoryEntity::class
    ],
    version   = 13,
    // E8 do plano OTA: esquema exportado em app/schemas (versionado) para testar migrações futuras
    // com MigrationTestHelper. Os esquemas 1–12 não foram guardados na época; daqui pra frente sim.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun glucoseDao(): GlucoseDao
    abstract fun alertLogDao(): AlertLogDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun fingerstickLogDao(): FingerstickLogDao
    abstract fun appLogDao(): AppLogDao
    abstract fun watchFaceDao(): WatchFaceDao
    abstract fun sensorHistoryDao(): SensorHistoryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `event_log` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `occurredAt`  INTEGER NOT NULL,
                        `type`        TEXT    NOT NULL,
                        `notes`       TEXT    NOT NULL DEFAULT '',
                        `value`       REAL    NOT NULL DEFAULT 0.0,
                        `sgvAtEvent`  REAL    NOT NULL DEFAULT 0.0
                    )
                    """.trimIndent()
                )
            }
        }

        // F3/T23 — colunas de dados BLE Sibionics na tabela glucose_readings
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE glucose_readings ADD COLUMN rawValue   REAL")
                db.execSQL("ALTER TABLE glucose_readings ADD COLUMN calibFactor REAL")
                db.execSQL("ALTER TABLE glucose_readings ADD COLUMN sensorMac  TEXT")
                db.execSQL("ALTER TABLE glucose_readings ADD COLUMN sensorAgeMin INTEGER")
            }
        }

        // F3/T28 — tabela de pares sensor vs medidor (validação de glicemia)
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fingerstick_log` (
                        `id`               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ts`               INTEGER NOT NULL,
                        `fingerstickMgDl`  REAL    NOT NULL,
                        `sensorMgDl`       REAL    NOT NULL,
                        `diff`             REAL    NOT NULL,
                        `diffPct`          REAL    NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // F1/T04 — tabela de log do app (aditiva, não toca no histórico de glicemia)
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_log` (
                        `id`     INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ts`     INTEGER NOT NULL,
                        `level`  TEXT    NOT NULL,
                        `tag`    TEXT    NOT NULL,
                        `origin` TEXT    NOT NULL DEFAULT '',
                        `msg`    TEXT    NOT NULL,
                        `data`   TEXT    NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
            }
        }

        // W1 — visores personalizados para SmartWatch/SmartBand.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `watch_faces` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `wearableId` TEXT NOT NULL,
                        `wearableName` TEXT NOT NULL,
                        `wearableWidthPx` INTEGER NOT NULL,
                        `wearableHeightPx` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `componentsJson` TEXT NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        `applyState` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // Histórico local de sensores (3 meses) — mesmo modelo do histórico de glicemia.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sensor_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `mac` TEXT NOT NULL,
                        `serial` TEXT,
                        `customerId` TEXT,
                        `source` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER,
                        `syncedToCloud` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        // F6 — alert_log passa a acomodar avisos de acompanhamento (convite recebido/aceito),
        // não só alertas de glicemia. Aditiva: linhas antigas viram kind="glucose" (default).
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alert_log ADD COLUMN kind TEXT NOT NULL DEFAULT 'glucose'")
                db.execSQL("ALTER TABLE alert_log ADD COLUMN title TEXT")
                db.execSQL("ALTER TABLE alert_log ADD COLUMN message TEXT")
            }
        }

        // Fator de calibração do sensor passa a ser espelhado no GluSync (sensor_history),
        // sobrevivendo a uma limpeza de dados do app — ver SensorHistoryRepository.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sensor_history ADD COLUMN calibrationFactor REAL")
            }
        }

        // Modos de leitura Sibionics (Ajustes > Sensor: Todas / Precisas a cada 5min / Distinção
        // no gráfico) — marca cada leitura como confirmada ou provisória. Aditiva.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE glucose_readings ADD COLUMN confirmed INTEGER")
            }
        }

        // Fase 4 do plano 06_watchfaces_v2_arquitetura.md — biblioteca de watchfaces sincronizada
        // com o GluSync. serverId casa o registro local (Long, Room) com o projeto remoto (UUID).
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_faces ADD COLUMN serverId TEXT")
            }
        }

        // Fase 5 do plano 06_watchfaces_v2_arquitetura.md — WatchfaceCompatibility: ids de wearable
        // compatíveis além do principal. "[]" = comportamento igual a antes (só o principal).
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_faces ADD COLUMN compatibleWearableIdsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        // Isolamento de glicemia por conta (achado 2026-09-26: trocar de conta no mesmo aparelho
        // continuava mostrando a glicemia da conta anterior). Linhas antigas nascem donas de
        // ninguém ("") e são reclamadas pela 1ª conta que logar depois da atualização —
        // ver GlucoseDao.claimUnownedRows e GlucoseRepository.
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE glucose_readings ADD COLUMN ownerEmail TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
