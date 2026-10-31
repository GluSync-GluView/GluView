package com.gluview.app.data.donation

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gluview.app.data.remote.dto.CreateDonationRequestDto
import com.gluview.app.data.remote.dto.DonationConfigDto
import com.gluview.app.data.remote.dto.StaticPixDto
import com.gluview.app.data.remote.dto.StaticPixRequestDto
import com.gluview.app.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Pix gerado e ainda não resolvido. */
data class PendingDonation(
    val id: String,
    val clientToken: String,
    val amountCents: Int,
    val qrCodeBase64: String,
    val copiaECola: String,
    val expiresAtMs: Long
)

/**
 * Doação via Pix (plano 09_doacao_pix_plano.md) — acompanha o Pix fora da tela.
 *
 * Quem doa copia o código e vai para o app do banco: a tela some, mas o processo continua (o
 * serviço do sensor o mantém vivo). Por isso o acompanhamento mora aqui, num singleton com escopo
 * próprio, e não no ViewModel. O Pix pendente fica no DataStore (fora do backup JSON, como as
 * chaves update_*): se o processo morrer, a abertura do app ([resume]) volta a acompanhar.
 *
 * Consulta o GluSync a cada [POLL_MS] até o Pix ser pago, vencer ou ser cancelado. Pago →
 * notificação de agradecimento neste celular, mesmo com o app em segundo plano.
 */
@Singleton
class DonationTracker @Inject constructor(
    private val api: DonationApi,
    private val dataStore: DataStore<Preferences>,
    private val notifications: NotificationHelper
) {
    sealed interface Outcome {
        data class Paid(val amountCents: Int) : Outcome
        data object Expired : Outcome
        data object Cancelled : Outcome
    }

    private companion object {
        const val TAG = "DONATION"
        const val POLL_MS = 5_000L
        /** Folga depois do vencimento antes de desistir de consultar (o servidor fecha o resto). */
        const val EXPIRY_GRACE_MS = 2 * 60_000L
        val KEY_ID      = stringPreferencesKey("donation_pending_id")
        val KEY_TOKEN   = stringPreferencesKey("donation_pending_token")
        val KEY_AMOUNT  = intPreferencesKey("donation_pending_amount_cents")
        val KEY_QR      = stringPreferencesKey("donation_pending_qr")
        val KEY_CODE    = stringPreferencesKey("donation_pending_code")
        val KEY_EXPIRES = stringPreferencesKey("donation_pending_expires_ms")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pollJob: Job? = null

    private val _pending = MutableStateFlow<PendingDonation?>(null)
    val pending: StateFlow<PendingDonation?> = _pending.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)
    /** Como o último Pix terminou — a tela mostra e depois limpa com [clearOutcome]. */
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    /** A entrada "Apoiar o GluView" nos Ajustes só aparece com a doação ligada no GluSync. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** null = não deu para saber (sem internet, servidor dormindo demais). */
    suspend fun loadConfig(): DonationConfigDto? =
        runCatching { api.config() }
            .onSuccess { _enabled.value = it.enabled }
            .onFailure { Log.w(TAG, "config falhou: ${it.javaClass.simpleName}") }
            .getOrNull()

    /** Abertura do app e da tela: retoma um Pix que ficou pendente. */
    fun resume() {
        scope.launch {
            lock.withLock {
                if (_pending.value == null) _pending.value = readStored()
            }
            if (_pending.value != null) startPolling()
        }
    }

    /** Modo chave fixa: só o copia e cola e o QR, nada a acompanhar. null = falhou agora. */
    suspend fun createStaticPix(amountCents: Int): StaticPixDto? =
        runCatching { api.staticPix(StaticPixRequestDto(amountCents)) }
            .onFailure { Log.w(TAG, "pix fixo falhou: ${it.javaClass.simpleName}") }
            .getOrNull()

    /** Modo Mercado Pago: gera a cobrança. false = o GluSync/Mercado Pago não conseguiu agora. */
    suspend fun create(amountCents: Int): Boolean {
        val created = runCatching { api.create(CreateDonationRequestDto(amountCents)) }
            .onFailure { Log.w(TAG, "criar falhou: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return false
        val donation = PendingDonation(
            id = created.id,
            clientToken = created.clientToken,
            amountCents = created.amountCents,
            qrCodeBase64 = created.qrCodeBase64,
            copiaECola = created.copiaECola,
            expiresAtMs = parseInstantMs(created.expiresAt)
        )
        lock.withLock {
            store(donation)
            _outcome.value = null
            _pending.value = donation
        }
        startPolling()
        return true
    }

    /** "Desistir": cancela no servidor (o QR deixa de valer). Pago no mesmo instante → vale o pagamento. */
    suspend fun cancel() {
        val d = _pending.value ?: return
        val status = runCatching { api.cancel(d.id, d.clientToken).status }.getOrNull()
        finish(d, if (status == "paid") "paid" else "cancelled")
    }

    /** Volta para a tela (ex.: do app do banco): consulta na hora, sem esperar o ciclo. */
    fun checkNow() {
        val d = _pending.value ?: return
        scope.launch { check(d) }
    }

    fun clearOutcome() { _outcome.value = null }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val d = _pending.value ?: break
                check(d)
                if (_pending.value == null) break
                if (System.currentTimeMillis() > d.expiresAtMs + EXPIRY_GRACE_MS) {
                    finish(d, "expired")
                    break
                }
                delay(POLL_MS)
            }
        }
    }

    private suspend fun check(d: PendingDonation) {
        try {
            val status = api.status(d.id, d.clientToken).status
            if (status != "pending") finish(d, status)
        } catch (e: HttpException) {
            // 404: o servidor não conhece mais esta doação (ou doações foram desligadas). Para de
            // acompanhar sem mostrar nada — não há como saber se foi paga.
            if (e.code() == 404) lock.withLock { if (_pending.value?.id == d.id) clearStored() }
        } catch (e: Exception) {
            Log.w(TAG, "status falhou: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun finish(d: PendingDonation, status: String) {
        val outcome = when (status) {
            "paid" -> Outcome.Paid(d.amountCents)
            "expired" -> Outcome.Expired
            else -> Outcome.Cancelled
        }
        lock.withLock {
            if (_pending.value?.id != d.id) return
            clearStored()
            _outcome.value = outcome
        }
        if (outcome is Outcome.Paid) notifications.showDonationThanks(DonationAmount.format(outcome.amountCents))
        Log.i(TAG, "doação ${d.id.take(8)} terminou: $status")
    }

    private suspend fun store(d: PendingDonation) = dataStore.edit {
        it[KEY_ID] = d.id
        it[KEY_TOKEN] = d.clientToken
        it[KEY_AMOUNT] = d.amountCents
        it[KEY_QR] = d.qrCodeBase64
        it[KEY_CODE] = d.copiaECola
        it[KEY_EXPIRES] = d.expiresAtMs.toString()
    }

    private suspend fun clearStored() {
        _pending.value = null
        dataStore.edit { p -> listOf(KEY_ID, KEY_TOKEN, KEY_QR, KEY_CODE, KEY_EXPIRES).forEach { p.remove(it) }; p.remove(KEY_AMOUNT) }
    }

    private suspend fun readStored(): PendingDonation? {
        val p = runCatching { dataStore.data.first() }.getOrNull() ?: return null
        return PendingDonation(
            id = p[KEY_ID] ?: return null,
            clientToken = p[KEY_TOKEN] ?: return null,
            amountCents = p[KEY_AMOUNT] ?: return null,
            qrCodeBase64 = p[KEY_QR] ?: return null,
            copiaECola = p[KEY_CODE] ?: return null,
            expiresAtMs = p[KEY_EXPIRES]?.toLongOrNull() ?: return null
        )
    }

    private fun parseInstantMs(iso: String): Long =
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrElse { System.currentTimeMillis() + 30 * 60_000L }
}
