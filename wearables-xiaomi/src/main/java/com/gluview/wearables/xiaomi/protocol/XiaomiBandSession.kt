/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker e colaboradores do Gadgetbridge

    This file is part of Gadgetbridge (derived work, adapted for GluView).

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

    Adaptado de service/devices/xiaomi/XiaomiBleProtocolV1.java, XiaomiCharacteristicV1.java e
    XiaomiAuthService.java para o GluView — plano 21, F7.4 (22/11/2026). */
package com.gluview.wearables.xiaomi.protocol

import java.security.SecureRandom
import java.util.UUID

/** O que o app oferece de Bluetooth (RxAndroidBle no GluView; falso nos testes). */
interface XiaomiLink {
    suspend fun write(characteristic: UUID, value: ByteArray)
    /** Próxima notificação da característica, ou null por tempo esgotado/queda. */
    suspend fun receive(characteristic: UUID, timeoutMs: Long): ByteArray?
    val maxWrite: Int
}

/**
 * Uma conversa com a pulseira Xiaomi cifrada pelo enquadramento BLE V1: aperto de mão (nonce → HMAC da
 * pulseira → confirmação) e depois comandos cifrados (AES-CCM, índice crescente a partir de 1).
 */
class XiaomiBandSession(
    private val link: XiaomiLink,
    private val secretKey: ByteArray,
    private val log: (String) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
    /** V1 = Mi Band 8 e afins (0051/0052, AES-CCM); V2 = Mi Band 9 Active (005E/005F, AES-CTR). */
    private val protocol: Protocol = Protocol.V1
) {
    enum class Protocol { V1, V2 }

    private var session: XiaomiSession? = null
    private var index = 1
    private var seq = 0
    private var rx = ByteArray(0)
    private val pending = ArrayDeque<XiaomiV2Frames.Packet>()

    sealed interface Result {
        data object Ok : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun authenticate(apiLevel: Int, phoneName: String, region: String): Result {
        if (protocol == Protocol.V2) {
            // XiaomiBleProtocolV2.initializeDevice: abre a sessão antes do aperto de mão.
            // O pedido de sessão vai com sequência 0 e o contador dos dados também começa em 0.
            seq = 0
            link.write(XiaomiV2Frames.TX, XiaomiV2Frames.startSession(0))
            nextV2(5_000) { it.type == XiaomiV2Frames.TYPE_SESSION }
                ?: return Result.Failed("a pulseira não abriu a sessão (V2)")
        }
        val phoneNonce = ByteArray(16).also { random.nextBytes(it) }
        log("Autenticando (passo 1)…")
        sendCommand(XiaomiMessages.nonceCommand(phoneNonce), encrypted = false)?.let { return it }
        val reply = readCommand() ?: return Result.Failed("a pulseira não respondeu ao pedido de autenticação")
        val cmd = XiaomiMessages.parseCommand(reply)
        if (cmd.type != XiaomiMessages.TYPE_AUTH || cmd.subtype != XiaomiMessages.AUTH_NONCE || cmd.watchNonce == null || cmd.watchHmac == null)
            return Result.Failed("resposta inesperada no passo 2 (tipo ${cmd.type}/${cmd.subtype})")
        val s = XiaomiSession.fromWatch(secretKey, phoneNonce, cmd.watchNonce, cmd.watchHmac)
            ?: return Result.Failed("a chave de autenticação não confere com a pulseira")
        session = s
        log("Autenticando (passo 3)…")
        val info = s.encrypt(XiaomiMessages.companionDevice(apiLevel, phoneName, region), 0)
        sendCommand(XiaomiMessages.confirmCommand(s.confirmNonces(phoneNonce, cmd.watchNonce), info), encrypted = false)?.let { return it }
        val done = readCommand() ?: return Result.Failed("a pulseira não confirmou a autenticação")
        val ok = XiaomiMessages.parseCommand(done)
        if (ok.type != XiaomiMessages.TYPE_AUTH || ok.subtype != XiaomiMessages.AUTH_CONFIRM)
            return Result.Failed("a pulseira recusou a autenticação (tipo ${ok.type}/${ok.subtype}, status ${ok.status})")
        index = 1
        log("Autenticado.")
        return Result.Ok
    }

    /** Notificação na pulseira (vibra conforme o ajuste dela). Precisa de [authenticate] antes. */
    suspend fun notify(id: Int, title: String, body: String, whenMs: Long): Result =
        sendEncrypted(XiaomiMessages.notificationCommand(id, title, body, whenMs))

    /** Vibra o padrão [segmentsMs] (liga, pausa, …) — "teste de vibração" do protocolo. */
    suspend fun vibrate(segmentsMs: LongArray): Result =
        if (segmentsMs.isEmpty()) Result.Ok else sendEncrypted(XiaomiMessages.vibrationCommand(segmentsMs))

    private suspend fun sendEncrypted(command: ByteArray): Result {
        val s = session ?: return Result.Failed("sem autenticação")
        if (protocol == Protocol.V2) return sendV2(s.encryptV2(command), XiaomiV2Frames.OPCODE_ENCRYPTED) ?: Result.Ok
        val i = index++
        return sendCommand(s.encrypt(command, i), encrypted = true, i) ?: Result.Ok
    }

    // ---- V2
    /**
     * Próximo pacote V2 que satisfaz [want]. Os de dados recebem confirmação na hora; os que chegam fora
     * de hora (ex.: a resposta antes da confirmação) ficam guardados para quem pedir depois.
     */
    private suspend fun nextV2(timeoutMs: Long, want: (XiaomiV2Frames.Packet) -> Boolean): XiaomiV2Frames.Packet? {
        pending.firstOrNull(want)?.let { pending.remove(it); return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            while (true) {
                val (p, used) = XiaomiV2Frames.take(rx)
                if (used == 0) break
                rx = rx.copyOfRange(used, rx.size)
                if (p == null) continue
                if (p.type == XiaomiV2Frames.TYPE_DATA) link.write(XiaomiV2Frames.TX, XiaomiV2Frames.ack(p.seq))
                if (want(p)) return p
                if (p.type == XiaomiV2Frames.TYPE_DATA) pending.addLast(p)
            }
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            rx += link.receive(XiaomiV2Frames.RX, left) ?: return null
        }
    }

    private suspend fun sendV2(payload: ByteArray, opcode: Int): Result? {
        val frame = XiaomiV2Frames.data(seq++, opcode, payload)
        frame.toList().chunked(link.maxWrite).forEach { link.write(XiaomiV2Frames.TX, it.toByteArray()) }
        // O Gadgetbridge não espera a confirmação; aqui esperamos um pouco só para pegar uma recusa (NACK).
        val r = nextV2(2_000) { it.type == XiaomiV2Frames.TYPE_ACK || it.type == XiaomiV2Frames.TYPE_NACK }
        return if (r?.type == XiaomiV2Frames.TYPE_NACK) Result.Failed("a pulseira recusou o pacote (V2)") else null
    }

    /** Escreve um comando em 0052 e espera a confirmação; null = ok, senão a falha. */
    private suspend fun sendCommand(payload: ByteArray, encrypted: Boolean, index: Int = 0): Result? {
        if (protocol == Protocol.V2) return sendV2(payload, if (encrypted) XiaomiV2Frames.OPCODE_ENCRYPTED else XiaomiV2Frames.OPCODE_PLAIN)
        val w = XiaomiV1Frames.COMMAND_WRITE
        if (XiaomiV1Frames.fitsSingle(payload.size, link.maxWrite)) {
            link.write(w, XiaomiV1Frames.single(payload, encrypted, index))
            return when (val r = link.receive(w, 5_000)?.let(XiaomiV1Frames::parse)) {
                is XiaomiV1Frames.Incoming.Result -> if (r.ok) null else Result.Failed("a pulseira recusou o comando")
                null -> Result.Failed("sem confirmação da pulseira")
                else -> Result.Failed("confirmação inesperada: $r")
            }
        }
        // Em pedaços; cifrado leva o índice na frente (XiaomiCharacteristicV1.sendNext).
        val data = if (encrypted) byteArrayOf((index and 0xff).toByte(), ((index ushr 8) and 0xff).toByte()) + payload else payload
        val parts = XiaomiV1Frames.chunks(data, link.maxWrite)
        link.write(w, XiaomiV1Frames.chunkStart(parts.size, encrypted))
        if (link.receive(w, 5_000)?.let(XiaomiV1Frames::parse) != XiaomiV1Frames.Incoming.ReadyForChunks)
            return Result.Failed("a pulseira não aceitou o envio em pedaços")
        parts.forEach { link.write(w, it) }
        return when (val r = link.receive(w, 8_000)?.let(XiaomiV1Frames::parse)) {
            XiaomiV1Frames.Incoming.ChunksReceived -> null
            null -> Result.Failed("sem confirmação dos pedaços")
            else -> Result.Failed("confirmação inesperada dos pedaços: $r")
        }
    }

    /** Lê um comando da pulseira em 0051 (curto ou em pedaços), confirma e decifra se preciso. */
    private suspend fun readCommand(): ByteArray? {
        if (protocol == Protocol.V2) {
            val p = nextV2(8_000) { it.type == XiaomiV2Frames.TYPE_DATA && it.channel == XiaomiV2Frames.CHANNEL_PROTOBUF } ?: return null
            return if (p.opcode == XiaomiV2Frames.OPCODE_ENCRYPTED) session?.decryptV2(p.body) else p.body
        }
        val r = XiaomiV1Frames.COMMAND_READ
        when (val first = link.receive(r, 8_000)?.let(XiaomiV1Frames::parse) ?: return null) {
            is XiaomiV1Frames.Incoming.Command -> {
                link.write(r, XiaomiV1Frames.ACK)
                return if (first.encrypted) session?.decrypt(first.payload) else first.payload
            }
            is XiaomiV1Frames.Incoming.ChunkedStart -> {
                link.write(r, XiaomiV1Frames.CHUNK_START_ACK)
                val got = HashMap<Int, ByteArray>()
                while (got.size < first.count) {
                    val c = link.receive(r, 5_000)?.let(XiaomiV1Frames::parse) as? XiaomiV1Frames.Incoming.Chunk ?: return null
                    got[c.index] = c.data
                }
                link.write(r, XiaomiV1Frames.CHUNK_END_ACK)
                val all = (1..first.count).fold(ByteArray(0)) { acc, i -> acc + (got[i] ?: return null) }
                return if (first.encrypted) session?.decrypt(all) else all
            }
            else -> return null
        }
    }
}
