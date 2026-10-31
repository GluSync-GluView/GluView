package com.gluview.app.ui.screen.donation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.donation.DonationAmount
import com.gluview.app.data.donation.DonationTracker
import com.gluview.app.data.donation.PendingDonation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Em que ponto da doação a pessoa está. */
sealed interface DonationStep {
    data object Loading : DonationStep
    data object LoadError : DonationStep
    data object Unavailable : DonationStep
    /** Aviso aberto: voluntária, não obrigatória, revertida para o desenvolvimento. */
    data object Notice : DonationStep
    data object Declined : DonationStep
    data object Amount : DonationStep
    data class Pix(val donation: PendingDonation) : DonationStep
    /** Chave fixa: ninguém sabe se foi pago — a tela mostra só a mensagem junto do QR. */
    data class StaticPix(val amountCents: Int, val copiaECola: String, val qrCodeBase64: String) : DonationStep
    data class Thanks(val amountCents: Int) : DonationStep
    data object Expired : DonationStep
}

data class DonationRange(val minCents: Int, val maxCents: Int, val presetCents: List<Int>, val isStatic: Boolean = true)

data class DonationUi(
    val step: DonationStep = DonationStep.Loading,
    val range: DonationRange? = null,
    val creating: Boolean = false,
    val createError: Boolean = false
)

/**
 * Tela "Apoiar o GluView" (plano 09_doacao_pix_plano.md). O aviso aparece toda vez
 * que a tela abre; recusar ou desistir leva à mensagem de que o GluView é 100% gratuito.
 *
 * Modo chave fixa (padrão desde 26/10/2026): o GluSync devolve o copia e cola com a chave fixa e o
 * valor; ninguém sabe se foi pago, então junto do QR vai só a mensagem (decisão do mantenedor). Modo
 * Mercado Pago (guardado): o acompanhamento do Pix fica no [DonationTracker], que sobrevive à tela.
 */
@HiltViewModel
class DonationViewModel @Inject constructor(private val tracker: DonationTracker) : ViewModel() {

    private val _ui = MutableStateFlow(DonationUi())
    val ui: StateFlow<DonationUi> = _ui.asStateFlow()

    init {
        tracker.resume()
        viewModelScope.launch {
            tracker.pending.collect { pending ->
                if (pending != null) _ui.update { it.copy(step = DonationStep.Pix(pending)) }
            }
        }
        viewModelScope.launch {
            tracker.outcome.collect { outcome ->
                if (outcome == null) return@collect
                val step = when (outcome) {
                    is DonationTracker.Outcome.Paid -> DonationStep.Thanks(outcome.amountCents)
                    DonationTracker.Outcome.Expired -> DonationStep.Expired
                    DonationTracker.Outcome.Cancelled -> DonationStep.Declined
                }
                _ui.update { it.copy(step = step) }
                tracker.clearOutcome()
            }
        }
        load()
    }

    fun load() {
        _ui.update { if (it.step is DonationStep.Pix) it else it.copy(step = DonationStep.Loading) }
        viewModelScope.launch {
            val config = tracker.loadConfig()
            _ui.update { ui ->
                val range = config?.takeIf { it.enabled }?.let { c ->
                    DonationRange(c.minCents, c.maxCents, c.presetCents.orEmpty().filter { it in c.minCents..c.maxCents },
                        isStatic = c.mode != "mercadopago")
                }
                // Pix pendente (retomado) não volta para o aviso.
                if (ui.step is DonationStep.Pix) return@update ui.copy(range = range)
                ui.copy(
                    range = range,
                    step = when {
                        config == null -> DonationStep.LoadError
                        range == null -> DonationStep.Unavailable
                        else -> DonationStep.Notice
                    }
                )
            }
        }
    }

    fun accept() = _ui.update { it.copy(step = DonationStep.Amount, createError = false) }
    fun decline() = _ui.update { it.copy(step = DonationStep.Declined) }
    fun showNoticeAgain() = _ui.update { it.copy(step = DonationStep.Notice) }

    /** null = valor válido; senão o texto já parseado ficou fora da faixa ou não é valor. */
    fun validate(input: String, presetCents: Int?): Int? {
        val range = _ui.value.range ?: return null
        val cents = if (input.isNotBlank()) DonationAmount.parseToCents(input) else presetCents
        return cents?.takeIf { it in range.minCents..range.maxCents }
    }

    fun create(amountCents: Int) {
        if (_ui.value.creating) return
        _ui.update { it.copy(creating = true, createError = false) }
        viewModelScope.launch {
            if (_ui.value.range?.isStatic != false) {
                val pix = tracker.createStaticPix(amountCents)
                _ui.update {
                    if (pix == null) it.copy(creating = false, createError = true)
                    else it.copy(creating = false, step = DonationStep.StaticPix(pix.amountCents, pix.copiaECola, pix.qrCodeBase64))
                }
            } else {
                val ok = tracker.create(amountCents)
                _ui.update { it.copy(creating = false, createError = !ok) }
            }
        }
    }

    fun giveUp() { viewModelScope.launch { tracker.cancel() } }

    /** A tela voltou ao primeiro plano (ex.: do app do banco): confere o Pix na hora. */
    fun onResume() = tracker.checkNow()
}

/** Só a entrada nos Ajustes: aparece com a doação ligada no GluSync. */
@HiltViewModel
class DonationEntryViewModel @Inject constructor(private val tracker: DonationTracker) : ViewModel() {
    val enabled: StateFlow<Boolean> = tracker.enabled

    init { viewModelScope.launch { tracker.loadConfig() } }
}
