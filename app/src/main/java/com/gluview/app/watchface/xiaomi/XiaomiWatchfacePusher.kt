package com.gluview.app.watchface.xiaomi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.applyDisplayMode
import com.gluview.app.util.graph.GraphPalette
import com.gluview.app.util.toGraphReading
import com.gluview.app.watchface.miband.MIBAND_GRAPH_HOURS
import com.gluview.app.wearables.xiaomi.XiaomiBandClient
import com.gluview.wearables.xiaomi.watchface.XiaomiWatchfaceBuilder
import com.gluview.wearables.xiaomi.watchface.XiaomiWatchfaceBuilder.Argb
import com.gluview.wearables.xiaomi.watchface.XiaomiWatchfaceBuilder.Family
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Plano 21, F7.4 (22/11/2026) — modelo de Xiaomi nova para o visor (o tamanho e a codificação mudam). */
enum class XiaomiBandModel(val id: String, val label: String, val family: Family) {
    MI_BAND_8("mb8", "Mi Band 8", Family.MI_BAND_8),
    MI_BAND_9("mb9", "Mi Band 9", Family.MI_BAND_9),
    MI_BAND_9_ACTIVE("mb9active", "Mi Band 9 Active", Family.MI_BAND_9_ACTIVE);

    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: MI_BAND_8 }
}

/**
 * Plano 21, F7.4 — glicemia com gráfico no visor da Xiaomi nova (Mi Band 8/9/9 Active), como o visor da
 * Mi Band 6: o fundo (valor, seta, variação, idade e o gráfico de 3 h) é desenhado no celular e o visor
 * inteiro é refeito e reenviado a cada leitura, respeitando o intervalo dia/noite da tela da Mi Band. A
 * hora vem de 4 dígitos que a própria pulseira atualiza. Dois ids alternados: instala o novo, ativa e apaga
 * o anterior (a pulseira não acumula visores).
 *
 * Testado só na pulseira virtual (tools/virtual-sensors/xiaomi.py) e com o gerador conferido byte a byte
 * contra o empacotador de referência; sem pulseira de verdade.
 */
@Singleton
class XiaomiWatchfacePusher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val repository: GlucoseRepository,
    private val client: XiaomiBandClient
) {
    private val tag = "XiaomiFace"
    private val mutex = Mutex()
    @Volatile private var lastAttemptMs = 0L

    companion object {
        const val ID_A = "167210065"
        const val ID_B = "167210066"
        private const val STALE_GREY = 0xFFB0BEC5.toInt()
    }

    suspend fun maybeAutoSend() {
        if (!prefs.xiaomiBandFace.first()) return
        val now = System.currentTimeMillis()
        val minMs = prefs.miBandDayIntervalMin.first().coerceAtLeast(1) * 60_000L
        if (!prefs.miBandUpdateEveryReading.first() && now - lastAttemptMs < minMs) return
        lastAttemptMs = now
        val ok = send { Log.d(tag, it) }
        Log.i(tag, "Visor automático na Xiaomi: ${if (ok) "ok" else "falhou"}")
    }

    suspend fun send(log: (String) -> Unit): Boolean {
        if (!mutex.tryLock()) { log("Envio já em andamento."); return false }
        try {
            val mac = prefs.xiaomiBandMac.first().trim()
            val key = prefs.xiaomiBandKey.first().trim()
            if (mac.isBlank() || key.isBlank()) { log("Pulseira Xiaomi não pareada."); return false }
            val reading = repository.currentReading.value ?: repository.latestFromDb.firstOrNull()
                ?: run { log("Nenhuma leitura ainda."); return false }
            val unit = GlucoseUnit.fromString(prefs.glucoseUnit.first())
            val model = XiaomiBandModel.fromId(prefs.xiaomiBandModel.first())
            val ageMin = if (reading.epochMs > 0) ((System.currentTimeMillis() - reading.epochMs) / 60_000L).toInt() else reading.minutesAgo
            val stale = reading.isStale || reading.zone == GlucoseZone.STALE || ageMin >= prefs.sensorNoSignalMin.first()
            val data = FaceData(
                value = with(unit) { reading.sgvMgDl.toDisplay() },
                delta = with(unit) { reading.sgvMgDl.deltaToDisplay(reading.delta) },
                ageMin = ageMin, arrow = reading.arrow, stale = stale,
                color = zoneColor(reading.zone)
            )
            val graph = withContext(Dispatchers.Default) {
                val fromMs = System.currentTimeMillis() - MIBAND_GRAPH_HOURS * 3_600_000L
                val readings = repository.getRecentReadingsForNotification(fromMs).map { it.toGraphReading(prefs.sibionicsCalFactor.first()) }
                    .applyDisplayMode(GlucoseDisplayMode.fromId(prefs.glucoseDisplayMode.first()))
                val f = model.family
                if (readings.isEmpty()) null else GlucoseGraphRenderer.renderWatchface(readings, f.width - 8, graphHeight(f), MIBAND_GRAPH_HOURS,
                    hipo = prefs.lowThreshold.first(), hiper = prefs.highThreshold.first(), unit = unit,
                    urgentLow = prefs.urgentLowThreshold.first(), urgentHigh = prefs.urgentHighThreshold.first())
            }
            val active = prefs.xiaomiFaceActiveId.first()
            val next = if (active == ID_A) ID_B else ID_A
            log("Gerando visor ${model.label} (${data.value} ${data.delta})…")
            val ok = withTimeoutOrNull(240_000) {
                client.installWatchface(mac, key, next, active.ifBlank { null }, { isV2 ->
                    // V2 só existe na 9 Active: se a pulseira fala V2, usa a 9 Active mesmo com outro modelo salvo.
                    val family = if (isV2) Family.MI_BAND_9_ACTIVE else if (model.family == Family.MI_BAND_9_ACTIVE) Family.MI_BAND_9 else model.family
                    build(family, data, graph, next)
                }, log)
            } == true
            if (ok) prefs.saveXiaomiFaceActiveId(next)
            return ok
        } catch (e: Exception) {
            log("Erro no visor da Xiaomi: ${e.message}")
            return false
        } finally { mutex.unlock() }
    }

    data class FaceData(val value: String, val delta: String, val ageMin: Int, val arrow: String, val stale: Boolean, val color: Int)

    private fun zoneColor(z: GlucoseZone) = when (z) {
        GlucoseZone.URGENT_LOW, GlucoseZone.URGENT_HIGH -> GraphPalette.ZONE_URGENT
        GlucoseZone.LOW, GlucoseZone.HIGH, GlucoseZone.QUICK_DROP -> GraphPalette.ZONE_OUT_OF_RANGE
        GlucoseZone.STALE -> STALE_GREY
        GlucoseZone.NORMAL -> GraphPalette.ZONE_IN_RANGE
    }

    private fun graphHeight(f: Family) = if (f.height >= 400) 170 else 110

    /** Visor inteiro para a família: fundo + prévia + dígitos. Público para o teste e para a tela. */
    fun build(family: Family, d: FaceData, graph: Bitmap?, id: String): ByteArray {
        val w = family.width; val h = family.height
        val tall = h >= 400
        val digitW = if (tall) 36 else 32; val digitH = if (tall) 56 else 48
        val timeY = if (tall) 34 else 14
        val cx = w / 2
        val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bg)
        c.drawColor(Color.BLACK)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        // dois-pontos da hora (os dígitos vêm da pulseira)
        p.color = Color.WHITE
        c.drawCircle(cx.toFloat(), timeY + digitH * 0.35f, 3.5f, p)
        c.drawCircle(cx.toFloat(), timeY + digitH * 0.7f, 3.5f, p)
        // valor + seta
        val valueY = if (tall) 190f else 120f
        p.color = if (d.stale) STALE_GREY else d.color
        p.textSize = if (tall) 64f else 52f
        val text = if (d.stale) "--" else d.value
        val tw = p.measureText(text)
        val arrowS = p.textSize * 0.5f
        val groupW = tw + 8 + arrowS
        c.drawText(text, cx - groupW / 2 + tw / 2, valueY, p)
        if (!d.stale) drawArrow(c, cx - groupW / 2 + tw + 8 + arrowS / 2, valueY - p.textSize * 0.35f, arrowS, d.arrow, p)
        p.color = STALE_GREY; p.textSize = if (tall) 22f else 18f
        c.drawText(if (d.stale) "sem sinal · ${d.ageMin} min" else "${d.delta} · ${d.ageMin} min", cx.toFloat(), valueY + (if (tall) 34 else 26), p)
        // gráfico
        graph?.let { c.drawBitmap(it, 4f, (if (tall) 260 else 190).toFloat(), null) }

        val digits = (0..9).map { n ->
            val b = Bitmap.createBitmap(digitW, digitH, Bitmap.Config.ARGB_8888)
            Canvas(b).drawText(n.toString(), digitW / 2f, digitH * 0.82f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = digitH * 0.95f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER })
            argb(b)
        }
        val gap = 10
        val layout = XiaomiWatchfaceBuilder.TimeLayout(
            hourTensX = cx - gap - 2 * digitW, hourUnitsX = cx - gap - digitW, minTensX = cx + gap, minUnitsX = cx + gap + digitW, y = timeY)
        val preview = Bitmap.createScaledBitmap(bg, w / 2, h / 2, true)
        return XiaomiWatchfaceBuilder.build(family, id, "GluView", argb(bg), argb(preview), digits, layout)
    }

    private fun argb(b: Bitmap): Argb { val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); return Argb(b.width, b.height, px) }

    private fun drawArrow(c: Canvas, x: Float, y: Float, s: Float, arrow: String, p: Paint) {
        val deg = when (arrow) { "↑", "↑↑" -> 90f; "↗" -> 45f; "→" -> 0f; "↘" -> -45f; "↓", "↓↓" -> -90f; else -> return }
        c.save(); c.rotate(-deg, x, y)
        val path = Path().apply {
            moveTo(x - s / 2, y - s / 7); lineTo(x, y - s / 7); lineTo(x, y - s / 2.6f); lineTo(x + s / 2, y)
            lineTo(x, y + s / 2.6f); lineTo(x, y + s / 7); lineTo(x - s / 2, y + s / 7); close()
        }
        c.drawPath(path, p); c.restore()
    }
}
