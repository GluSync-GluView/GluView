package com.gluview.app.mirror

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gluview.mirror.FollowerAlertKind
import com.gluview.mirror.FollowerAlertPrefs
import com.gluview.mirror.Json
import com.gluview.mirror.LocalHttpServer
import com.gluview.mirror.MirrorCandidate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Um seguidor que este celular (paciente) autorizou. */
data class SharedFollower(val pairIdHex: String, val keyB64: String, val label: String, val createdMs: Long)

/** Uma pessoa que este celular segue pelo espelho. */
data class FollowedPatient(
    val pairIdHex: String,
    val keyB64: String,
    val name: String,
    /** Endereço do celular do paciente (IP ou nome). Vazio = procurar na rede local. */
    val host: String,
    val port: Int,
    val alerts: FollowerAlertPrefs = FollowerAlertPrefs(),
    /** O paciente tirou o acesso (guardado para não voltar a tentar nem alarmar depois de reiniciar). */
    val revoked: Boolean = false,
    /** Endereços que o paciente mandou no último "bem-vindo" (F2.4), já no formato de [com.gluview.mirror.MirrorCandidate]. */
    val learned: List<String> = emptyList()
)

data class MirrorSettings(
    /** Paciente: aceitar seguidores pelo espelho. */
    val shareEnabled: Boolean = false,
    /** Nome que os seguidores veem (o paciente escolhe). */
    val patientName: String = "",
    val sharePort: Int = DEFAULT_MIRROR_PORT,
    val shared: List<SharedFollower> = emptyList(),
    val followed: List<FollowedPatient> = emptyList(),
    /** Servidor web local (F2.1). */
    val httpEnabled: Boolean = false,
    /** false = só neste celular (127.0.0.1); true = também na rede Wi-Fi (senha obrigatória). */
    val httpOnWifi: Boolean = false,
    val httpPort: Int = LocalHttpServer.DEFAULT_PORT,
    val httpSecret: String = "",
    /** F2.4: endereço público opcional do paciente (DDNS, VPN, porta redirecionada no roteador). */
    val publicHost: String = "",
    /** F2.4: descobrir o IP público por STUN (servidores grátis do Google/Cloudflare) como tentativa extra. */
    val stunEnabled: Boolean = true
) {
    companion object { const val DEFAULT_MIRROR_PORT = 17581 }
}

/**
 * Plano 21, F2 — onde ficam os pares do espelho. As chaves são segredos (quem tem a chave lê a
 * glicemia), por isso tudo mora em SharedPreferences cifrado pelo Android Keystore, separado do
 * DataStore comum e FORA do backup de configurações (parear de novo é o caminho em outro celular).
 */
@Singleton
class MirrorStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs: SharedPreferences by lazy {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "gluview_mirror", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private val _settings = MutableStateFlow(MirrorSettings())
    val settings: StateFlow<MirrorSettings> = _settings
    @Volatile private var loaded = false

    /** Carrega do disco (chamar fora da main thread: cria a chave do Keystore na 1ª vez). */
    @Synchronized
    fun load(): MirrorSettings {
        if (!loaded) {
            _settings.value = runCatching { decode(prefs.getString(KEY, null)) }.getOrDefault(MirrorSettings())
            loaded = true
        }
        return _settings.value
    }

    @Synchronized
    fun update(transform: (MirrorSettings) -> MirrorSettings): MirrorSettings {
        val next = transform(load())
        prefs.edit().putString(KEY, encode(next)).apply()
        _settings.value = next
        return next
    }

    companion object {
        private const val KEY = "settings_v1"

        fun encode(s: MirrorSettings): String = Json.write(mapOf(
            "shareEnabled" to s.shareEnabled, "sharePort" to s.sharePort, "patientName" to s.patientName,
            "httpEnabled" to s.httpEnabled, "httpOnWifi" to s.httpOnWifi, "httpPort" to s.httpPort,
            "httpSecret" to s.httpSecret, "publicHost" to s.publicHost, "stun" to s.stunEnabled,
            "shared" to s.shared.map { mapOf("id" to it.pairIdHex, "k" to it.keyB64, "label" to it.label, "at" to it.createdMs) },
            "followed" to s.followed.map { f ->
                mapOf("id" to f.pairIdHex, "k" to f.keyB64, "name" to f.name, "host" to f.host, "port" to f.port,
                    "alerts" to f.alerts.enabled.map { it.name }, "repeat" to f.alerts.repeatMin,
                    "link" to f.alerts.linkTimeoutMin, "nosig" to f.alerts.noSignalMin, "revoked" to f.revoked,
                    "learned" to f.learned)
            }
        ))

        fun decode(raw: String?): MirrorSettings {
            val m = raw?.let { Json.parse(it) as? Map<*, *> } ?: return MirrorSettings()
            val d = MirrorSettings()
            fun Map<*, *>.s(k: String) = this[k] as? String
            fun Map<*, *>.i(k: String) = (this[k] as? Number)?.toInt()
            fun Map<*, *>.b(k: String) = this[k] as? Boolean
            return MirrorSettings(
                shareEnabled = m.b("shareEnabled") ?: d.shareEnabled,
                patientName = m.s("patientName").orEmpty(),
                sharePort = m.i("sharePort")?.takeIf { it in 1024..65535 } ?: d.sharePort,
                httpEnabled = m.b("httpEnabled") ?: d.httpEnabled,
                httpOnWifi = m.b("httpOnWifi") ?: d.httpOnWifi,
                httpPort = m.i("httpPort")?.takeIf { it in 1024..65535 } ?: d.httpPort,
                httpSecret = m.s("httpSecret") ?: "",
                publicHost = m.s("publicHost").orEmpty(),
                stunEnabled = m.b("stun") ?: d.stunEnabled,
                shared = (m["shared"] as? List<*>).orEmpty().mapNotNull { x ->
                    val o = x as? Map<*, *> ?: return@mapNotNull null
                    SharedFollower(o.s("id") ?: return@mapNotNull null, o.s("k") ?: return@mapNotNull null,
                        o.s("label").orEmpty(), (o["at"] as? Number)?.toLong() ?: 0L)
                },
                followed = (m["followed"] as? List<*>).orEmpty().mapNotNull { x ->
                    val o = x as? Map<*, *> ?: return@mapNotNull null
                    val def = FollowerAlertPrefs()
                    FollowedPatient(o.s("id") ?: return@mapNotNull null, o.s("k") ?: return@mapNotNull null,
                        o.s("name").orEmpty(), o.s("host").orEmpty(), o.i("port") ?: MirrorSettings.DEFAULT_MIRROR_PORT,
                        FollowerAlertPrefs(
                            enabled = (o["alerts"] as? List<*>)?.mapNotNull { n -> FollowerAlertKind.entries.firstOrNull { it.name == n } }?.toSet() ?: def.enabled,
                            repeatMin = o.i("repeat")?.coerceIn(5, 240) ?: def.repeatMin,
                            linkTimeoutMin = o.i("link")?.coerceIn(3, 120) ?: def.linkTimeoutMin,
                            noSignalMin = o.i("nosig")?.coerceIn(5, 120) ?: def.noSignalMin),
                        revoked = o.b("revoked") ?: false,
                        learned = (o["learned"] as? List<*>).orEmpty().filterIsInstance<String>().take(MirrorCandidate.MAX))
                }
            )
        }
    }
}
