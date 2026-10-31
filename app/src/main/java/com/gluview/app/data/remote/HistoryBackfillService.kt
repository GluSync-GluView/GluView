package com.gluview.app.data.remote

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backfill único do histórico local ao logar numa conta nova — migração de banco (troca de provedor,
 * 2026-07-23): a nuvem nasce vazia, mas o Room local ainda tem o histórico do sensor (retenção
 * local de até 100 dias). Sobe tudo de uma vez, em lotes, via
 * [GlucoseRepository.sendReadingsToCloud] — mesmo caminho REST (`/glucose/entries`, upsert
 * idempotente) já usado e testado pelo [com.gluview.app.service.GapBackfillWorker]. Diferente
 * dele (que só cobre 15 dias — vida útil do sensor — rodando à noite por diff contra a nuvem),
 * este cobre TODO o histórico local retido e dispara uma única vez, no login/cadastro.
 */
@Singleton
class HistoryBackfillService @Inject constructor(
    private val preferences: AppPreferences,
    private val glucoseRepository: GlucoseRepository,
    private val appLogger: AppLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget: nunca bloqueia nem derruba quem chama (ex.: o fluxo de login). */
    fun backfillIfNeeded(email: String) {
        scope.launch {
            runCatching { doBackfill(email) }
                .onFailure { e ->
                    appLogger.error(TAG, "Erro inesperado no backfill", mapOf("erro" to (e.message ?: "desconhecido")))
                }
        }
    }

    private suspend fun doBackfill(email: String) {
        if (preferences.localOnlyMode.first()) return
        if (email in preferences.historyBackfillDoneEmails.first()) return

        val readings = glucoseRepository.getRecentReadings(0L).sortedBy { it.dateMs }
        if (readings.isEmpty()) {
            preferences.markHistoryBackfillDone(email)
            return
        }

        for (chunk in readings.chunked(BATCH_SIZE)) {
            val ok = glucoseRepository.sendReadingsToCloud(chunk)
            if (!ok) {
                appLogger.error(TAG, "Falha ao enviar lote — tentando de novo no próximo login", mapOf("qtd" to chunk.size))
                return
            }
        }
        preferences.markHistoryBackfillDone(email)
        appLogger.usage(TAG, "Histórico local enviado ao GluSync", mapOf("qtd" to readings.size))
    }

    private companion object {
        const val TAG = "BACKFILL"
        const val BATCH_SIZE = 4000 // margem sob o cap de 5000/requisição do servidor (MaxBatchSize)
    }
}
