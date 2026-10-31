package com.gluview.app.watchface.miband

import android.content.Context
import android.util.Log
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.ArrowMapper
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.applyDisplayMode
import com.gluview.app.util.epochMsToTimeOfDay
import com.gluview.app.util.withAppLanguage
import com.gluview.app.util.toGraphReading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monta o visor da Mi Band com os dados atuais (glicemia, delta, seta, gráfico) e o envia por
 * BLE. Ponto único usado pelo botão manual (MiBandViewModel), pelo envio automático do
 * GluSyncService a cada nova leitura ([maybeAutoSend], intervalo dia/noite configurável ou a
 * cada leitura) e pelo watchdog de "sem sinal" dos sensores locais ([maybeSendStaleRefresh]).
 */
@Singleton
class MiBandWatchFacePusher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val repository: GlucoseRepository,
    private val uploader: MiBandUploader
) {
    private val tag = "MiBandPusher"
    private val sendMutex = Mutex()
    @Volatile private var lastAutoAttemptMs = 0L

    private companion object {
        val MAC_RE = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }

    /**
     * Envio automático (chamado pelo serviço a cada nova leitura). Respeita o toggle nas
     * preferências e o intervalo mínimo entre TENTATIVAS — sucesso ou falha contam (senão uma
     * band fora de alcance viraria uma reconexão BLE a cada leitura de 1 min). O intervalo é
     * dia/noite, configurável pelo usuário (ex.: 5 min de dia, 30 min à noite).
     */
    suspend fun maybeAutoSend() {
        if (!prefs.miBandAutoSend.first()) return
        val now = System.currentTimeMillis()
        // "Atualizar a cada nova medição" pula o intervalo dia/noite — envia toda vez que esta
        // função é chamada (a cada leitura nova de verdade, ~1/min no Sibionics).
        if (!prefs.miBandUpdateEveryReading.first() && now - lastAutoAttemptMs < currentIntervalMs()) return
        lastAutoAttemptMs = now
        val ok = send(logger = { Log.d(tag, it) })
        Log.i(tag, "Envio automático do visor: ${if (ok) "ok" else "falhou"}")
    }

    /**
     * Chamado pelo watchdog de "sem sinal" do sensor (loop de aviso), NÃO por leitura nova —
     * não há valor novo pra mandar. Reenvia o visor com o valor CONGELADO da última leitura, mas
     * troca o texto de delta por "há Xmin sem sinal": a tela muda a cada tentativa (prova que o
     * app ainda está vivo/tentando) sem fingir que o número é atual. Respeita o mesmo intervalo
     * dia/noite configurado (não é afetado por [AppPreferences.miBandUpdateEveryReading], que só
     * faz sentido para leituras novas de verdade).
     */
    suspend fun maybeSendStaleRefresh(minutesSinceReading: Int) {
        if (!prefs.miBandAutoSend.first()) return
        val now = System.currentTimeMillis()
        if (now - lastAutoAttemptMs < currentIntervalMs()) return
        lastAutoAttemptMs = now
        // Curto e no idioma do app; a hora da última leitura já vai ao lado ("sem sinal · 14:05").
        val noSignal = context.withAppLanguage(prefs.appLanguage.first()).getString(R.string.miband_no_signal)
        val ok = send(staleOverrideText = noSignal, logger = { Log.d(tag, it) })
        Log.i(tag, "Atualização de visor (sensor sem sinal): ${if (ok) "ok" else "falhou"}")
    }

    /** Intervalo mínimo entre tentativas, conforme o horário atual (local, granularidade de
     *  minuto) esteja dentro do período especial configurado ou não. O período pode cruzar a
     *  meia-noite (ex.: 22:00–07:00) ou não (ex.: 00:30–06:30). */
    private suspend fun currentIntervalMs(): Long {
        val dayMin = prefs.miBandDayIntervalMin.first()
        val nightMin = prefs.miBandNightIntervalMin.first()
        val startMin = prefs.miBandNightStartMin.first()
        val endMin = prefs.miBandNightEndMin.first()
        val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        val inSpecialPeriod = when {
            startMin == endMin -> false
            startMin < endMin  -> nowMin in startMin until endMin
            else                -> nowMin >= startMin || nowMin < endMin
        }
        return (if (inSpecialPeriod) nightMin else dayMin).coerceAtLeast(1) * 60_000L
    }

    /**
     * Gera o .bin com a leitura atual e envia à band. Retorna false sem fazer nada se outro
     * envio já estiver em andamento, se faltar pareamento ou se não houver leitura.
     */
    suspend fun send(staleOverrideText: String? = null, logger: (String) -> Unit): Boolean {
        if (!sendMutex.tryLock()) { logger("Envio já em andamento."); return false }
        try {
            val mac = prefs.miBandMac.first().trim()
            val keyStr = prefs.miBandAuthKey.first().trim().removePrefix("0x")
            if (!MAC_RE.matches(mac)) { logger("MAC da Mi Band inválido/não configurado."); return false }
            if (keyStr.length != 32 || !keyStr.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                logger("Auth key da Mi Band inválida/não configurada."); return false
            }

            val reading = repository.currentReading.value
                ?: repository.latestFromDb.firstOrNull()
                ?: run { logger("Nenhuma leitura de glicemia disponível ainda."); return false }

            val unit = GlucoseUnit.fromString(prefs.glucoseUnit.first())
            val bgValue = with(unit) { reading.sgvMgDl.toDisplay() }
            val delta = staleOverrideText ?: with(unit) { reading.sgvMgDl.deltaToDisplay(reading.delta) }
            val arrowName = ArrowMapper.toAssetName(reading.arrow)
            val isHigh = reading.zone == GlucoseZone.HIGH || reading.zone == GlucoseZone.URGENT_HIGH
            val isLow = reading.zone == GlucoseZone.LOW || reading.zone == GlucoseZone.URGENT_LOW

            val ageSec = if (reading.epochMs > 0L)
                ((System.currentTimeMillis() - reading.epochMs) / 1_000L).toInt().coerceAtLeast(0)
            else reading.minutesAgo * 60
            val measuredAtMs = if (reading.epochMs > 0L) reading.epochMs else System.currentTimeMillis() - reading.minutesAgo * 60_000L
            val timeFormat = TimeFormat.fromString(prefs.timeFormat.first())
            val timeText = epochMsToTimeOfDay(measuredAtMs, timeFormat)
            val noSignalMin = prefs.sensorNoSignalMin.first()
            val isStale = staleOverrideText != null ||
                reading.isStale || reading.zone == GlucoseZone.STALE || ageSec / 60 >= noSignalMin

            logger("Gerando visor ($bgValue $delta)…")
            val bin = withContext(Dispatchers.Default) {
                val generator = MiBandWatchFaceGenerator(context)
                // 3 h fixas, a mesma janela do editor de visor (L14; antes as horas do widget).
                val graphHours = MIBAND_GRAPH_HOURS
                val calFactor = prefs.sibionicsCalFactor.first()
                val hipo = prefs.lowThreshold.first()
                val hiper = prefs.highThreshold.first()
                val urgentLow = prefs.urgentLowThreshold.first()
                val urgentHigh = prefs.urgentHighThreshold.first()
                val fromMs = System.currentTimeMillis() - graphHours * 3_600_000L
                // L11: modo de exibição do Sibionics também no visor.
                val mode = GlucoseDisplayMode.fromId(prefs.glucoseDisplayMode.first())
                val graphReadings = repository.getRecentReadingsForNotification(fromMs).map { it.toGraphReading(calFactor) }
                    .applyDisplayMode(mode)
                val graphBitmap = if (graphReadings.isNotEmpty() && generator.graphWidth > 0)
                    GlucoseGraphRenderer.renderWatchface(graphReadings, generator.graphWidth, generator.graphHeight, graphHours,
                        hipo = hipo, hiper = hiper, unit = unit, urgentLow = urgentLow, urgentHigh = urgentHigh)
                else null

                generator.generate(
                    MiBandWatchData(
                        bgValue = bgValue, delta = delta, timeText = timeText,
                        arrowName = arrowName, isHigh = isHigh, isLow = isLow, isStale = isStale,
                        graph = graphBitmap
                    )
                )
            }
            logger("Visor gerado: ${bin.size} bytes. Enviando por BLE…")
            val ok = withTimeoutOrNull(180_000L) {
                withContext(Dispatchers.IO) {
                    uploader.connectAndUpload(mac, parseHex(keyStr), bin, logger)
                }
            }
            if (ok != true) logger("Falha/timeout no envio do visor.")
            return ok == true
        } catch (e: Exception) {
            logger("Erro ao gerar/enviar visor: ${e.message}")
            Log.e(tag, "Erro no envio do visor", e)
            return false
        } finally {
            sendMutex.unlock()
        }
    }

    private fun parseHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) + hex[i * 2 + 1].digitToInt(16)).toByte()
        }
}
