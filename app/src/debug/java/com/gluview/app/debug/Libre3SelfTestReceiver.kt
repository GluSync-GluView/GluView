package com.gluview.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gluview.app.data.libre3.Libre3Native
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.security.SecureRandom

/**
 * SÓ NO BUILD DEBUG. Duas funções:
 *
 * 1) Autoteste da biblioteca do Libre 3 num aparelho de verdade (sem sensor), ação padrão:
 *    carrega gluviewlibre3; começo da autorização nova (chave do app, chave efêmera na curva P-256,
 *    aleatória); e o DESAFIO completo com autorização salva fabricada ([Libre3TestAuthorization]),
 *    app ↔ lado do sensor (funções Emulator* do núcleo), nos dois sentidos.
 *      adb shell am broadcast -n <pacote>/com.gluview.app.debug.Libre3SelfTestReceiver
 *      adb logcat -s LIBRE3_SELFTEST
 *
 * 2) "Oráculo" do lado do sensor para o simulador Bluetooth (tools/virtual-sensors/libre3.py):
 *    extra `op` = `kauth` | `sensor_decrypt` | `sensor_encrypt`, com `kauth`, `nonce`, `data` em hex;
 *    a resposta volta em hex no resultData do broadcast (o `am broadcast` imprime `data="..."`).
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface DebugDeps {
    fun preferences(): com.gluview.app.data.datastore.AppPreferences
    fun repository(): com.gluview.app.data.repository.GlucoseRepository
    fun appLogger(): com.gluview.app.util.AppLogger
    fun appLogDao(): com.gluview.app.data.local.dao.AppLogDao
}

class Libre3SelfTestReceiver : BroadcastReceiver() {
    private val tag = "LIBRE3_SELFTEST"
    private val rnd = SecureRandom()

    override fun onReceive(context: Context, intent: Intent) {
        val ok = Libre3Native.init(null)
        when (intent.getStringExtra("op")) {
            "kauth" -> { resultData = hex(Libre3TestAuthorization.fabricate()); return }
            "sensor_decrypt" -> { resultData = sensorOp(intent, decrypt = true) ?: "ERRO"; return }
            "sensor_encrypt" -> { resultData = sensorOp(intent, decrypt = false) ?: "ERRO"; return }
            // F6.2: a biblioteca da Abbott empacotada reconhece a família 7F (Libre 2/2 Plus EU)?
            "libre2_family" -> {
                val logger = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext, DebugDeps::class.java).appLogger()
                val lib = com.gluview.app.data.libre2.Libre2Native
                val ok = runCatching { lib.init(logger) }.getOrDefault(false)
                fun fam(hexPi: String) = lib.productFamily(unhex(hexPi))
                val r = "init=$ok 9D=${fam("9d0830017136")} 2B=${fam("2b0a3a081fe1")} " +
                    "7F0E30=${fam("7f0e30010000")} 7F0E31=${fam("7f0e31010000")} E5=${fam("e50003010000")} 70=${fam("700010010000")}"
                Log.i(tag, "LIBRE2_FAMILY $r")
                resultData = r
                return
            }
            // F6.2: as funções do caminho EU respondem para a família 7F? (UID de exemplo, sem sensor)
            "libre2_eu" -> {
                val logger = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext, DebugDeps::class.java).appLogger()
                val lib = com.gluview.app.data.libre2.Libre2Native
                val ok = runCatching { lib.init(logger) }.getOrDefault(false)
                val uid = unhex(intent.getStringExtra("uid") ?: "e00a3c2a1e2b07e0")
                val sb = StringBuilder("init=$ok")
                for (pi in listOf("7f0e30010000", "7f0e31010000", "9d0830017136")) {
                    val info = unhex(pi)
                    val cmd = lib.euActivationCommand(info)
                    val act = lib.euActivationPayload(uid, info)
                    val t = System.currentTimeMillis() / 1000
                    val en = lib.euEnableStreamingPayload(uid, info, t)
                    val u0 = lib.euStreamingUnlockPayload(uid, info, t, 0)
                    val u1 = lib.euStreamingUnlockPayload(uid, info, t, 1)
                    sb.append(" | $pi cmd=${cmd?.let { "%02x".format(it) }} act=${act?.let(::hex)} en=${en?.let(::hex)} " +
                        "unlock0=${u0?.let(::hex)} unlock1=${u1?.let(::hex)} unlockMuda=${u0 != null && u1 != null && !u0.contentEquals(u1)}")
                }
                Log.i(tag, "LIBRE2_EU $sb")
                resultData = sb.toString()
                return
            }
            // Escolhe a fonte de dados (id do DataSourceType) e, para os leitores, esquece o leitor salvo.
            "source" -> {
                val prefs = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext, DebugDeps::class.java).preferences()
                val id = intent.getStringExtra("id") ?: return
                val pending = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        prefs.saveDataSource(id)
                        if (id.startsWith("LIBRE_BRIDGE")) prefs.saveLibreBridge("", "")
                        pending.resultData = "OK $id"
                    } finally { pending.finish() }
                }
                return
            }
            // Últimas linhas do log do app (AppLogger), filtradas por etiqueta — para os cenários dos simuladores.
            "logs" -> {
                val dao = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext, DebugDeps::class.java).appLogDao()
                val tags = intent.getStringExtra("tags")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                val last = intent.getStringExtra("last")?.toIntOrNull() ?: 30
                val pending = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val rows = dao.observeRecent(2000).first()
                            .filter { tags == null || tags.any { t -> it.tag.startsWith(t) } }
                            .take(last).reversed()
                        pending.resultData = rows.joinToString(" || ") {
                            "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(it.ts))} ${it.level} ${it.tag} ${it.msg} ${it.data}"
                        }
                    } finally { pending.finish() }
                }
                return
            }
            // Últimas leituras gravadas (hora em ms, mg/dL, seta, origem) — para conferir o simulador.
            "readings" -> {
                val repo = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext, DebugDeps::class.java).repository()
                val since = System.currentTimeMillis() - (intent.getStringExtra("minutes")?.toLongOrNull() ?: 240L) * 60_000L
                val pending = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val mac = intent.getStringExtra("mac")
                        val list = repo.getRecentReadingsForNotification(since)
                            .filter { mac == null || it.sensorMac.equals(mac, ignoreCase = true) }
                            .sortedBy { it.dateMs }
                        val dup = list.zipWithNext().count { (a, b) -> b.dateMs - a.dateMs < 30_000L }
                        pending.resultData = "n=${list.size} pares<30s=$dup agora=${System.currentTimeMillis()} " +
                            list.takeLast(intent.getStringExtra("last")?.toIntOrNull() ?: 8)
                                .joinToString(";") { "${it.dateMs}:${it.sgvMgDl}:${it.arrow}" }
                    } finally { pending.finish() }
                }
                return
            }
            // Sessão do sensor virtual (o que a ativação NFC gravaria) + fonte LIBRE3. Reabra o app depois.
            "session" -> {
                val prefs = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext, DebugDeps::class.java).preferences()
                val pending = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        prefs.saveLibre3Session(
                            serial = intent.getStringExtra("serial") ?: "3MH00SIMUL",
                            mac = intent.getStringExtra("mac") ?: "",
                            pinHex = intent.getStringExtra("pin") ?: "",
                            activationMs = intent.getStringExtra("start")?.toLongOrNull() ?: System.currentTimeMillis(),
                            warmupMin = 60, wearMin = 14 * 24 * 60
                        )
                        prefs.saveLibre3SavedAuth(intent.getStringExtra("kauth") ?: "")
                        prefs.saveDataSource(com.gluview.app.data.source.DataSourceType.LIBRE3.id)
                        pending.resultData = "OK"
                    } finally { pending.finish() }
                }
                return
            }
        }
        Log.i(tag, "init disponivel=$ok")
        if (!ok) { Log.e(tag, "RESULTADO=FALHA (biblioteca)"); return }

        // --- autorização nova: só a parte do app que roda sem sensor
        val ctx = Libre3Native.beginSecurityHandshake(0L)
        val appKey = ctx != 0L && Libre3Native.selectAppKeyAndSavedAuthorization(ctx, 1, null)
        val k1 = if (appKey) Libre3Native.createEphemeralPublicKey(ctx) else null
        val k2 = if (appKey) Libre3Native.createEphemeralPublicKey(ctx) else null
        val fresh = appKey && k1 != null && k2 != null && onP256(k1) && onP256(k2) && !k1.contentEquals(k2) &&
            !Libre3Native.deriveAuthorizationRoot(ctx, ByteArray(65).also { it[0] = 4 })
        Log.i(tag, "autorizacaoNova chaveDoApp=$appKey efemeras=${k1?.size}/${k2?.size} ok=$fresh")

        // --- autorização salva + desafio, app ↔ sensor virtual
        val kAuth = Libre3TestAuthorization.fabricate()
        val app = Libre3Native.beginSecurityHandshake(0L)
        val sensor = Libre3Native.beginSecurityHandshake(0L)
        val imported = Libre3Native.selectAppKeyAndSavedAuthorization(app, 1, kAuth) &&
            Libre3Native.selectAppKeyAndSavedAuthorization(sensor, 1, kAuth)
        val r1 = bytes(16); val r2 = bytes(16); val pin = bytes(4); val nonce1 = bytes(7)
        val reply = Libre3Native.encryptChallengeReply(app, nonce1, r1 + r2 + pin)
        val seen = reply?.let { Libre3Native.emulatorDecryptReply(sensor, nonce1, it) }
        val kEnc = bytes(16); val ivEnc = bytes(8); val nonce2 = bytes(7)
        val plain56 = r2 + r1 + kEnc + ivEnc
        val resp = Libre3Native.emulatorEncryptResponse(sensor, nonce2, plain56)
        val back = resp?.let { Libre3Native.decryptChallengeResponse(app, nonce2, it) }
        val reExport = Libre3Native.exportSavedAuthorization(app)
        // kAuth diferente no "sensor" = desafio tem de falhar
        val other = Libre3Native.beginSecurityHandshake(0L)
        Libre3Native.selectAppKeyAndSavedAuthorization(other, 1, Libre3TestAuthorization.fabricate())
        val wrongRejected = reply != null && Libre3Native.emulatorDecryptReply(other, nonce1, reply)?.contentEquals(r1 + r2 + pin) != true
        val saved = imported && reply?.size == 40 && seen?.contentEquals(r1 + r2 + pin) == true &&
            resp?.size == 60 && back?.contentEquals(plain56) == true && reExport?.contentEquals(kAuth) == true && wrongRejected
        Log.i(tag, "autorizacaoSalva importou=$imported resposta=${reply?.size} sensorLeu=${seen?.contentEquals(r1 + r2 + pin)} " +
            "respostaSensor=${resp?.size} appLeu=${back?.contentEquals(plain56)} reexportIgual=${reExport?.contentEquals(kAuth)} " +
            "kAuthErradaRecusada=$wrongRejected ok=$saved")
        Log.i(tag, "RESULTADO=" + if (fresh && saved) "OK" else "FALHA")
    }

    private fun sensorOp(intent: Intent, decrypt: Boolean): String? {
        if (!Libre3Native.available) return null
        val kAuth = unhex(intent.getStringExtra("kauth") ?: return null)
        val nonce = unhex(intent.getStringExtra("nonce") ?: return null)
        val data = unhex(intent.getStringExtra("data") ?: return null)
        val ctx = Libre3Native.beginSecurityHandshake(0L)
        if (!Libre3Native.selectAppKeyAndSavedAuthorization(ctx, 1, kAuth)) return null
        val out = if (decrypt) Libre3Native.emulatorDecryptReply(ctx, nonce, data) else Libre3Native.emulatorEncryptResponse(ctx, nonce, data)
        return out?.let(::hex)
    }

    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun onP256(xy: ByteArray): Boolean {
        if (xy.size != 64) return false
        val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
        val b = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
        val x = BigInteger(1, xy.copyOfRange(0, 32))
        val y = BigInteger(1, xy.copyOfRange(32, 64))
        if (x >= p || y >= p) return false
        val lhs = y.modPow(BigInteger.valueOf(2), p)
        val rhs = x.modPow(BigInteger.valueOf(3), p).subtract(x.multiply(BigInteger.valueOf(3))).add(b).mod(p)
        return lhs == rhs
    }
}
