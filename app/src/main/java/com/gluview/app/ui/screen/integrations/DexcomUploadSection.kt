package com.gluview.app.ui.screen.integrations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.integration.DexcomShareUploader
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class DexcomUploadUi(
    val enabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    val hasSavedPassword: Boolean = false,
    val serial: String = "",
    val us: Boolean = false,
    val status: String = "",
    val statusOk: Boolean = false,
    val testing: Boolean = false,
    val testResult: String? = null
)

/** F8.3 — envio ao Dexcom Share (API não oficial, desligado por padrão, decisão do Felipe 21/11/2026). */
@HiltViewModel
class DexcomUploadViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val uploader: DexcomShareUploader
) : ViewModel() {
    private val _ui = MutableStateFlow(DexcomUploadUi())
    val ui: StateFlow<DexcomUploadUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            _ui.update {
                it.copy(enabled = preferences.dexcomUploadEnabled.first(), username = preferences.dexcomUploadUsername.first(),
                    serial = preferences.dexcomUploadReceiverSerial.first(), us = preferences.dexcomUploadUs.first(),
                    hasSavedPassword = preferences.dexcomUploadPassword().isNotBlank())
            }
        }
        viewModelScope.launch {
            preferences.dexcomUploadStatus.collect { (ms, text, ok) ->
                val s = if (ms > 0) "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))} — $text" else ""
                _ui.update { it.copy(status = s, statusOk = ok) }
            }
        }
    }

    fun onUsername(v: String) = _ui.update { it.copy(username = v) }
    fun onPassword(v: String) = _ui.update { it.copy(password = v) }
    fun onSerial(v: String) = _ui.update { it.copy(serial = v.filter { c -> c.isLetterOrDigit() }.take(16)) }
    fun onUs(v: Boolean) { _ui.update { it.copy(us = v) }; save() }
    fun onEnabled(v: Boolean) { _ui.update { it.copy(enabled = v) }; save() }

    fun save() {
        val s = _ui.value
        viewModelScope.launch {
            preferences.saveDexcomUpload(s.enabled, s.username, s.password.takeIf { it.isNotEmpty() }, s.serial, s.us)
            if (s.password.isNotEmpty()) _ui.update { it.copy(password = "", hasSavedPassword = true) }
        }
    }

    fun test() {
        save()
        viewModelScope.launch {
            _ui.update { it.copy(testing = true, testResult = null) }
            val r = uploader.testLogin()
            _ui.update { it.copy(testing = false, testResult = r) }
        }
    }
}

@Composable
fun DexcomUploadSection(vm: DexcomUploadViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    GluSection(stringResource(R.string.dexcom_upload_title), description = stringResource(R.string.dexcom_upload_desc)) {
        GluCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GluCallout(stringResource(R.string.dexcom_upload_disclaimer), tone = Tone.Warning,
                    title = stringResource(R.string.dexcom_upload_disclaimer_title))
                SwitchRow(stringResource(R.string.dexcom_upload_switch), ui.enabled, vm::onEnabled,
                    description = stringResource(R.string.dexcom_upload_switch_desc))
                OutlinedTextField(ui.username, vm::onUsername, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dexcom_upload_user)) })
                OutlinedTextField(ui.password, vm::onPassword, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(if (ui.hasSavedPassword) R.string.dexcom_upload_password_saved else R.string.dexcom_upload_password)) })
                OutlinedTextField(ui.serial, vm::onSerial, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dexcom_upload_serial)) },
                    supportingText = { Text(stringResource(R.string.dexcom_upload_serial_help)) })
                SwitchRow(stringResource(R.string.dexcom_upload_us), ui.us, vm::onUs)
                Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dexcom_upload_save)) }
                OutlinedButton(onClick = vm::test, enabled = !ui.testing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dexcom_upload_test))
                }
                ui.testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (ui.status.isNotBlank()) {
                    Text(stringResource(R.string.dexcom_upload_last, ui.status), style = MaterialTheme.typography.bodySmall,
                        color = if (ui.statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
