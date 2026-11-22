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

    Adaptado de GBProtobufGenerator/src/main/proto/xiaomi.proto, xiaomi/wear_*.proto, XiaomiAuthService.java e
    services/XiaomiNotificationService.java para o GluView — plano 21, F7.4 (22/11/2026). */
package com.gluview.wearables.xiaomi.protocol

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Só as mensagens protobuf (proto2) de que o GluView precisa, escritas à mão para não pôr gerador de
 * código no build: Command (tipo 1 = autenticação, tipo 7 = notificação), Account/Auth e Notification.
 * Os números de campo são os do xiaomi.proto do Gadgetbridge.
 */
object XiaomiMessages {
    const val TYPE_AUTH = 1
    const val AUTH_NONCE = 26
    const val AUTH_CONFIRM = 27
    const val TYPE_NOTIFICATION = 7
    const val NOTIFICATION_SEND = 0

    // ---- escrita (proto2: só o que foi definido)
    class Writer {
        private val out = ByteArrayOutputStream()
        private fun varint(v: Long) { var x = v; while (x and 0x7fL.inv() != 0L) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun uint(field: Int, v: Long) = apply { tag(field, 0); varint(v) }
        fun bool(field: Int, v: Boolean) = uint(field, if (v) 1 else 0)
        fun bytes(field: Int, v: ByteArray) = apply { tag(field, 2); varint(v.size.toLong()); out.write(v) }
        fun string(field: Int, v: String) = bytes(field, v.toByteArray(Charsets.UTF_8))
        fun float(field: Int, v: Float) = apply {
            tag(field, 5); val b = java.lang.Float.floatToIntBits(v)
            for (k in 0 until 4) out.write((b ushr (8 * k)) and 0xff)
        }
        fun build(): ByteArray = out.toByteArray()
    }

    // ---- leitura genérica: número do campo → valores (Long para varint/fixed, ByteArray para delimitado)
    fun parse(data: ByteArray): Map<Int, List<Any>> {
        val m = LinkedHashMap<Int, MutableList<Any>>()
        var i = 0
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val b = data[i++].toInt() and 0xff; r = r or ((b and 0x7f).toLong() shl s); if (b < 0x80) return r; s += 7 } }
        while (i < data.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            val v: Any = when ((key and 7).toInt()) {
                0 -> varint()
                1 -> { var r = 0L; for (k in 0 until 8) r = r or ((data[i + k].toLong() and 0xff) shl (8 * k)); i += 8; r }
                2 -> { val n = varint().toInt(); val b = data.copyOfRange(i, i + n); i += n; b }
                5 -> { var r = 0L; for (k in 0 until 4) r = r or ((data[i + k].toLong() and 0xff) shl (8 * k)); i += 4; r }
                else -> throw IllegalArgumentException("tipo de campo não suportado")
            }
            m.getOrPut(field) { mutableListOf() }.add(v)
        }
        return m
    }

    private fun Map<Int, List<Any>>.msg(f: Int) = (this[f]?.firstOrNull() as? ByteArray)?.let { parse(it) }
    private fun Map<Int, List<Any>>.bytes(f: Int) = this[f]?.firstOrNull() as? ByteArray
    private fun Map<Int, List<Any>>.num(f: Int) = (this[f]?.firstOrNull() as? Long)?.toInt()

    // ---- autenticação
    /** Passo 1: Command{type 1, subtype 26, auth{appVerify{nonce}}} (buildNonceCommand). */
    fun nonceCommand(phoneNonce: ByteArray): ByteArray {
        val appVerify = Writer().bytes(1, phoneNonce).build()
        val account = Writer().bytes(30, appVerify).build()
        return Writer().uint(1, TYPE_AUTH.toLong()).uint(2, AUTH_NONCE.toLong()).bytes(3, account).build()
    }

    /** CompanionDevice (unknown1 = 0 tem de ir explícito; 224 como o Gadgetbridge). */
    fun companionDevice(apiLevel: Int, phoneName: String, region: String): ByteArray =
        Writer().uint(1, 0).float(2, apiLevel.toFloat()).string(3, phoneName).uint(4, 224).string(5, region).build()

    /** Passo 3: Command{type 1, subtype 27, auth{appConfirm{encryptedNonces, encryptedDeviceInfo}}}. */
    fun confirmCommand(encryptedNonces: ByteArray, encryptedDeviceInfo: ByteArray): ByteArray {
        val confirm = Writer().bytes(1, encryptedNonces).bytes(2, encryptedDeviceInfo).build()
        val account = Writer().bytes(32, confirm).build()
        return Writer().uint(1, TYPE_AUTH.toLong()).uint(2, AUTH_CONFIRM.toLong()).bytes(3, account).build()
    }

    data class Command(val type: Int, val subtype: Int, val watchNonce: ByteArray?, val watchHmac: ByteArray?, val status: Int?)

    fun parseCommand(data: ByteArray): Command {
        val c = parse(data)
        val auth = c.msg(3)
        val dv = auth?.msg(31)
        return Command(c.num(1) ?: -1, c.num(2) ?: -1, dv?.bytes(1), dv?.bytes(2), c.num(100) ?: auth?.num(8))
    }

    // ---- notificação (XiaomiNotificationService.onNotification)
    private val TS = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT)

    fun notificationCommand(id: Int, title: String, body: String, whenMs: Long,
                            packageName: String = "com.gluview.app", appName: String = "GluView"): ByteArray {
        val n3 = Writer().string(1, packageName).string(2, appName).string(3, title).string(4, "")
            .string(5, body).string(6, synchronized(TS) { TS.format(Date(whenMs)) }).uint(7, id.toLong() and 0xffffffffL).build()
        val n2 = Writer().bytes(1, n3).build()
        val notification = Writer().bytes(3, n2).build()
        return Writer().uint(1, TYPE_NOTIFICATION.toLong()).uint(2, NOTIFICATION_SEND.toLong()).bytes(9, notification).build()
    }
}
