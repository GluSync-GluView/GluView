package com.gluview.app.ui.screen.miband

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.watchface.apply.MiBandClient
import com.gluview.app.watchface.miband.MiBandWatchFacePusher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Resultado da última ação, resumido para a tela (o log técnico fica em "Ver detalhes"). */
enum class MiBandOutcome { CONNECTED, SENT, FAILED, TIMEOUT }

@HiltViewModel
class MiBandViewModel @Inject constructor(
    private val client: MiBandClient,
    private val pusher: MiBandWatchFacePusher,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log = _log.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _outcome = MutableStateFlow<MiBandOutcome?>(null)
    val outcome = _outcome.asStateFlow()

    // Validação no próprio campo (antes virava uma linha do log em pt).
    private val _macError = MutableStateFlow<Int?>(null)
    val macError = _macError.asStateFlow()
    private val _keyError = MutableStateFlow<Int?>(null)
    val keyError = _keyError.asStateFlow()

    private val _mac = MutableStateFlow("")
    val mac = _mac.asStateFlow()
    private val _key = MutableStateFlow("")
    val key = _key.asStateFlow()

    private val _autoSend = MutableStateFlow(false)
    val autoSend = _autoSend.asStateFlow()

    private val _updateEveryReading = MutableStateFlow(false)
    val updateEveryReading = _updateEveryReading.asStateFlow()

    // Agendamento: período especial (horário HH:mm, pode cruzar a meia-noite) com intervalo
    // próprio; fora dele usa o intervalo padrão. Só persiste ao salvar.
    private val _dayIntervalMin = MutableStateFlow("5")
    val dayIntervalMin = _dayIntervalMin.asStateFlow()
    private val _nightIntervalMin = MutableStateFlow("30")
    val nightIntervalMin = _nightIntervalMin.asStateFlow()
    private val _nightStartMin = MutableStateFlow(1320) // 22:00
    val nightStartMin = _nightStartMin.asStateFlow()
    private val _nightEndMin = MutableStateFlow(420) // 07:00
    val nightEndMin = _nightEndMin.asStateFlow()
    private val _scheduleSaved = MutableStateFlow(false)
    val scheduleSaved = _scheduleSaved.asStateFlow()

    init {
        viewModelScope.launch {
            _mac.value = prefs.miBandMac.first()
            _key.value = prefs.miBandAuthKey.first()
            _autoSend.value = prefs.miBandAutoSend.first()
            _updateEveryReading.value = prefs.miBandUpdateEveryReading.first()
            _dayIntervalMin.value = prefs.miBandDayIntervalMin.first().toString()
            _nightIntervalMin.value = prefs.miBandNightIntervalMin.first().toString()
            _nightStartMin.value = prefs.miBandNightStartMin.first()
            _nightEndMin.value = prefs.miBandNightEndMin.first()
        }
    }

    fun onMacChange(v: String) { _mac.value = v; _macError.value = null }
    fun onKeyChange(v: String) { _key.value = v; _keyError.value = null }

    /** Valida MAC e chave; marca o campo culpado. */
    private fun validPairing(mac: String, keyStr: String): Boolean {
        _macError.value = if (MAC_REGEX.matches(mac)) null else R.string.miband_mac_invalid
        _keyError.value = if (keyStr.length == 32 && keyStr.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) null
                          else R.string.miband_key_invalid
        return _macError.value == null && _keyError.value == null
    }

    fun onAutoSendChange(enabled: Boolean) {
        _autoSend.value = enabled
        viewModelScope.launch { prefs.saveMiBandAutoSend(enabled) }
    }

    fun onUpdateEveryReadingChange(enabled: Boolean) {
        _updateEveryReading.value = enabled
        viewModelScope.launch { prefs.saveMiBandUpdateEveryReading(enabled) }
    }

    fun onDayIntervalChange(v: String) { _dayIntervalMin.value = v.filter { it.isDigit() }; _scheduleSaved.value = false }
    fun onNightIntervalChange(v: String) { _nightIntervalMin.value = v.filter { it.isDigit() }; _scheduleSaved.value = false }
    fun onNightStartChange(hour: Int, minute: Int) { _nightStartMin.value = hour * 60 + minute; _scheduleSaved.value = false }
    fun onNightEndChange(hour: Int, minute: Int) { _nightEndMin.value = hour * 60 + minute; _scheduleSaved.value = false }

    fun saveSchedule() {
        val dayMin = _dayIntervalMin.value.toIntOrNull() ?: 5
        val nightMin = _nightIntervalMin.value.toIntOrNull() ?: 30
        viewModelScope.launch {
            prefs.saveMiBandSchedule(dayMin, nightMin, _nightStartMin.value, _nightEndMin.value)
            _scheduleSaved.value = true
        }
    }

    private fun append(s: String) { _log.value = _log.value + s }

    fun testConnection() {
        if (_running.value) return
        val mac = _mac.value.trim()
        val keyStr = _key.value.trim().removePrefix("0x")
        _log.value = emptyList()
        _outcome.value = null
        if (!validPairing(mac, keyStr)) return

        _running.value = true
        viewModelScope.launch {
            prefs.saveMiBandPairing(mac, keyStr)
            val keyBytes = parseHex(keyStr)
            val ok = withTimeoutOrNull(25_000L) {
                client.connectAndAuthenticate(mac, keyBytes) { append(it) }
            }
            if (ok == null) {
                append("⏱️ Tempo esgotado (25s). Confirme que a band está ligada, próxima e com Bluetooth ativo.")
            }
            _outcome.value = when (ok) {
                null -> MiBandOutcome.TIMEOUT
                true -> MiBandOutcome.CONNECTED
                false -> MiBandOutcome.FAILED
            }
            _running.value = false
        }
    }

    /** Salva o pareamento digitado e envia o visor com a glicemia atual (via pusher compartilhado). */
    fun sendCurrentWatchFace() {
        if (_running.value) return
        val mac = _mac.value.trim()
        val keyStr = _key.value.trim().removePrefix("0x")
        _log.value = emptyList()
        _outcome.value = null
        if (!validPairing(mac, keyStr)) return
        _running.value = true
        viewModelScope.launch {
            prefs.saveMiBandPairing(mac, keyStr)
            val ok = pusher.send { append(it) }
            _outcome.value = if (ok) MiBandOutcome.SENT else MiBandOutcome.FAILED
            _running.value = false
        }
    }

    private companion object {
        val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }

    private fun parseHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) + hex[i * 2 + 1].digitToInt(16)).toByte()
        }
}
