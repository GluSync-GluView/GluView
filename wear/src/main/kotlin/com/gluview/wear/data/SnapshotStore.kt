package com.gluview.wear.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.gluview.wear.complication.GlucoseComplicationService
import com.gluview.wear.tile.GlucoseTileService
import com.gluview.wear.contract.WatchFaceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Última leitura conhecida no relógio — fonte única da complication, do tile e da tela.
 *
 * Guarda o mapa do contrato em SharedPreferences (os tipos Int/Long/Boolean/String voltam iguais em
 * `prefs.all`) e o PNG do minigráfico num arquivo. Nada aqui decide se o dado é velho: quem mostra
 * chama [WatchFaceSnapshot.isStale] com a hora do relógio no momento de desenhar.
 */
object SnapshotStore {
    private const val PREFS = "gluview_snapshot"
    private const val GRAPH_FILE = "graph.png"

    private val _state = MutableStateFlow<WatchFaceSnapshot?>(null)
    @Volatile private var loaded = false

    fun state(context: Context): StateFlow<WatchFaceSnapshot?> {
        ensureLoaded(context)
        return _state
    }

    fun load(context: Context): WatchFaceSnapshot? {
        ensureLoaded(context)
        return _state.value
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val all = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        _state.value = if (all.isEmpty()) null else WatchFaceSnapshot.fromMap(all)
        loaded = true
    }

    /**
     * Grava e avisa complication e tile. [graphPng] null apaga o gráfico anterior (leitura vinda da
     * nuvem não traz gráfico, e um gráfico velho ao lado de um valor novo enganaria).
     */
    @Synchronized
    fun save(context: Context, snapshot: WatchFaceSnapshot, graphPng: ByteArray?) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            clear()
            snapshot.toMap().forEach { (k, v) ->
                when (v) {
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is Boolean -> putBoolean(k, v)
                    is String -> putString(k, v)
                }
            }
        }.commit()
        val f = File(app.filesDir, GRAPH_FILE)
        if (graphPng != null) f.writeBytes(graphPng) else f.delete()
        _state.value = snapshot
        loaded = true
        refreshSurfaces(app)
    }

    fun graphPng(context: Context): ByteArray? =
        File(context.applicationContext.filesDir, GRAPH_FILE).takeIf { it.isFile }?.readBytes()

    fun graphBitmap(context: Context): Bitmap? =
        graphPng(context)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    /** Pede para complication e tile se redesenharem (leitura nova ou mudança de estado). */
    fun refreshSurfaces(context: Context) {
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                context, ComponentName(context, GlucoseComplicationService::class.java)
            ).requestUpdateAll()
        }
        runCatching { TileService.getUpdater(context).requestUpdate(GlucoseTileService::class.java) }
    }
}
