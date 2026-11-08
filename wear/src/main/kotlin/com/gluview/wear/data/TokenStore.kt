package com.gluview.wear.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gluview.wear.contract.WearToken

/**
 * Token de acesso ao GluSync entregue pelo celular (plano B da Fase 7), guardado cifrado
 * (EncryptedSharedPreferences, mesmo padrão do celular — achado #3 da auditoria de segurança).
 * Sem tela de login no relógio: sem token, o relógio só recebe pelo celular.
 */
object TokenStore {
    private const val FILE = "gluview_token"

    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(context: Context, token: WearToken) {
        if (token.accessToken.isBlank()) return clear(context)
        prefs(context).edit()
            .putString(WearToken.K_TOKEN, token.accessToken)
            .putString(WearToken.K_BASE_URL, token.baseUrl)
            .putLong(WearToken.K_EXPIRES, token.expiresAtMs)
            .apply()
    }

    fun load(context: Context): WearToken? = runCatching {
        val p = prefs(context)
        val token = p.getString(WearToken.K_TOKEN, null) ?: return null
        WearToken(token, p.getString(WearToken.K_BASE_URL, "").orEmpty(), p.getLong(WearToken.K_EXPIRES, 0L))
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { prefs(context).edit().clear().apply() }
    }
}
