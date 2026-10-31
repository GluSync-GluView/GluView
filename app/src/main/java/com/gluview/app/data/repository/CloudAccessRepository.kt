package com.gluview.app.data.repository

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.dto.AccessRequestFeatures
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADR-0030 — liberação da nuvem e do acompanhamento. A conta é grátis e o app funciona 100% no
 * aparelho; enviar glicemia ao GluSync e acompanhar alguém dependem de um pedido aprovado
 * ([AccessRequestFeatures.CLOUD_SYNC]). O servidor é quem bloqueia de verdade (403
 * `feature_not_entitled`); este repositório só guarda o último estado conhecido para o app não
 * tentar enviar à toa e para a tela mostrar "não liberado / em análise / liberado".
 */
@Singleton
class CloudAccessRepository @Inject constructor(
    private val accessRequests: AccessRequestRepository,
    private val preferences: AppPreferences
) {
    /** Consulta o servidor se a última consulta tem mais que [maxAgeMs] (ou nunca houve). Sem
     *  conta, ou se a rede falhar, mantém o estado salvo. */
    suspend fun refreshIfStale(maxAgeMs: Long = REFRESH_INTERVAL_MS) {
        if (!preferences.hasAccount.first()) return
        val age = System.currentTimeMillis() - preferences.cloudSyncCheckedAtMs.first()
        if (preferences.cloudSyncState.first().isNotEmpty() && age in 0 until maxAgeMs) return
        refresh()
    }

    suspend fun refresh(): Result<String> = accessRequests.getStatus().map { states ->
        stateFrom(states).also { preferences.saveCloudSyncState(it) }
    }

    /** O servidor recusou um envio com `feature_not_entitled`: para de tentar até a próxima consulta. */
    suspend fun markNotEntitled() {
        if (preferences.cloudSyncState.first() == STATE_APPROVED) preferences.saveCloudSyncState(STATE_REVOKED)
        else if (preferences.cloudSyncState.first().isEmpty()) preferences.saveCloudSyncState(STATE_NONE)
    }

    /** Pede a liberação "glicemia na nuvem e acompanhamento". */
    suspend fun request(note: String? = null): Result<Unit> =
        accessRequests.createFeatureAccessRequest(AccessRequestFeatures.CLOUD_SYNC, note).map {
            preferences.saveCloudSyncState(STATE_PENDING)
        }

    companion object {
        const val STATE_APPROVED = "approved"
        const val STATE_PENDING  = "pending"
        const val STATE_REVOKED  = "revoked"
        const val STATE_NONE     = "none"
        const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

        /**
         * Função pura (testável): estado do pacote a partir do mapa featureKey → estado do servidor.
         * Envio de glicemia liberado avulso (concessão do admin só de `cloud_upload:glucose`) também
         * conta como liberado para o envio — o acompanhamento, se faltar, o servidor recusa à parte.
         */
        fun stateFrom(states: Map<String, String>): String {
            states[AccessRequestFeatures.CLOUD_SYNC]?.let { if (it == STATE_APPROVED) return it }
            if (states[AccessRequestFeatures.CLOUD_UPLOAD_GLUCOSE] == STATE_APPROVED) return STATE_APPROVED
            return states[AccessRequestFeatures.CLOUD_SYNC] ?: STATE_NONE
        }
    }
}
