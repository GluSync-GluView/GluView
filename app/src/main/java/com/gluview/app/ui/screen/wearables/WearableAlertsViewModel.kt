package com.gluview.app.ui.screen.wearables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.wearables.hub.WearableFamily
import com.gluview.app.wearables.hub.WearablesHub
import com.gluview.wearables.core.AlertRule
import com.gluview.wearables.core.WearableAlertKind
import com.gluview.wearables.core.WearableAlertSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de um tipo de aparelho na tela. [result]: null, "ok", "find_ok" ou o motivo da falha. */
data class WearableFamilyUi(
    val family: WearableFamily,
    val settings: WearableAlertSettings,
    val configured: Boolean,
    val busy: Boolean = false,
    val result: String? = null
)

data class WearableAlertsUi(val families: List<WearableFamilyUi>, val snoozeMinutes: Int)

/** Plano 21, F7.1 — tela "Alertas no pulso e no relógio". */
@HiltViewModel
class WearableAlertsViewModel @Inject constructor(
    private val hub: WearablesHub,
    private val prefs: AppPreferences
) : ViewModel() {
    private val configured = MutableStateFlow<Map<WearableFamily, Boolean>>(emptyMap())
    private val busy = MutableStateFlow<Set<WearableFamily>>(emptySet())
    private val results = MutableStateFlow<Map<WearableFamily, String>>(emptyMap())

    private val settings = combine(WearableFamily.entries.map { f -> hub.settings(f) }) { arr ->
        WearableFamily.entries.zip(arr.toList()).toMap()
    }

    val ui: StateFlow<WearableAlertsUi> = combine(settings, configured, busy, results, prefs.wearableSnoozeMinutes) { s, c, b, r, snooze ->
        WearableAlertsUi(WearableFamily.entries.map { f ->
            WearableFamilyUi(f, s[f] ?: WearableAlertSettings.defaults(f.supportsCall), c[f] ?: false, f in b, r[f])
        }, snooze)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
        WearableAlertsUi(WearableFamily.entries.map { WearableFamilyUi(it, WearableAlertSettings.defaults(it.supportsCall), false) }, 30))

    init { refreshConfigured() }

    fun refreshConfigured() {
        viewModelScope.launch { configured.value = WearableFamily.entries.associateWith { hub.configured(it) } }
    }

    fun setEnabled(f: WearableFamily, on: Boolean) = update(f) { it.copy(enabled = on) }

    fun setRule(f: WearableFamily, kind: WearableAlertKind, rule: AlertRule) = update(f) { it.with(kind, rule) }

    fun setSnooze(minutes: Int) { viewModelScope.launch { prefs.saveWearableSnoozeMinutes(minutes) } }

    fun test(f: WearableFamily, title: String, body: String) = run(f) {
        val style = hub.currentSettings(f).ruleFor(WearableAlertKind.LOW).style
        if (hub.test(f, style, title, body)) "ok" else "fail"
    }

    fun find(f: WearableFamily) = run(f) { if (hub.find(f)) "find_ok" else "fail" }

    private fun update(f: WearableFamily, change: (WearableAlertSettings) -> WearableAlertSettings) {
        viewModelScope.launch { hub.save(f, change(hub.currentSettings(f))) }
    }

    private fun run(f: WearableFamily, block: suspend () -> String) {
        if (f in busy.value) return
        viewModelScope.launch {
            busy.value = busy.value + f
            results.value = results.value - f
            val r = runCatching { block() }.getOrElse { "fail" }
            results.value = results.value + (f to r)
            busy.value = busy.value - f
        }
    }
}
