/*  Copyright (C) 2023-2025 Yoran Vulker, José Rebelo e colaboradores do Gadgetbridge

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

    Adaptado de service/devices/xiaomi/XiaomiSppPacketV2.java, XiaomiBleProtocolV2.java e XiaomiUuids.java
    para o GluView — plano 21, F7.4 (22/11/2026). */
package com.gluview.wearables.xiaomi.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Enquadramento "BLE V2" (Mi Band 9 Active): serviço FE95, a pulseira notifica em 005E e o app escreve em
 * 005F; cada pacote = A5 A5, tipo (0 nack, 1 ack, 2 sessão, 3 dados), sequência, u16 tamanho, u16 CRC do
 * conteúdo, conteúdo. Dados = canal (1 protobuf, 2 dados, 5 atividade) + opcode (1 claro, 2 cifrado AES-CTR).
 */
object XiaomiV2Frames {
    val SERVICE: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    val RX: UUID = UUID.fromString("0000005e-0000-1000-8000-00805f9b34fb")   // pulseira → app
    val TX: UUID = UUID.fromString("0000005f-0000-1000-8000-00805f9b34fb")   // app → pulseira

    const val TYPE_NACK = 0
    const val TYPE_ACK = 1
    const val TYPE_SESSION = 2
    const val TYPE_DATA = 3
    const val CHANNEL_PROTOBUF = 1
    const val CHANNEL_DATA = 2
    const val OPCODE_PLAIN = 1
    const val OPCODE_ENCRYPTED = 2
    const val SESSION_START_REQUEST = 1
    const val SESSION_START_RESPONSE = 2

    /** calculatePayloadChecksum do Gadgetbridge (CRC-16/ARC). */
    fun checksum(payload: ByteArray): Int {
        var crc = 0
        for (b in payload) {
            for (j in 0 until 8) {
                crc = crc shl 1
                if ((((crc shr 16) and 1) xor ((b.toInt() shr j) and 1)) == 1) crc = crc xor 0x8005
            }
        }
        return Integer.reverse(crc) ushr 16
    }

    fun frame(type: Int, seq: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(0xa5.toByte()).put(0xa5.toByte()).put((type and 0xf).toByte()).put((seq and 0xff).toByte())
            .putShort(payload.size.toShort()).putShort(checksum(payload).toShort()).put(payload).array()

    /** Pedido de início de sessão (versão 1.0.0, pacote máx. 0xFC00, janela 0x20, tempo 10000 ms). */
    fun startSession(seq: Int = 0): ByteArray = frame(TYPE_SESSION, seq, byteArrayOf(
        SESSION_START_REQUEST.toByte(), 1, 0x03, 0x00, 0x01, 0x00, 0x00, 2, 0x02, 0x00, 0x00, 0xfc.toByte(),
        3, 0x02, 0x00, 0x20, 0x00, 4, 0x02, 0x00, 0x10, 0x27))

    fun ack(seq: Int): ByteArray = frame(TYPE_ACK, seq, ByteArray(0))

    fun data(seq: Int, opcode: Int, payload: ByteArray, channel: Int = CHANNEL_PROTOBUF): ByteArray =
        frame(TYPE_DATA, seq, byteArrayOf((channel and 0xf).toByte(), opcode.toByte()) + payload)

    data class Packet(val type: Int, val seq: Int, val payload: ByteArray) {
        val channel: Int get() = if (type == TYPE_DATA && payload.isNotEmpty()) payload[0].toInt() and 0xf else -1
        val opcode: Int get() = if (type == TYPE_DATA && payload.size > 1) payload[1].toInt() and 0xff else -1
        val body: ByteArray get() = if (type == TYPE_DATA && payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)
    }

    /**
     * Tira um pacote do começo do buffer. Devolve (pacote ou null, bytes consumidos); consumidos = 0 quando
     * ainda falta chegar o resto. Lixo no começo (sem A5 A5) ou CRC errado é descartado.
     */
    fun take(buf: ByteArray): Pair<Packet?, Int> {
        if (buf.size < 8) return null to 0
        if (buf[0] != 0xa5.toByte() || buf[1] != 0xa5.toByte()) {
            val next = (1 until buf.size).firstOrNull { buf[it] == 0xa5.toByte() } ?: buf.size
            return null to next
        }
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf[2].toInt() and 0xf
        val seq = buf[3].toInt() and 0xff
        val len = bb.getShort(4).toInt() and 0xffff
        val crc = bb.getShort(6).toInt() and 0xffff
        if (buf.size < 8 + len) return null to 0
        val payload = buf.copyOfRange(8, 8 + len)
        return (if (checksum(payload) == crc) Packet(type, seq, payload) else null) to (8 + len)
    }
}
