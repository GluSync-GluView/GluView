package com.gluview.app.wearables.huami

import android.content.Context
import android.util.Log
import com.gluview.app.watchface.miband.HuamiProfile
import com.gluview.wearables.core.VibrationStyle
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plano 21, F7.1 — alertas do GluView nas pulseiras Huami/Amazfit, com a vibração escolhida pela pessoa.
 * Porte do comportamento do WatchDrip+ (GPLv3, MiBandService/AlertMessage/AlertLevelMessage):
 *  - texto do alerta pelo Alert Notification (0x2A46);
 *  - vibração no padrão escolhido pelo Immediate Alert (0x2A06): FF, liga (ms), pausa (ms), vezes;
 *  - "chamada" (CALL): categoria 3 no 0x2A46 — a pulseira toca até a pessoa recusar; recusar (evento 0x07
 *    em 0x0010) é a soneca pela pulseira; sem resposta, a chamada é encerrada (00 C0 00 03 03… em 0x0020).
 * Conecta, autentica (flags do modelo), avisa e desconecta.
 */
@Singleton
class HuamiAlertSender @Inject constructor(@ApplicationContext private val context: Context) {
    private val tag = "HuamiAlert"
    private val rx: RxBleClient by lazy { RxBleClient.create(context) }
    private val mutex = Mutex()

    enum class CallResult { NONE, REJECTED, IGNORED, TIMEOUT }

    data class Outcome(val ok: Boolean, val call: CallResult = CallResult.NONE)

    companion object {
        private fun huami(s: String) = UUID.fromString("0000$s-0000-3512-2118-0009af100700")
        private fun std(s: String) = UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")
        val CH_AUTH: UUID = huami("0009")
        val CH_EVENT: UUID = huami("0010")
        val CH_CHUNKED: UUID = huami("0020")
        val CH_ALERT_LEVEL: UUID = std("2a06")
        val CH_NEW_ALERT: UUID = std("2a46")
        const val CATEGORY_SMS: Byte = 5
        const val CATEGORY_CALL: Byte = 3
        const val EVENT_CALL_REJECT: Byte = 0x07
        const val EVENT_CALL_IGNORE: Byte = 0x09
        val COMMAND_DISABLE_CALL = byteArrayOf(0x00, 0xc0.toByte(), 0x00, 0x03, 0x03, 0x00, 0x00, 0x00, 0x00)

        /** AlertLevelMessage.getPeriodicVibrationMessage: FF + liga LE16 + pausa LE16 + vezes. */
        fun vibrationCommand(style: VibrationStyle): ByteArray {
            val on = style.onMs.toInt().coerceIn(0, 0xffff)
            val off = style.offMs.toInt().coerceIn(0, 0xffff)
            return byteArrayOf(0xff.toByte(), (on and 0xff).toByte(), (on ushr 8).toByte(),
                (off and 0xff).toByte(), (off ushr 8).toByte(), style.pulses.coerceIn(1, 255).toByte())
        }

        /**
         * AlertMessage.getAlertMessageOld: categoria, 1 alerta, texto UTF-8 — cortado para caber numa escrita
         * ([maxWrite] = MTU − 3; sem MTU maior a pulseira só recebe os primeiros 20 bytes).
         */
        fun alertCommand(category: Byte, text: String, maxWrite: Int = 244): ByteArray {
            var t = text
            while (t.toByteArray(Charsets.UTF_8).size > (maxWrite - 2).coerceAtLeast(0)) t = t.dropLast(1)
            return byteArrayOf(category, 0x01) + t.toByteArray(Charsets.UTF_8)
        }
    }

    suspend fun alert(mac: String, key: ByteArray, profile: HuamiProfile, title: String, body: String,
                      style: VibrationStyle, callTimeoutMs: Long = 30_000, log: (String) -> Unit = {}): Outcome =
        session(mac, key, profile, log) { conn, events, maxWrite ->
            if (style == VibrationStyle.CALL) {
                conn.writeCharacteristic(CH_NEW_ALERT, alertCommand(CATEGORY_CALL, "$title $body".trim(), maxWrite)).await()
                log("Chamada enviada; esperando a pessoa responder…")
                val ev = withTimeoutOrNull(callTimeoutMs) {
                    var r: CallResult? = null
                    while (r == null) {
                        val e = events.receive()
                        r = when (e.firstOrNull()) { EVENT_CALL_REJECT -> CallResult.REJECTED; EVENT_CALL_IGNORE -> CallResult.IGNORED; else -> null }
                    }
                    r
                } ?: CallResult.TIMEOUT
                if (ev != CallResult.REJECTED) {
                    // Encerra a chamada na pulseira (sem resposta ou ignorada).
                    runCatching { conn.writeCharacteristic(CH_CHUNKED, COMMAND_DISABLE_CALL).await() }
                    runCatching { conn.writeCharacteristic(CH_ALERT_LEVEL, byteArrayOf(0)).await() }
                }
                log("Chamada: $ev")
                Outcome(true, ev)
            } else {
                conn.writeCharacteristic(CH_NEW_ALERT, alertCommand(CATEGORY_SMS, "$title: $body", maxWrite)).await()
                if (style != VibrationStyle.OFF) conn.writeCharacteristic(CH_ALERT_LEVEL, vibrationCommand(style)).await()
                log("Alerta enviado (${style.id}).")
                Outcome(true)
            }
        }

    /** "Achar a pulseira": vibra forte algumas vezes. */
    suspend fun find(mac: String, key: ByteArray, profile: HuamiProfile, log: (String) -> Unit = {}): Boolean =
        session(mac, key, profile, log) { conn, _, _ ->
            conn.writeCharacteristic(CH_ALERT_LEVEL, vibrationCommand(VibrationStyle.INSISTENT)).await()
            Outcome(true)
        }.ok

    private suspend fun session(mac: String, key: ByteArray, profile: HuamiProfile, log: (String) -> Unit,
                                block: suspend (RxBleConnection, Channel<ByteArray>, Int) -> Outcome): Outcome = mutex.withLock {
        fun emit(m: String) { Log.i(tag, m); log(m) }
        val device = try { rx.getBleDevice(mac) } catch (e: Exception) { emit("MAC inválido"); return Outcome(false) }
        val disposables = CompositeDisposable()
        val connected = CompletableDeferred<RxBleConnection>()
        val dropped = CompletableDeferred<Unit>()
        disposables.add(device.establishConnection(false).subscribe(
            { c -> if (!connected.isCompleted) connected.complete(c) },
            { e -> if (!connected.isCompleted) connected.completeExceptionally(e) else dropped.complete(Unit) }))
        try {
            val conn = withTimeoutOrNull(15_000) { connected.await() } ?: run { emit("❌ falha ao conectar"); return Outcome(false) }
            conn.discoverServices(10, TimeUnit.SECONDS).await()
            // Como o WatchDrip+ (PREFERRED_MTU_SIZE): sem isto o texto do alerta chega cortado em 20 bytes.
            val mtu = runCatching { withTimeoutOrNull(5_000) { conn.requestMtu(247).await() } }.getOrNull() ?: 23
            val authCh = Channel<ByteArray>(Channel.UNLIMITED)
            val eventCh = Channel<ByteArray>(Channel.UNLIMITED)
            for ((uuid, ch) in listOf(CH_AUTH to authCh, CH_EVENT to eventCh)) {
                val ready = CompletableDeferred<Unit>()
                disposables.add(conn.setupNotification(uuid).doOnNext { ready.complete(Unit) }.flatMap { it }
                    .subscribe({ ch.trySend(it) }, { t -> Log.w(tag, "notificação $uuid: $t") }))
                withTimeoutOrNull(5_000) { ready.await() } ?: run { emit("❌ notificações da pulseira não ligaram"); return Outcome(false) }
            }
            suspend fun next(): ByteArray? = withTimeoutOrNull(8_000) {
                select<ByteArray?> { authCh.onReceive { it }; dropped.onAwait { null } }
            }
            // Autenticação Huami (AuthOperations do WatchDrip+), com as flags do modelo.
            val a = profile.authFlags; val c = profile.cryptFlags
            conn.writeCharacteristic(CH_AUTH, if (c.toInt() == 0) byteArrayOf(0x02, a)
                else byteArrayOf((c.toInt() or 0x02).toByte(), a, 0x02, 0x01, 0x00)).await()
            val rnd = next() ?: run { emit("❌ sem número aleatório"); return Outcome(false) }
            if (rnd.size < 19 || rnd[0].toInt() != 0x10 || rnd[2].toInt() != 0x01) { emit("❌ a pulseira recusou a autenticação"); return Outcome(false) }
            val enc = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
                .doFinal(rnd.copyOfRange(3, 19))
            conn.writeCharacteristic(CH_AUTH, byteArrayOf((0x03 or c.toInt()).toByte(), a) + enc).await()
            val res = next() ?: run { emit("❌ sem confirmação da autenticação"); return Outcome(false) }
            if (res.size < 3 || res[2].toInt() != 0x01) { emit("❌ chave de autenticação recusada"); return Outcome(false) }
            block(conn, eventCh, (mtu - 3).coerceAtLeast(20))
        } catch (e: Exception) {
            emit("❌ erro: ${e.message}")
            Outcome(false)
        } finally {
            disposables.clear()
        }
    }
}
