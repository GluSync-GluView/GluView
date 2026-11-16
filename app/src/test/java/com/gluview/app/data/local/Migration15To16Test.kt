package com.gluview.app.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gluview.app.di.DatabaseModule
import com.gluview.app.therapy.CalibrationManager
import com.gluview.app.therapy.StoredCalPair
import com.gluview.therapy.CalPair
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Plano 21 (F3.1): um banco da versão 15 (GluView 1.4.x) com leituras abre na 16 sem perder nada;
 * a coluna nova (valor antes da calibração) nasce nula.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Migration15To16Test {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val name = "migration-15-16.db"

    @After fun tearDown() { context.deleteDatabase(name) }

    private fun createV15() {
        val schema = JSONObject(File("schemas/com.gluview.app.data.local.AppDatabase/15.json").readText())
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
        db.execSQL("INSERT INTO glucose_readings (dateMs, sgvMgDl, direction, arrow, status, delta, isStale, minutesAgo, fetchedAt, ownerEmail) " +
            "VALUES (1000, 123.0, 'Flat', '→', 'normal', 2, 0, 0, 1000, 'a@b.c')")
        db.version = 15
        db.close()
    }

    @Test fun `15 para 16 preserva as leituras e a coluna nova nasce nula`() = runBlocking {
        createV15()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val rows = room.glucoseDao().getFrom("a@b.c", 0L)
        assertEquals(1, rows.size)
        assertEquals(123.0, rows[0].sgvMgDl, 0.0)
        assertNull(rows[0].uncalibratedMgDl)
        room.close()
    }

    @Test fun `pares de calibracao ida e volta`() {
        val l = listOf(StoredCalPair("AA:BB|x", CalPair(5L, 100.0, 110.0)), StoredCalPair("", CalPair(6L, 90.5, 95.0)))
        val back = CalibrationManager.decode(CalibrationManager.encode(l))
        assertEquals(2, back.size)
        assertEquals("AA:BB_x", back[0].sensorKey)
        assertEquals(l[1], back[1])
        assertEquals(0, CalibrationManager.decode("lixo;1|2").size)
    }
}
