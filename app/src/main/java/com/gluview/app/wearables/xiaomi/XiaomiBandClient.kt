package com.gluview.app.wearables.xiaomi

import android.content.Context
import android.os.Build
import android.util.Log
import com.gluview.wearables.xiaomi.protocol.XiaomiBandSession
import com.gluview.wearables.xiaomi.protocol.XiaomiLink
import com.gluview.wearables.core.VibrationStyle
import com.gluview.wearables.xiaomi.protocol.XiaomiV1Frames
import com.gluview.wearables.xiaomi.protocol.XiaomiV2Frames
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plano 21, F7.4 — pulseiras Xiaomi novas: enquadramento BLE V1 cifrado (Mi Band 8, Redmi Band 2…) ou
 * BLE V2 (Mi Band 9 Active, características 005E/005F) — o caminho é escolhido pelo que a pulseira anuncia.
 * Aqui só o Bluetooth (RxAndroidBle, como o MiBandUploader); o protocolo (aperto de mão, cifra, mensagens)
 * mora no módulo AGPL `:wearables-xiaomi` ([XiaomiBandSession]). Conecta, autentica, manda uma
 * notificação (a pulseira vibra) e desconecta — não fica ligado o tempo todo.
 *
 * Testado só na pulseira virtual (tools/virtual-sensors/xiaomi.py, --proto v1|v2); sem pulseira de verdade.
 */
@Singleton
class XiaomiBandClient @Inject constructor(@ApplicationContext private val context: Context) {
    private val tag = "XiaomiBand"
    private val rx: RxBleClient by lazy { RxBleClient.create(context) }
    private val mutex = Mutex()

    suspend fun sendNotification(mac: String, keyHex: String, title: String, body: String, log: (String) -> Unit): Boolean =
        alert(mac, keyHex, title, body, null, log)

    /**
     * Notificação + vibração no padrão [style] (null = só a vibração própria da notificação; OFF = nenhuma
     * a mais). O padrão vai pelo "teste de vibração" do protocolo, que aceita liga/pausa em ms.
     */
    suspend fun alert(mac: String, keyHex: String, title: String, body: String, style: VibrationStyle?,
                      log: (String) -> Unit): Boolean = mutex.withLock {
        fun emit(m: String) { Log.i(tag, m); log(m) }
        fun fail(m: String): Boolean { emit("❌ $m"); return false }
        val k = keyHex.trim().removePrefix("0x").lowercase()
        if (k.length != 32 || !k.all { it in '0'..'9' || it in 'a'..'f' }) return fail("chave de autenticação inválida (32 caracteres 0–9/a–f)")
        val key = ByteArray(16) { k.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val device = try { rx.getBleDevice(mac) } catch (e: Exception) { return fail("MAC inválido") }

        val disposables = CompositeDisposable()
        val connected = CompletableDeferred<RxBleConnection>()
        val dropped = CompletableDeferred<Unit>()
        disposables.add(device.establishConnection(false).subscribe(
            { c -> if (!connected.isCompleted) connected.complete(c) },
            { e -> if (!connected.isCompleted) connected.completeExceptionally(e) else { emit("⚠️ Desconectado: ${e.message}"); dropped.complete(Unit) } }
        ))
        try {
            emit("Conectando…")
            val conn = withTimeoutOrNull(15_000) { connected.await() } ?: return fail("falha ao conectar (tempo esgotado)")
            val services = conn.discoverServices(10, TimeUnit.SECONDS).await()
            val uuids = services.bluetoothGattServices.flatMap { sv -> sv.characteristics.map { it.uuid } }.toSet()
            val v2 = XiaomiV2Frames.RX in uuids && XiaomiV2Frames.TX in uuids
            val mtu = runCatching { withTimeoutOrNull(5_000) { conn.requestMtu(if (v2) 512 else 247).await() } }.getOrNull() ?: 23
            val channels = if (v2) mapOf(XiaomiV2Frames.RX to Channel<ByteArray>(Channel.UNLIMITED))
                else mapOf(XiaomiV1Frames.COMMAND_READ to Channel<ByteArray>(Channel.UNLIMITED),
                    XiaomiV1Frames.COMMAND_WRITE to Channel<ByteArray>(Channel.UNLIMITED))
            for ((uuid, ch) in channels) {
                val ready = CompletableDeferred<Unit>()
                disposables.add(conn.setupNotification(uuid).doOnNext { ready.complete(Unit) }.flatMap { it }
                    .subscribe({ ch.trySend(it) }, { t -> Log.w(tag, "notificação falhou em $uuid: $t") }))
                withTimeoutOrNull(5_000) { ready.await() } ?: return fail("não deu para ligar as notificações da pulseira")
            }
            emit("Conectado (MTU $mtu, protocolo ${if (v2) "V2" else "V1"}).")
            val link = object : XiaomiLink {
                override val maxWrite = (mtu - 3).coerceAtLeast(20)
                override suspend fun write(characteristic: UUID, value: ByteArray) { conn.writeCharacteristic(characteristic, value).await() }
                override suspend fun receive(characteristic: UUID, timeoutMs: Long): ByteArray? = withTimeoutOrNull(timeoutMs) {
                    select<ByteArray?> {
                        channels.getValue(characteristic).onReceive { it }
                        dropped.onAwait { null }
                    }
                }
            }
            val s = XiaomiBandSession(link, key, ::emit,
                protocol = if (v2) XiaomiBandSession.Protocol.V2 else XiaomiBandSession.Protocol.V1)
            val region = Locale.getDefault().language.take(2).uppercase(Locale.ROOT).ifBlank { "PT" }
            (s.authenticate(Build.VERSION.SDK_INT, Build.MODEL ?: "Android", region) as? XiaomiBandSession.Result.Failed)
                ?.let { return fail(it.reason) }
            (s.notify((System.currentTimeMillis() / 1000).toInt(), title, body, System.currentTimeMillis()) as? XiaomiBandSession.Result.Failed)
                ?.let { return fail(it.reason) }
            if (style != null && style != VibrationStyle.OFF) {
                (s.vibrate(style.segmentsMs) as? XiaomiBandSession.Result.Failed)?.let { return fail(it.reason) }
                emit("Vibração: ${style.id}.")
            }
            emit("✅ Notificação enviada à pulseira.")
            return true
        } catch (e: Exception) {
            return fail("erro: ${e.message}")
        } finally {
            disposables.clear()
        }
    }
}
