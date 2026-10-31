package com.gluview.app.ui.screen.terms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TermsViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {

    /** Escolhe PT (padrão) ou EN pro texto dos termos, conforme o idioma do app. null = ainda
     *  lendo (antes começava em "pt" e quem usa en via o texto em pt por um instante). */
    val appLanguage: StateFlow<String?> = preferences.appLanguage
        .stateIn<String?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** F7/T56 — registra o aceite (versão atual + data + usuário) e segue. */
    fun accept(onAccepted: () -> Unit) {
        viewModelScope.launch {
            val email = preferences.userEmail.first()
            preferences.saveTermsAccepted(TERMS_VERSION, email)
            onAccepted()
        }
    }
}
