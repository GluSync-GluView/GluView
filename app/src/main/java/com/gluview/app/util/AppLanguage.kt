package com.gluview.app.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Contexto no idioma escolhido em Ajustes ([lang], ex.: "pt", "en"). Para o que sai fora das
 * Activities — notificação, widgets, exportação: no Android < 13 o contexto da aplicação e o do
 * serviço não seguem o AppCompatDelegate, e o texto sairia no idioma do SISTEMA.
 */
fun Context.withAppLanguage(lang: String): Context {
    val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
    return createConfigurationContext(config)
}
