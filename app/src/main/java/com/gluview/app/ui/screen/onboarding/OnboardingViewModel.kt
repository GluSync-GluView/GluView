package com.gluview.app.ui.screen.onboarding

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.datastore.deviceLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {

    // L21: o idioma já começa pelo aparelho; o primeiro slide deixa trocar.
    val language: StateFlow<String> = preferences.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), deviceLanguage())

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            preferences.saveAppLanguage(lang)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        }
    }

    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            preferences.saveOnboardingDone()
            onDone()
        }
    }
}
