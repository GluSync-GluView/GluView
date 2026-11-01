package com.gluview.app.data.repository

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.AcceptTermsDto
import com.gluview.app.ui.screen.terms.TERMS_TEXT
import com.gluview.app.ui.screen.terms.TERMS_TEXT_EN
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registra no GluSync o aceite dos termos já feito no aparelho. O aceite em si continua local
 * (o app funciona sem conta e sem rede); o registro no servidor é a prova do consentimento para
 * dado de saúde (LGPD art. 11) e acontece assim que existir conta: ao aceitar, se já logado, e
 * quando o serviço inicia depois do login. Falha de rede só adia — tenta de novo na próxima vez.
 */
@Singleton
class TermsRepository @Inject constructor(
    private val api: GluSyncApi,
    private val preferences: AppPreferences
) {
    suspend fun syncAcceptance() {
        if (!preferences.hasAccount.first()) return
        val accepted = preferences.termsAcceptedVersion.first()
        if (accepted <= 0 || preferences.termsRecordedVersion.first() >= accepted) return
        val en = preferences.appLanguage.first().startsWith("en")
        val text = if (en) TERMS_TEXT_EN else TERMS_TEXT
        val ok = runCatching {
            api.acceptTerms(AcceptTermsDto(version = accepted, documentHash = sha256(text), locale = if (en) "en" else "pt-BR"))
                .isSuccessful
        }.getOrDefault(false)
        if (ok) preferences.saveTermsRecordedVersion(accepted)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
