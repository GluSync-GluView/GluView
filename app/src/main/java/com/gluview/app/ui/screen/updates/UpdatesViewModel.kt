package com.gluview.app.ui.screen.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.BuildConfig
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.update.UpdateChecker
import com.gluview.app.data.update.UpdateInstaller
import com.gluview.app.data.update.UpdatePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** O que a última checagem manual disse (para a mensagem logo abaixo do botão). */
enum class CheckMessage { UP_TO_DATE, FAILED, NO_SERVER, UNSUPPORTED_ANDROID }

data class UpdatesUi(
    val installedName: String = BuildConfig.VERSION_NAME,
    val installedCode: Long = BuildConfig.VERSION_CODE.toLong(),
    val lastCheckMs: Long = 0L,
    /** Versão mais nova conhecida no servidor, se for maior que a instalada. */
    val available: UpdatePreferences.KnownLatest? = null,
    val autoCheck: Boolean = true,
    val checking: Boolean = false,
    val checkMessage: CheckMessage? = null,
    val install: UpdateInstaller.State = UpdateInstaller.State.Idle,
    val lostPermissions: Set<String> = emptySet(),
    val localOnly: Boolean = false
)

/** Tela Atualizações (plano OTA, E6/E7). Toda instalação passa por aqui: nada instala sozinho. */
@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val installer: UpdateInstaller,
    private val updatePreferences: UpdatePreferences,
    preferences: AppPreferences
) : ViewModel() {

    private val checking = MutableStateFlow(false)
    private val checkMessage = MutableStateFlow<CheckMessage?>(null)

    private data class Stored(
        val lastCheckMs: Long, val latest: UpdatePreferences.KnownLatest?, val autoCheck: Boolean,
        val lost: Set<String>, val localOnly: Boolean
    )

    private val stored = combine(
        updatePreferences.lastCheckMs, updatePreferences.knownLatest, updatePreferences.autoCheck,
        updatePreferences.lostPermissions, preferences.localOnlyMode
    ) { last, latest, auto, lost, local -> Stored(last, latest, auto, lost, local) }

    val ui: StateFlow<UpdatesUi> = combine(stored, checking, checkMessage, installer.state) { s, busy, msg, install ->
        UpdatesUi(
            lastCheckMs = s.lastCheckMs,
            available = s.latest?.takeIf { it.versionCode > BuildConfig.VERSION_CODE },
            autoCheck = s.autoCheck,
            checking = busy,
            checkMessage = msg,
            install = install,
            lostPermissions = s.lost,
            localOnly = s.localOnly
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdatesUi())

    fun checkNow() {
        if (checking.value) return
        viewModelScope.launch {
            checking.value = true
            checkMessage.value = when (checker.check(force = true)) {
                is UpdateChecker.Result.Available -> null
                UpdateChecker.Result.UpToDate -> CheckMessage.UP_TO_DATE
                UpdateChecker.Result.NoServer -> CheckMessage.NO_SERVER
                is UpdateChecker.Result.UnsupportedAndroid -> CheckMessage.UNSUPPORTED_ANDROID
                is UpdateChecker.Result.Failed -> CheckMessage.FAILED
                UpdateChecker.Result.Skipped -> null
            }
            checking.value = false
        }
    }

    fun update() {
        val target = ui.value.available ?: return
        installer.start(target)
    }

    /** Pergunta do porteiro: "Atualizar agora" / "Mais tarde". */
    fun confirmNow() = installer.confirm()
    fun postpone() = installer.postpone()

    fun continueAfterPermission() = installer.continueAfterPermission()
    fun onUserActionLaunched() = installer.onUserActionLaunched()
    fun resetInstall() = installer.reset()
    fun setAutoCheck(enabled: Boolean) { viewModelScope.launch { updatePreferences.setAutoCheck(enabled) } }
    fun dismissLostPermissions() { viewModelScope.launch { updatePreferences.setLostPermissions(emptySet()) } }
}
