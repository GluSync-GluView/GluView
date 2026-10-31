package com.gluview.app.watchface.miband

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.util.Log
import com.gluview.app.watchface.miband.firmware.BinUtils
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camada 2 — upload do visor (.bin) para a Mi Band 6 via protocolo Huami-2020 "chunked", sobre
 * RxAndroidBle 1.12.1 (a MESMA lib que o WatchDrip usa, não mais uma réplica manual sobre
 * BluetoothGatt direto). Porte da lógica do WatchDrip (FirmwareOperations2020, GPLv3).
 *
 * Por que RxAndroidBle e não BluetoothGatt cru: o RxAndroidBle serializa TODA operação GATT
 * numa fila própria de 1 thread que só libera a próxima operação depois que o callback Android
 * da anterior chega (ConnectionOperationQueueImpl/QueueSemaphore, confirmado lendo o código-fonte
 * da lib). Uma réplica manual desse mecanismo sobre BluetoothGatt (tentada antes) corrigiu um bug
 * real de pacing mas não resolveu o travamento no 2º bloco de dados — então esta versão usa a
 * lib de verdade, eliminando de vez a dúvida "reproduzi o mecanismo certinho?".
 *
 * Fluxo: conectar → descobrir serviços → autenticar (handshake clássico, flags do Mi Band 6:
 * authFlags=0x00/cryptFlags=0x80) → MTU 247 → notify no control(1531) → WF_ID + nightmode no
 * config(0003) → REQUEST_PARAMS(0xd0) → FW_INFO(0xd2) → START(0xd3,01) → dados em chunks no
 * data(1532), band confirma cada chunk com 0xd4+offset → COMPLETE(0xd5) → FINALIZE(0xd6).
 */
@Singleton
class MiBandUploader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "MiBandUpload"

    private companion object {
        val CH_AUTH = UUID.fromString("00000009-0000-3512-2118-0009af100700")
        val CH_CONFIG = UUID.fromString("00000003-0000-3512-2118-0009af100700")
        val CH_DEVICEEVENT = UUID.fromString("00000010-0000-3512-2118-0009af100700")
        val CH_FW = UUID.fromString("00001531-0000-3512-2118-0009af100700")
        val CH_FW_DATA = UUID.fromString("00001532-0000-3512-2118-0009af100700")

        const val CMD_REQUEST_PARAMETERS = 0xd0.toByte()
        const val CMD_SEND_FW_INFO = 0xd2.toByte()
        const val CMD_START_TRANSFER = 0xd3.toByte()
        const val REPLY_UPDATE_PROGRESS = 0xd4.toByte()
        const val CMD_COMPLETE_TRANSFER = 0xd5.toByte()
        const val CMD_FINALIZE_UPDATE = 0xd6.toByte()
        const val RESPONSE = 0x10.toByte()
        const val WATCHFACE_TYPE = 8.toByte()
    }

    private val rxClient: RxBleClient by lazy { RxBleClient.create(context) }

    private lateinit var log: (String) -> Unit
    private var mtu = 23
    private val authNotify = Channel<ByteArray>(Channel.UNLIMITED)
    private val fwNotify = Channel<ByteArray>(Channel.UNLIMITED)
    private val disposables = CompositeDisposable()

    private fun emit(m: String) { Log.i(tag, m); log(m) }

    suspend fun connectAndUpload(mac: String, key: ByteArray, fw: ByteArray, logger: (String) -> Unit): Boolean {
        log = logger
        val device = try { rxClient.getBleDevice(mac) } catch (e: Exception) { emit("MAC inválido: ${e.message}"); return false }

        val connDeferred = CompletableDeferred<RxBleConnection>()
        disposables.add(
            device.establishConnection(false).subscribe(
                { conn -> if (!connDeferred.isCompleted) connDeferred.complete(conn) },
                { err ->
                    if (!connDeferred.isCompleted) connDeferred.completeExceptionally(err)
                    else emit("⚠️ Desconectado: ${err.message}")
                }
            )
        )
        try {
            emit("Conectando…")
            val connection = withTimeoutOrNull(15000) { connDeferred.await() }
                ?: return fail("Falha ao conectar (timeout)")

            emit("Conectado, descobrindo serviços…")
            connection.discoverServices(10, TimeUnit.SECONDS).await()

            // Prioridade alta = intervalo de conexão curto → throughput para o streaming.
            runCatching {
                withTimeoutOrNull(3000) {
                    connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH, 1, TimeUnit.MILLISECONDS).await()
                }
            }

            mtu = runCatching { withTimeoutOrNull(5000) { connection.requestMtu(247).await() } }.getOrNull() ?: 23
            emit("MTU=$mtu (pacote=${mtu - 3})")

            // notify no control de firmware + na char de eventos, e no auth — antes de qualquer
            // escrita que espere resposta.
            subscribeNotify(connection, CH_FW, fwNotify)
            subscribeNotify(connection, CH_DEVICEEVENT, null)
            subscribeNotify(connection, CH_AUTH, authNotify)

            if (!authenticate(connection, key)) return false

            // WF_ID + nightmode agendado na config — fire-and-forget, igual ao WatchDrip
            // (a ordem é garantida pela fila FIFO do RxAndroidBle, não por espera manual).
            fireWrite(connection, CH_CONFIG, watchfaceIdCommand(fw))
            fireWrite(connection, CH_CONFIG, byteArrayOf(0x1a, 0x01, 0x10, 0x00, 0x07, 0x00))

            // request parameters → chunkLength
            emit("Pedindo parâmetros…")
            fireWrite(connection, CH_FW, byteArrayOf(CMD_REQUEST_PARAMETERS))
            val pr = awaitFw() ?: return fail("sem resposta a REQUEST_PARAMETERS")
            if (!(pr[0] == RESPONSE && pr[1] == CMD_REQUEST_PARAMETERS)) return fail("resposta inesperada params: ${hex(pr)}")
            val chunkLength = (pr[4].toInt() and 0xff) or ((pr[5].toInt() and 0xff) shl 8)
            emit("chunkLength=$chunkLength")

            // fw info (tamanho + crc32 + chunkSize)
            fireWrite(connection, CH_FW, fwInfoCommand(fw, chunkLength))
            val ir = awaitFw() ?: return fail("sem resposta a FW_INFO")
            val okInfo = ir[0] == RESPONSE && ir[1] == CMD_SEND_FW_INFO && ((ir[2].toInt() and 0xff) == 0x01 || (ir[2].toInt() and 0xff) == 0x57)
            if (!okInfo) return fail("FW_INFO rejeitado: ${hex(ir)}")
            emit("FW_INFO aceito")

            // start transfer
            fireWrite(connection, CH_FW, byteArrayOf(CMD_START_TRANSFER, 0x01))
            val sr = awaitFw() ?: return fail("sem resposta a START")
            if (!(sr[0] == RESPONSE && sr[1] == CMD_START_TRANSFER && sr[2].toInt() == 1)) return fail("START rejeitado: ${hex(sr)}")
            emit("Transferência iniciada, enviando dados…")

            // envia dados em chunks; a band confirma cada chunk com 0xd4 + próximo offset
            var offset = 0
            while (offset < fw.size) {
                sendChunk(connection, fw, offset, chunkLength)
                val rp = awaitFw() ?: return fail("sem ack de progresso")
                when {
                    rp[1] == REPLY_UPDATE_PROGRESS && rp.size >= 6 -> {
                        offset = (rp[2].toInt() and 0xff) or ((rp[3].toInt() and 0xff) shl 8) or
                                ((rp[4].toInt() and 0xff) shl 16) or ((rp[5].toInt() and 0xff) shl 24)
                    }
                    else -> return fail("ack inesperado: ${hex(rp)}")
                }
            }

            // complete + finalize
            fireWrite(connection, CH_FW, byteArrayOf(CMD_COMPLETE_TRANSFER))
            val cr = awaitFw() ?: return fail("sem resposta a COMPLETE")
            if (!(cr[0] == RESPONSE && cr[1] == CMD_COMPLETE_TRANSFER)) return fail("COMPLETE rejeitado: ${hex(cr)}")
            fireWrite(connection, CH_FW, byteArrayOf(CMD_FINALIZE_UPDATE))
            val fr = awaitFw() ?: return fail("sem resposta a FINALIZE")
            if (!(fr[0] == RESPONSE && fr[1] == CMD_FINALIZE_UPDATE && fr[2].toInt() == 1)) return fail("FINALIZE rejeitado: ${hex(fr)}")

            emit("✅ Visor enviado e instalado na Mi Band!")
            return true
        } catch (e: Exception) {
            return fail("Erro: ${e.message}")
        } finally {
            disposables.clear()
        }
    }

    private fun fail(msg: String): Boolean { emit("❌ $msg"); return false }
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }
    private fun packetLen() = (mtu - 3).coerceAtLeast(18)

    @SuppressLint("CheckResult")
    private fun fireWrite(connection: RxBleConnection, uuid: UUID, data: ByteArray) {
        connection.writeCharacteristic(uuid, data).subscribe(
            { },
            { t -> Log.w(tag, "write falhou em $uuid: $t") }
        )
    }

    /**
     * Envia um chunk lógico (até chunkLength) em pacotes BLE de packetLen na char de dados —
     * réplica 1:1 de FirmwareOperations2020.sendFirmwareDataChunk do WatchDrip: dispara todos os
     * writes do chunk em sequência via subscribe(), SEM aguardar cada um individualmente. A
     * ordem/pacing fica inteiramente por conta da fila interna do RxAndroidBle.
     */
    private fun sendChunk(connection: RxBleConnection, fw: ByteArray, offset: Int, chunkLength: Int) {
        val remaining = fw.size - offset
        val thisChunk = minOf(chunkLength, remaining)
        var p = 0
        var packets = 0
        while (p < thisChunk) {
            val n = minOf(packetLen(), thisChunk - p)
            fireWrite(connection, CH_FW_DATA, fw.copyOfRange(offset + p, offset + p + n))
            p += n
            packets++
        }
        emit("Chunk @$offset enviado (${thisChunk}B em $packets pacotes)")
    }

    private fun watchfaceIdCommand(fw: ByteArray): ByteArray {
        val size = BinUtils.fromUint32(fw.size)
        return byteArrayOf(0x39, 0x00, size[0], size[1], size[2], size[3], fw[18], fw[19], fw[20], fw[21])
    }

    private fun fwInfoCommand(fw: ByteArray, chunkLength: Int): ByteArray {
        val size = BinUtils.fromUint32(fw.size)
        val crc = CRC32().apply { update(fw) }.value.toInt()
        val crcB = BinUtils.fromUint32(crc)
        val ch = BinUtils.fromUint16(chunkLength)
        return byteArrayOf(
            CMD_SEND_FW_INFO, WATCHFACE_TYPE,
            size[0], size[1], size[2], size[3],
            crcB[0], crcB[1], crcB[2], crcB[3],
            ch[0], ch[1], 0, 0, 1,
            size[0], size[1], size[2], size[3]
        )
    }

    // ---- handshake de autenticação (clássico, flags do Mi Band 6) ----
    // MiBandType.useAlternativeAuthFlag/useAlternativeCryptFlag no WatchDrip retornam true
    // ESPECIFICAMENTE p/ MI_BAND6 (entre outros): authFlags=0x00 (genérico seria 0x08),
    // cryptFlags=0x80 (genérico seria 0x00). Isso muda o pedido de nº aleatório de 2 p/ 5 bytes
    // e o opcode da resposta cifrada de 0x03 p/ 0x83 (AUTH_SEND_ENCRYPTED_AUTH_NUMBER|cryptFlags).
    private suspend fun authenticate(connection: RxBleConnection, key: ByteArray): Boolean {
        val authFlags: Byte = 0x00
        val cryptFlags: Byte = 0x80.toByte()
        fireWrite(connection, CH_AUTH, byteArrayOf((cryptFlags.toInt() or 0x02).toByte(), authFlags, 0x02, 0x01, 0x00))
        val rnd = withTimeoutOrNull(8000) { authNotify.receive() } ?: return fail("sem número aleatório")
        if (!(rnd[0] == RESPONSE && (rnd[1].toInt() and 0x0f) == 0x02)) return fail("auth random inesperado: ${hex(rnd)}")
        val enc = aesEcb(rnd.copyOfRange(3, rnd.size), key)
        fireWrite(connection, CH_AUTH, byteArrayOf((0x03 or cryptFlags.toInt()).toByte(), authFlags) + enc)
        val res = withTimeoutOrNull(8000) { authNotify.receive() } ?: return fail("sem confirmação de auth")
        if (!(res[0] == RESPONSE && (res[1].toInt() and 0x0f) == 0x03 && res[2].toInt() == 0x01)) return fail("auth recusada: ${hex(res)}")
        emit("Autenticado.")
        return true
    }

    private fun aesEcb(data: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(data)

    private suspend fun awaitFw(): ByteArray? = withTimeoutOrNull(15000) { fwNotify.receive() }

    @SuppressLint("CheckResult")
    private fun subscribeNotify(connection: RxBleConnection, uuid: UUID, channel: Channel<ByteArray>?) {
        disposables.add(
            connection.setupNotification(uuid)
                .flatMap { it }
                .subscribe(
                    { bytes ->
                        Log.i(tag, "RX $uuid: ${hex(bytes)}")
                        channel?.trySend(bytes)
                    },
                    { t -> Log.w(tag, "notify falhou em $uuid: $t") }
                )
        )
    }
}
