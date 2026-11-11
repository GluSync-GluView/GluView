package com.gluview.app.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gluview.app.di.DatabaseModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Plano 17 (F1.1): um banco da versão 13 (GluView 1.1.1) com registros do diário e pontas de dedo
 * abre na versão 14 sem perder nada — o Room valida o esquema final contra as entidades.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Migration13To14Test {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After fun tearDown() { context.deleteDatabase(name) }

    private fun createV13() {
        val schema = JSONObject(File("schemas/com.gluview.app.data.local.AppDatabase/13.json").readText())
            .getJSONObject("database")
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            e.optJSONArray("indices")?.let { idx ->
                for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL("INSERT INTO event_log (occurredAt, type, notes, value, sgvAtEvent) VALUES (1000, 'meal', 'almoço', 60.0, 150.0)")
        db.execSQL("INSERT INTO event_log (occurredAt, type, notes, value, sgvAtEvent) VALUES (2000, 'insulin', '', 4.5, 180.0)")
        db.execSQL("INSERT INTO fingerstick_log (ts, fingerstickMgDl, sensorMgDl, diff, diffPct) VALUES (3000, 120.0, 110.0, 10.0, 8.3)")
        db.version = 13
        db.close()
    }

    @Test fun `13 para 14 preserva o diario e traz as pontas de dedo`() = runBlocking {
        createV13()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val all = room.eventLogDao().getAll("")
        assertEquals(3, all.size)
        val meal = all.first { it.type == "meal" }
        assertEquals(60.0, meal.value, 0.0)
        assertEquals("almoço", meal.notes)
        assertEquals(1000L, meal.updatedAt)
        assertTrue(meal.dirty)
        val finger = all.first { it.type == "fingerstick" }
        assertEquals(120.0, finger.value, 0.0)
        assertEquals("diagnostic", finger.source)
        // uuid único e no formato 8-4-4-4-12
        assertEquals(3, all.map { it.uuid }.toSet().size)
        assertTrue(all.all { Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-a[0-9a-f]{3}-[0-9a-f]{12}$").matches(it.uuid) })
        // Tabelas novas existem e funcionam
        assertEquals(0, room.reminderDao().getAll().size)
        assertEquals(0, room.diaryCatalogDao().observeInsulins().first().size)
        // A conta que entra reclama os registros sem dono
        room.eventLogDao().claimUnownedRows("a@b.c")
        assertEquals(3, room.eventLogDao().getAll("a@b.c").size)
        room.close()
    }
}
