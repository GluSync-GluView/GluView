package com.gluview.app.ui.screen.insulinpump

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.remote.dto.AccessRequestFeatures
import com.gluview.app.data.repository.AccessRequestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** ADR-0029 — estado da candidatura de beta da bomba de insulina. "approved" aqui é só a lista
 *  de interesse do beta (feature_key=insulin_pump_beta) — NUNCA autoriza nenhuma capacidade real
 *  de dosagem futura, que exigirá sua própria feature_key e concessão deliberada quando existir. */
/** ERROR = não deu para consultar (L6: antes a falha virava NONE e oferecia "Pedir acesso" de novo). */
enum class BetaState { NONE, PENDING, WAITLISTED, APPROVED, REJECTED, LOADING, ERROR }

data class InsulinPumpUiState(
    val betaState: BetaState = BetaState.LOADING,
    val submitting: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class InsulinPumpViewModel @Inject constructor(
    private val accessRequestRepository: AccessRequestRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(InsulinPumpUiState())
    val uiState: StateFlow<InsulinPumpUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(betaState = BetaState.LOADING)
            accessRequestRepository.getStatus().fold(
                onSuccess = { statuses ->
                    val state = statuses[AccessRequestFeatures.INSULIN_PUMP_BETA].toBetaState()
                    _uiState.value = _uiState.value.copy(betaState = state, error = null)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(betaState = BetaState.ERROR, error = e.message ?: e.javaClass.simpleName)
                }
            )
        }
    }

    fun applyForBeta() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(submitting = true, error = null)
            accessRequestRepository.createFeatureAccessRequest(AccessRequestFeatures.INSULIN_PUMP_BETA).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(submitting = false, betaState = BetaState.PENDING)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        // Só o detalhe técnico: a tela põe a frase traduzida.
                        submitting = false, error = e.message ?: e.javaClass.simpleName
                    )
                }
            )
        }
    }
}

private fun String?.toBetaState(): BetaState = when (this) {
    "pending"    -> BetaState.PENDING
    "waitlisted" -> BetaState.WAITLISTED
    "approved"   -> BetaState.APPROVED
    "rejected"   -> BetaState.REJECTED
    else         -> BetaState.NONE // ausente, "none" ou "revoked" (ver AccessRequestService.GetMyStatusAsync)
}
