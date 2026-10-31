package com.gluview.app.dream

import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import androidx.compose.ui.graphics.toArgb
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.ui.theme.GlucoseZoneColors
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.agedFor
import com.gluview.app.util.applyDisplayMode
import com.gluview.app.util.epochMsToTimeOfDay
import com.gluview.app.util.graph.surfaceLabelPx
import com.gluview.app.util.graph.surfaceSpec
import com.gluview.app.util.toGraphReading
import com.gluview.app.util.withAppLanguage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.random.Random

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DreamEntryPoint {
    fun glucoseRepository(): GlucoseRepository
    fun appPreferences(): AppPreferences
}

class GluViewDreamService : DreamService() {

    private var scope: CoroutineScope? = null
    private lateinit var repository: GlucoseRepository
    private lateinit var prefs: AppPreferences

    private lateinit var container: View
    private lateinit var tvSgv: TextView
    private lateinit var tvArrowDelta: TextView
    private lateinit var tvTime: TextView
    private lateinit var ivGraph: ImageView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true

        val ep = EntryPointAccessors.fromApplication(applicationContext, DreamEntryPoint::class.java)
        repository = ep.glucoseRepository()
        prefs = ep.appPreferences()

        val view = LayoutInflater.from(this).inflate(R.layout.dream_gluview, null)
        container    = view.findViewById(R.id.dream_container)
        tvSgv        = view.findViewById(R.id.tv_dream_sgv)
        tvArrowDelta = view.findViewById(R.id.tv_dream_arrow_delta)
        tvTime       = view.findViewById(R.id.tv_dream_time)
        ivGraph      = view.findViewById(R.id.iv_dream_graph)
        listOf(tvSgv, tvArrowDelta, tvTime).forEach { it.cap() }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        // Número E gráfico a cada leitura nova (antes o gráfico só a cada 5 min, atrasado do número).
        scope!!.launch {
            repository.currentReading.collect { reading ->
                updateDisplay(reading)
                refreshGraph()
            }
        }

        // A cada minuto o gráfico anda com o relógio; a cada 5 min, o anti-burn-in.
        scope!!.launch {
            var ticks = 0
            while (isActive) {
                delay(60_000L)
                ticks++
                // L12: reavalia a idade da leitura (vira "sem sinal" sem precisar de uma nova).
                updateDisplay(repository.currentReading.value)
                refreshGraph()
                if (ticks % 5 == 0) applyAntiBurnIn()
            }
        }
    }

    override fun onDreamingStopped() {
        scope?.cancel()
        scope = null
        super.onDreamingStopped()
    }

    /** Fonte limitada: 96sp com a fonte do sistema em 2,0 não cabe nem em retrato. */
    private fun TextView.cap(maxScale: Float = 1.3f) {
        val base = textSize / resources.configuration.fontScale
        setTextSize(TypedValue.COMPLEX_UNIT_PX, base * min(resources.configuration.fontScale, maxScale))
    }

    private suspend fun updateDisplay(rawReading: GlucoseReading?) {
        val reading = rawReading?.agedFor(prefs.sensorNoSignalMin.first())
        val unit   = GlucoseUnit.fromString(prefs.glucoseUnit.first())
        val format = TimeFormat.fromString(prefs.timeFormat.first())
        // Textos no idioma do app (no Android < 13 o contexto do serviço fica no do sistema).
        val text = withAppLanguage(prefs.appLanguage.first())

        if (reading == null || reading.isStale || reading.zone == GlucoseZone.STALE) {
            tvSgv.text = text.getString(R.string.zone_stale)
            tvSgv.setTextColor(GlucoseZoneColors.text(GlucoseZone.STALE, dark = true).toArgb())
            tvArrowDelta.text = ""
            tvTime.text = reading?.epochMs?.takeIf { it > 0L }
                ?.let { text.getString(R.string.notif_last_reading, epochMsToTimeOfDay(it, format)) }.orEmpty()
            return
        }

        with(unit) {
            tvSgv.text = reading.sgvMgDl.toDisplay()
            tvArrowDelta.text = "${reading.arrow}  ${reading.sgvMgDl.deltaToDisplay(reading.delta)} ${unit.label}"
        }
        // Mesma paleta de texto por zona do app (antes #FFA726 para baixo/alto, fora da paleta).
        tvSgv.setTextColor(GlucoseZoneColors.text(reading.zone, dark = true).toArgb())
        tvTime.text = if (reading.epochMs > 0L) epochMsToTimeOfDay(reading.epochMs, format) else ""
    }

    /** O gráfico da Home no tamanho MEDIDO da view (antes 800×300 com fitXY: pontos viravam
     *  elipses), fundo transparente sobre o preto (antes uma caixa #1C1C1E) e eixos ligados. */
    private suspend fun refreshGraph() {
        val w = ivGraph.width
        val h = ivGraph.height
        if (w <= 0 || h <= 0) {
            ivGraph.post { scope?.launch { refreshGraph() } }
            return
        }
        val hours = prefs.widgetGraphHours.first()
        val hipo  = prefs.lowThreshold.first()
        val hiper = prefs.highThreshold.first()
        val urgentLow = prefs.urgentLowThreshold.first()
        val urgentHigh = prefs.urgentHighThreshold.first()
        val unit  = GlucoseUnit.fromString(prefs.glucoseUnit.first())
        val format = TimeFormat.fromString(prefs.timeFormat.first())
        val nowMs = System.currentTimeMillis()
        val fromMs = nowMs - hours * 3_600_000L

        val bmp = withContext(Dispatchers.IO) {
            // L11: modo de exibição do Sibionics, como na Home.
            val mode = GlucoseDisplayMode.fromId(prefs.glucoseDisplayMode.first())
            val readings = repository.getRecentReadingsForNotification(fromMs)
                .map { it.toGraphReading(1.0) }.applyDisplayMode(mode)
            GlucoseGraphRenderer.render(readings, surfaceSpec(
                w, h, hours, nowMs, dark = true, hipo = hipo, hiper = hiper, unit = unit, timeFormat = format,
                labelSizePx = surfaceLabelPx(resources, 14f), urgentLow = urgentLow, urgentHigh = urgentHigh
            ))
        }
        ivGraph.setImageBitmap(bmp)
    }

    private fun applyAntiBurnIn() {
        val dp = resources.displayMetrics.density
        val dx = (Random.nextFloat() * 2f - 1f) * 16f * dp
        val dy = (Random.nextFloat() * 2f - 1f) * 12f * dp
        container.animate().translationX(dx).translationY(dy).setDuration(3000L).start()
    }
}
