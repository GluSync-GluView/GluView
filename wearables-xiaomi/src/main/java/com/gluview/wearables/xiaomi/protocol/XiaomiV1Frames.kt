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

    Adaptado de service/devices/xiaomi/XiaomiCharacteristicV1.java e XiaomiUuids.java para o GluView —
    plano 21, F7.4 (22/11/2026). */
package com.gluview.wearables.xiaomi.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Enquadramento "BLE V1" das pulseiras Xiaomi cifradas (Mi Band 8, Redmi Band 2, Watch S1…): serviço FE95,
 * o app escreve comandos em 0052 e a pulseira responde/manda em 0051. Pacote começa com u16 = 0 (controle)
 * ou = número do pedaço (1..n):
 *   00 00 02 [1 cifrado | 2 claro] [u16 índice, só cifrado] dados   → comando curto; resposta 00 00 03 00
 *   00 00 00 [cifrado] [u16 n]                                       → início de envio em n pedaços
 *   00 00 01 01 → pronto para os pedaços · 00 00 01 00 → recebeu tudo · 00 00 01 02 → recusado
 */
object XiaomiV1Frames {
    val SERVICE: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    val COMMAND_READ: UUID = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb")
    val COMMAND_WRITE: UUID = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb")
    val ACTIVITY_DATA: UUID = UUID.fromString("00000053-0000-1000-8000-00805f9b34fb")
    val DATA_UPLOAD: UUID = UUID.fromString("00000055-0000-1000-8000-00805f9b34fb")

    val ACK = byteArrayOf(0, 0, 3, 0)
    val CHUNK_START_ACK = byteArrayOf(0, 0, 1, 1)
    val CHUNK_END_ACK = byteArrayOf(0, 0, 1, 0)


    /** Comando curto. [index] só existe quando cifrado (o payload já vem cifrado com esse índice). */
    fun single(payload: ByteArray, encrypted: Boolean, index: Int = 0): ByteArray {
        val bb = ByteBuffer.allocate((if (encrypted) 6 else 4) + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0).put(2).put(if (encrypted) 1 else 2)
        if (encrypted) bb.putShort(index.toShort())
        return bb.put(payload).array()
    }

    /** Cabe num pacote só? (shouldWriteChunked do Gadgetbridge, para pulseira cifrada) */
    fun fitsSingle(payloadSize: Int, maxWrite: Int): Boolean = payloadSize + 6 <= maxWrite

    fun chunkStart(numChunks: Int, encrypted: Boolean): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(0).put(0).put(if (encrypted) 1 else 0)
            .putShort(numChunks.toShort()).array()

    /** Pedaços (1..n), cada um com até maxWrite-2 bytes de dados. */
    fun chunks(payload: ByteArray, maxWrite: Int): List<ByteArray> {
        val size = maxWrite - 2
        return (0 until (payload.size + size - 1) / size).map { i ->
            val end = minOf((i + 1) * size, payload.size)
            ByteBuffer.allocate(2 + end - i * size).order(ByteOrder.LITTLE_ENDIAN).putShort((i + 1).toShort())
                .put(payload, i * size, end - i * size).array()
        }
    }

    sealed interface Incoming {
        data class Result(val ok: Boolean) : Incoming                 // 00 00 03 rr
        data object ReadyForChunks : Incoming                         // 00 00 01 01
        data object ChunksReceived : Incoming                         // 00 00 01 00
        data class Refused(val subtype: Int) : Incoming               // 00 00 01 02/05
        data class Command(val encrypted: Boolean, val payload: ByteArray) : Incoming   // 00 00 02 ee …
        data class ChunkedStart(val encrypted: Boolean, val count: Int) : Incoming      // 00 00 00 ee n
        data class Chunk(val index: Int, val data: ByteArray) : Incoming
        data class Unknown(val raw: ByteArray) : Incoming
    }

    fun parse(v: ByteArray): Incoming {
        if (v.size < 2) return Incoming.Unknown(v)
        val bb = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
        val chunk = bb.short.toInt() and 0xffff
        if (chunk != 0) return Incoming.Chunk(chunk, v.copyOfRange(2, v.size))
        if (v.size < 4) return Incoming.Unknown(v)
        return when (v[2].toInt()) {
            0 -> if (v.size >= 6) Incoming.ChunkedStart(v[3].toInt() == 1, ((v[4].toInt() and 0xff) or ((v[5].toInt() and 0xff) shl 8))) else Incoming.Unknown(v)
            1 -> when (v[3].toInt()) { 1 -> Incoming.ReadyForChunks; 0 -> Incoming.ChunksReceived; else -> Incoming.Refused(v[3].toInt()) }
            2 -> Incoming.Command(v[3].toInt() == 1, v.copyOfRange(4, v.size))
            3 -> Incoming.Result(v[3].toInt() == 0)
            else -> Incoming.Unknown(v)
        }
    }
}
