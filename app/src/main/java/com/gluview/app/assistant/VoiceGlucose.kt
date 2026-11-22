package com.gluview.app.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plano 21 (F8, Google) — "Ok Google, abrir glicose no GluView" (App Actions, res/xml/shortcuts.xml)
 * ou o atalho do ícone abrem gluview://assistente/glicose: o app vai para a Home e FALA a glicemia
 * com o leitor de voz do Android. Mesmas regras da skill da Alexa no GluSync: leitura com 20 min ou
 * mais nunca é dita como atual; não decide nem aconselha nada.
 */
object VoiceGlucoseText {
    const val STALE_MIN = 20

    fun speak(reading: GlucoseReading?, mmol: Boolean, low: Float, high: Float, nowMs: Long): String {
        if (reading == null || reading.epochMs <= 0L) return "Ainda não há leitura de glicose no GluView."
        val ageMin = ((nowMs - reading.epochMs).coerceAtLeast(0L) / 60_000L).toInt()
        val value = if (mmol) String.format(Locale("pt", "BR"), "%.1f", reading.sgvMgDl / 18.0)
                    else Math.round(reading.sgvMgDl).toString()
        val unit = if (mmol) "milimols por litro" else "miligramas por decilitro"
        if (ageMin >= STALE_MIN) {
            return "A última leitura do GluView é de ${ageText(ageMin)}: $value $unit. Pode estar sem sinal; confira o sensor."
        }
        val trend = trendText(reading.direction.ifBlank { reading.arrow })?.let { ", $it" }.orEmpty()
        val range = when {
            reading.sgvMgDl <= low -> " Abaixo da sua faixa."
            reading.sgvMgDl >= high -> " Acima da sua faixa."
            else -> ""
        }
        return "Sua glicose está em $value $unit$trend, medida ${ageText(ageMin)}.$range"
    }

    fun trendText(d: String): String? = when (d.trim()) {
        "DoubleUp", "↑↑" -> "subindo rápido"
        "SingleUp", "↑" -> "subindo"
        "FortyFiveUp", "↗" -> "subindo devagar"
        "Flat", "→" -> "estável"
        "FortyFiveDown", "↘" -> "caindo devagar"
        "SingleDown", "↓" -> "caindo"
        "DoubleDown", "↓↓" -> "caindo rápido"
        else -> null
    }

    fun ageText(min: Int): String = when {
        min == 0 -> "agora há pouco"
        min == 1 -> "há 1 minuto"
        min < 60 -> "há $min minutos"
        min < 120 -> "há mais de uma hora"
        else -> "há mais de ${min / 60} horas"
    }
}

@Singleton
class VoiceGlucoseSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: GlucoseRepository,
    private val preferences: AppPreferences,
    private val appLogger: AppLogger
) {
    private var tts: TextToSpeech? = null

    /** Lê a leitura mais nova do banco local e fala. Chamar fora da main thread (consulta o banco). */
    suspend fun speakLatest() {
        val th = preferences.glucoseThresholds.first()
        val text = VoiceGlucoseText.speak(repository.getLatestOnce(), preferences.glucoseUnit.first() == "MMOL_L",
            th.low, th.high, System.currentTimeMillis())
        appLogger.usage("ASSISTANT", "Glicemia falada pelo atalho do Assistente")
        speak(text)
    }

    @Synchronized
    private fun speak(text: String) {
        tts?.shutdown()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            val e = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { e.shutdown(); return@TextToSpeech }
            e.language = Locale("pt", "BR")
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { e.shutdown() }
                @Deprecated("API antiga") override fun onError(id: String?) { e.shutdown() }
            })
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gluview-assistant")
        }
        tts = engine
    }
}
