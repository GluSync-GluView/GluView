package com.gluview.app.ui.screen.caregiver

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.remote.dto.CaregiverLinkViewDto
import com.gluview.app.data.repository.CaregiverRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Um vínculo já classificado do ponto de vista do usuário logado. */
data class CaregiverItem(
    val link: CaregiverLinkViewDto,
    val otherName: String,   // nome/e-mail da outra ponta
    val patientId: String,
    val iAmFollower: Boolean
)

data class CaregiverUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val invitesReceived: List<CaregiverItem> = emptyList(),
    val invitesSent: List<CaregiverItem> = emptyList(),
    val following: List<CaregiverItem> = emptyList(),  // pacientes que sigo (accepted)
    val followers: List<CaregiverItem> = emptyList()   // quem me segue (accepted)
)

@HiltViewModel
class CaregiverViewModel @Inject constructor(
    private val repo: CaregiverRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _ui = MutableStateFlow(CaregiverUiState())
    val ui: StateFlow<CaregiverUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                val myId = repo.me().id
                val links = repo.links()
                myId to links
            }.onSuccess { (myId, links) ->
                val received = mutableListOf<CaregiverItem>()
                val sent = mutableListOf<CaregiverItem>()
                val following = mutableListOf<CaregiverItem>()
                val followers = mutableListOf<CaregiverItem>()
                for (l in links) {
                    val iAmFollower = l.iAmFollower(myId)
                    val item = CaregiverItem(l, l.otherPartyName(myId), l.patientId, iAmFollower)
                    when (l.status) {
                        "accepted" -> if (iAmFollower) following += item else followers += item
                        "pending"  -> if (l.initiatedBy == myId) sent += item else received += item
                        else -> {} // rejected/revoked: oculto
                    }
                }
                _ui.update {
                    it.copy(
                        loading = false,
                        invitesReceived = received,
                        invitesSent = sent,
                        following = following,
                        followers = followers
                    )
                }
            }.onFailure { e ->
                _ui.update { it.copy(loading = false, error = friendly(e)) }
            }
        }
    }

    fun invite(email: String, asFollower: Boolean) {
        viewModelScope.launch {
            runCatching { repo.invite(email, if (asFollower) "follower" else "patient") }
                .onSuccess {
                    _ui.update { it.copy(message = "Convite enviado.") }
                    refresh()
                }
                .onFailure { e -> _ui.update { it.copy(message = "Falha ao convidar: ${friendly(e)}") } }
        }
    }

    fun respond(linkId: String, accept: Boolean) {
        viewModelScope.launch {
            runCatching { repo.respond(linkId, accept) }
                .onSuccess {
                    _ui.update { it.copy(message = if (accept) "Convite aceito." else "Convite recusado.") }
                    refresh()
                }
                .onFailure { e -> _ui.update { it.copy(message = friendly(e)) } }
        }
    }

    fun revoke(linkId: String) {
        viewModelScope.launch {
            runCatching { repo.revoke(linkId) }
                .onSuccess {
                    _ui.update { it.copy(message = "Acesso removido.") }
                    refresh()
                }
                .onFailure { e -> _ui.update { it.copy(message = friendly(e)) } }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    private fun friendly(e: Throwable): String = when {
        // ADR-0030 — convite novo exige o acompanhamento liberado para o PACIENTE do vínculo.
        (e as? retrofit2.HttpException)?.let {
            it.code() == 403 && it.response()?.errorBody()?.string()?.contains("feature_not_entitled") == true
        } == true -> context.getString(R.string.caregiver_follow_not_entitled)
        e.message?.contains("401") == true || e.message?.contains("403") == true ->
            "Entre na sua conta do servidor (GluSync) para usar o acompanhamento."
        else -> e.message ?: "Erro de rede."
    }
}
