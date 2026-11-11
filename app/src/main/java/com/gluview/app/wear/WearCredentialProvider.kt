package com.gluview.app.wear

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gluview.app.data.remote.GluSyncApi
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Resposta de POST /api/glusync/v1/wear/credential (GluSync). */
data class WearCredentialDto(
    @SerializedName("key") val key: String,
    @SerializedName("expiresAt") val expiresAtMs: Long,
)

/**
 * Credencial própria do relógio Wear OS ("buscar na nuvem", plano 14): uma API Key do GluSync só de
 * leitura, que vale 30 dias. Antes o relógio recebia o token de acesso do celular, que vence em 1 hora —
 * com o celular longe, o relógio parava de buscar a glicemia depois disso.
 *
 * Guardada cifrada aqui no celular e renovada quando faltam menos de [RENEW_BEFORE_MS]. Se o GluSync
 * não responder (sem rede, servidor antigo sem a rota), devolve a que ainda vale ou null — e o
 * [WearBridge] volta a mandar o token curto, como antes. Falha não é repetida antes de [RETRY_MS]
 * (o envio ao relógio acontece a cada leitura).
 */
@Singleton
class WearCredentialProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GluSyncApi,
) {
    private val mutex = Mutex()
    @Volatile private var lastFailureAtMs = 0L

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "gluview_wear_credential", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** A credencial do relógio (chave, validade em ms), pedindo uma nova ao GluSync quando precisa. */
    suspend fun current(nowMs: Long = System.currentTimeMillis()): Pair<String, Long>? = mutex.withLock {
        val saved = runCatching { prefs.getString(K_KEY, null) to prefs.getLong(K_EXPIRES, 0L) }.getOrNull()
        val stillValid = saved?.takeIf { (k, exp) -> !k.isNullOrBlank() && exp - nowMs > MIN_VALID_MS }
            ?.let { (k, exp) -> k!! to exp }
        if (stillValid != null && stillValid.second - nowMs > RENEW_BEFORE_MS) return@withLock stillValid
        if (nowMs - lastFailureAtMs < RETRY_MS) return@withLock stillValid

        runCatching { api.createWearCredential() }
            .onSuccess { dto ->
                if (dto.key.isNotBlank() && dto.expiresAtMs > nowMs) {
                    runCatching { prefs.edit().putString(K_KEY, dto.key).putLong(K_EXPIRES, dto.expiresAtMs).apply() }
                    Log.i(TAG, "Credencial do relógio renovada")
                    return@withLock dto.key to dto.expiresAtMs
                }
            }
            .onFailure { Log.w(TAG, "Sem credencial nova para o relógio: ${it.message}") }
        lastFailureAtMs = nowMs
        stillValid
    }

    /** Esquece a credencial (logout, modo só-local). O relógio recebe "apague" pelo WearBridge. */
    fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    companion object {
        private const val TAG = "WearCredential"
        private const val K_KEY = "key"
        private const val K_EXPIRES = "expiresAt"
        /** Renova quando faltam menos de 7 dias (a chave vale 30). */
        const val RENEW_BEFORE_MS = 7L * 24 * 60 * 60 * 1000
        /** Depois de uma falha, só tenta pedir de novo em 30 min. */
        const val RETRY_MS = 30L * 60 * 1000
        private const val MIN_VALID_MS = 60L * 60 * 1000
    }
}
