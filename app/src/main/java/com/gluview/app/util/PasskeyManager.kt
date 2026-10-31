package com.gluview.app.util

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T85 — encapsula o Android Credential Manager (passkeys). requestJson/responseJson trafegam
 * no formato WebAuthn padrão (o mesmo que o Fido2NetLib do GluSync produz/consome via
 * CredentialCreateOptions.ToJson()/AssertionOptions.ToJson()) — não é um protocolo próprio.
 */
@Singleton
class PasskeyManager @Inject constructor() {

    /** Retorna o registrationResponseJson, ou null se o usuário cancelou o prompt (não é erro). */
    suspend fun createPasskey(context: Context, requestJson: String): String? = try {
        val request = CreatePublicKeyCredentialRequest(requestJson)
        val response = CredentialManager.create(context).createCredential(context, request)
        (response as CreatePublicKeyCredentialResponse).registrationResponseJson
    } catch (e: CreateCredentialCancellationException) {
        null
    } catch (e: CreateCredentialException) {
        throw Exception(friendlyCreateError(e))
    }

    /** Retorna o authenticationResponseJson, ou null se o usuário cancelou o prompt. */
    suspend fun getPasskey(context: Context, requestJson: String): String? = try {
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
        val response = CredentialManager.create(context).getCredential(context, request)
        (response.credential as PublicKeyCredential).authenticationResponseJson
    } catch (e: GetCredentialCancellationException) {
        null
    } catch (e: NoCredentialException) {
        throw Exception("Nenhuma passkey encontrada neste dispositivo")
    } catch (e: GetCredentialException) {
        throw Exception(friendlyGetError(e))
    }

    private fun friendlyCreateError(e: CreateCredentialException): String =
        "Não foi possível criar a passkey neste dispositivo"

    private fun friendlyGetError(e: GetCredentialException): String =
        "Não foi possível usar a passkey neste dispositivo"
}
