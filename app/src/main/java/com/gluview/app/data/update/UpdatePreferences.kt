package com.gluview.app.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Estado persistido do atualizador (plano OTA, seção 5.5 "Preferências novas"). Mesmo DataStore
 * do app, chaves próprias com prefixo update_. Ficam FORA do backup JSON (AppPreferences.toJsonString
 * só exporta uma lista explícita), porque descrevem este aparelho, não a configuração do usuário.
 */
@Singleton
class UpdatePreferences @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private companion object {
        val KEY_AUTO_CHECK          = booleanPreferencesKey("update_auto_check")
        val KEY_LAST_CHECK_MS       = longPreferencesKey("update_last_check_ms")
        val KEY_LATEST_CODE         = longPreferencesKey("update_latest_version_code")
        val KEY_LATEST_NAME         = stringPreferencesKey("update_latest_version_name")
        val KEY_LATEST_SIZE         = longPreferencesKey("update_latest_size_bytes")
        val KEY_LATEST_MIN_SDK      = longPreferencesKey("update_latest_min_sdk")
        val KEY_LATEST_SECURITY     = booleanPreferencesKey("update_latest_security")
        val KEY_NOTIFIED_CODE       = longPreferencesKey("update_notified_version_code")
        val KEY_PENDING_CODE        = longPreferencesKey("update_pending_version_code")
        val KEY_PENDING_FROM_CODE   = longPreferencesKey("update_pending_from_version_code")
        val KEY_PENDING_SINCE_MS    = longPreferencesKey("update_pending_since_ms")
        val KEY_PERMISSION_SNAPSHOT = stringSetPreferencesKey("update_permission_snapshot")
        val KEY_LOST_PERMISSIONS    = stringSetPreferencesKey("update_lost_permissions")
        val KEY_LAST_RESULT         = stringPreferencesKey("update_last_result")
        val KEY_UPDATED_AT_MS       = longPreferencesKey("update_confirmed_at_ms")
    }

    /** Quando a última atualização foi confirmada no processo novo (0 = nunca). O worker de
     *  verificação usa isto, porque a reconciliação na abertura do app já limpa o marcador. */
    suspend fun confirmedAtMs(): Long = dataStore.data.first()[KEY_UPDATED_AT_MS] ?: 0L
    suspend fun setConfirmedAtMs(ms: Long) = dataStore.edit { it[KEY_UPDATED_AT_MS] = ms }

    /** Última versão conhecida no servidor (null = nenhuma/nunca checou). */
    data class KnownLatest(
        val versionCode: Long,
        val versionName: String,
        val sizeBytes: Long,
        val minSdk: Int,
        val security: Boolean
    )

    /** Atualização em andamento: gravado ANTES do commit da sessão, porque o processo morre na
     *  instalação e o resultado pode nunca chegar ao processo novo. */
    data class Pending(val targetCode: Long, val fromCode: Long, val sinceMs: Long)

    private fun <T> Flow<Preferences>.safe(default: T, f: (Preferences) -> T): Flow<T> =
        catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }.map { runCatching { f(it) }.getOrDefault(default) }

    val autoCheck: Flow<Boolean> = dataStore.data.safe(true) { it[KEY_AUTO_CHECK] ?: true }
    val lastCheckMs: Flow<Long> = dataStore.data.safe(0L) { it[KEY_LAST_CHECK_MS] ?: 0L }
    val lostPermissions: Flow<Set<String>> = dataStore.data.safe(emptySet()) { it[KEY_LOST_PERMISSIONS] ?: emptySet() }
    val lastResult: Flow<String?> = dataStore.data.safe(null) { it[KEY_LAST_RESULT] }
    val knownLatest: Flow<KnownLatest?> = dataStore.data.safe(null) { p ->
        val code = p[KEY_LATEST_CODE] ?: return@safe null
        KnownLatest(code, p[KEY_LATEST_NAME] ?: "", p[KEY_LATEST_SIZE] ?: 0L,
            (p[KEY_LATEST_MIN_SDK] ?: 0L).toInt(), p[KEY_LATEST_SECURITY] ?: false)
    }

    suspend fun setAutoCheck(enabled: Boolean) = dataStore.edit { it[KEY_AUTO_CHECK] = enabled }

    suspend fun saveCheck(nowMs: Long, latest: KnownLatest?) = dataStore.edit {
        it[KEY_LAST_CHECK_MS] = nowMs
        if (latest == null) {
            it.remove(KEY_LATEST_CODE); it.remove(KEY_LATEST_NAME); it.remove(KEY_LATEST_SIZE)
            it.remove(KEY_LATEST_MIN_SDK); it.remove(KEY_LATEST_SECURITY)
        } else {
            it[KEY_LATEST_CODE] = latest.versionCode
            it[KEY_LATEST_NAME] = latest.versionName
            it[KEY_LATEST_SIZE] = latest.sizeBytes
            it[KEY_LATEST_MIN_SDK] = latest.minSdk.toLong()
            it[KEY_LATEST_SECURITY] = latest.security
        }
    }

    suspend fun notifiedCode(): Long = dataStore.data.first()[KEY_NOTIFIED_CODE] ?: 0L
    suspend fun setNotifiedCode(code: Long) = dataStore.edit { it[KEY_NOTIFIED_CODE] = code }

    suspend fun pending(): Pending? {
        val p = dataStore.data.first()
        val target = p[KEY_PENDING_CODE] ?: return null
        return Pending(target, p[KEY_PENDING_FROM_CODE] ?: 0L, p[KEY_PENDING_SINCE_MS] ?: 0L)
    }

    suspend fun savePending(pending: Pending, permissionSnapshot: Set<String>) = dataStore.edit {
        it[KEY_PENDING_CODE] = pending.targetCode
        it[KEY_PENDING_FROM_CODE] = pending.fromCode
        it[KEY_PENDING_SINCE_MS] = pending.sinceMs
        it[KEY_PERMISSION_SNAPSHOT] = permissionSnapshot
    }

    suspend fun permissionSnapshot(): Set<String> = dataStore.data.first()[KEY_PERMISSION_SNAPSHOT] ?: emptySet()

    suspend fun clearPending(result: String?) = dataStore.edit {
        it.remove(KEY_PENDING_CODE); it.remove(KEY_PENDING_FROM_CODE); it.remove(KEY_PENDING_SINCE_MS)
        it.remove(KEY_PERMISSION_SNAPSHOT)
        if (result != null) it[KEY_LAST_RESULT] = result
    }

    suspend fun setLastResult(result: String?) = dataStore.edit {
        if (result == null) it.remove(KEY_LAST_RESULT) else it[KEY_LAST_RESULT] = result
    }

    suspend fun setLostPermissions(lost: Set<String>) = dataStore.edit {
        if (lost.isEmpty()) it.remove(KEY_LOST_PERMISSIONS) else it[KEY_LOST_PERMISSIONS] = lost
    }
}
