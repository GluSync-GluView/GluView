package com.gluview.app.data.medlevensohn

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocolo BLE do Smart 2.0 (MedLevensohn) — fabricado pela chinesa **Microtech MD**, a MESMA
 * fabricante do AiDEX X / LinX / Lumi. Confirmado: (1) prefixo de anúncio BLE `"Smart-"` já
 * reconhecido no Juggluco lado a lado com `"AiDEX X-"`/`"LinX-"`/`"Lumi-"`; (2) vídeo público
 * mostrando Smart 2.0 → Juggluco → xDrip funcionando (abr/2026).
 *
 * Diferente do [com.gluview.app.data.accuchek.cgm.SmartGuideGatt] (perfil Bluetooth SIG padrão sem
 * cripto), aqui HÁ cripto própria — mas é MD5 + AES-128-CFB128 com IV/chave DERIVADOS DO NÚMERO
 * DE SÉRIE do sensor (não ECDH nem white-box como o Libre 2 Plus, e não precisa de nenhuma .so
 * proprietária: dá para portar 100% em Kotlin puro usando `javax.crypto`).
 *
 * Fonte: j-kaltes/Juggluco (GPLv3) — `Common/src/main/cpp/aidexx/{glucose.h,crc.cpp,decrypt.cpp,
 * initcrypto.cpp,mkiv.cpp,java.cpp}` + `Common/src/dex/java/tk/glucodata/AidexXGattCallback.java`.
 * Os vetores de teste abaixo ([MedLevensohnProtocolTest]) vêm de exemplos REAIS embutidos nos
 * comentários desse código-fonte (capturas de sniff BLE + chamadas de auto-teste do próprio
 * Juggluco) — não são apenas sintéticos-pela-spec como no caso do SmartGuide.
 *
 * ★ SEM HARDWARE PARA TESTE (27/07/2026): cobre o handshake de chave + glicemia atual (f003) +
 * leitura via advertisement (sem conexão). NÃO cobre ainda: paginação completa de histórico
 * (opcodes 0x123/0x124), unpair/clear, nem OTA — ficam para quando o sensor chegar e o caminho
 * básico estiver validado.
 */
object MedLevensohnGatt {
    private fun sig(shortUuid: String): UUID =
        UUID.fromString("0000$shortUuid-0000-1000-8000-00805f9b34fb")

    /** Reaproveita o número 0x181F, mas NÃO é o CGM Service oficial da Bluetooth SIG — é só um contêiner. */
    val SERVICE: UUID              = sig("181f")
    val CHAR_MAIN: UUID            = sig("f002") // dados/histórico — write+notify, cifrado
    val CHAR_PRIVATE_UNBONDED: UUID = sig("f001") // pede a masterKey (MD5 do serial) — write+notify
    val CHAR_PRIVATE_BONDED: UUID  = sig("f003") // glicemia atual ao vivo — notify, cifrado
    val CCCD: UUID                  = sig("2902")

    private val NAME_PREFIXES = listOf("AiDEX X-", "LinX-", "Lumi-", "Smart-")

    /** true para qualquer sensor da família Microtech MD (AiDEX X, LinX, Lumi, Smart 2.0). */
    fun isMicrotechName(name: String?): Boolean =
        name != null && NAME_PREFIXES.any { name.startsWith(it) }

    /** Os últimos [serialLength] caracteres do nome BLE devem bater com o serial conhecido do sensor. */
    fun matchesSerial(name: String, serial: String): Boolean =
        name.length >= serial.length && name.takeLast(serial.length) == serial

    /** Extrai o serial do nome de anúncio BLE — tudo após o último '-' (os 4 prefixos conhecidos terminam em '-'). */
    fun extractSerial(deviceName: String): String = deviceName.substringAfterLast('-')
}

/**
 * Deriva IV e chaves a partir do número de série — MESMA transformação para os dois usos,
 * só o multiplicador/soma mudam (`mkiv.cpp`: `mkiv()` genérico + `mkAidexXiv()`/`aidexXaskKey()`).
 */
object MedLevensohnCrypto {

    /** `snCharToVal`: dígito→0-9, 'A'-'Z'/'a'-'z'→0x0A-0x23 (mesma tabela p/ maiúsculas/minúsculas), senão o byte cru. */
    private fun serialCharToValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c.code - 0x37
        in 'a'..'z' -> c.code - 0x57
        else -> c.code and 0xFF
    }

    private fun serialToBytes(serial: String): ByteArray =
        ByteArray(serial.length) { serialCharToValue(serial[it]).toByte() }

    private fun md5(data: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(data)

    /** IV (16 bytes) = MD5(bytes[i] = bytes[i]*17 + 0x13). CONSTANTE por sensor — mesmo IV em toda a sessão. */
    fun deriveIv(serial: String): ByteArray {
        val bytes = serialToBytes(serial)
        val transformed = ByteArray(bytes.size) { i -> ((bytes[i].toInt() and 0xFF) * 17 + 0x13).toByte() }
        return md5(transformed)
    }

    /** Payload (16 bytes) escrito na característica f001 pra pedir a masterKey: MD5(bytes[i]*13 + 61). */
    fun deriveAskKeyPayload(serial: String): ByteArray {
        val bytes = serialToBytes(serial)
        val transformed = ByteArray(bytes.size) { i -> ((bytes[i].toInt() and 0xFF) * 13 + 61).toByte() }
        return md5(transformed)
    }

    /** AES-128-CFB128 (sem padding) — mesmo modo usado pelo Juggluco via OpenSSL `EVP_aes_128_cfb128`. */
    fun aesCfb128Decrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CFB128/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    fun aesCfb128Encrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CFB128/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plaintext)
    }
}

/** CRCs usados no protocolo: CRC8-Maxim (valida a chave de sessão derivada), CRC16-CCITT-false
 * (valida cada payload decifrado do handshake/histórico) e CRC32 customizado (valida o pacote
 * de advertisement, sem precisar de conexão GATT). Fonte: `aidexx/crc.cpp`. */
object MedLevensohnCrc {

    /** Polinômio 0x8C refletido (Maxim/Dallas 1-Wire) — usado para validar a sessionKey derivada (17º byte). */
    fun crc8Maxim(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1
            }
        }
        return crc and 0xFF
    }

    /** CCITT-FALSE (poly 0x1021, init 0xFFFF, sem reflexão) — valida cada payload do handshake/histórico. */
    fun crc16CcittFalse(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** CRC32 não-refletido (poly 0x04C11DB7) com SEED derivado dos próprios bytes do pacote — protege o advertisement. */
    fun crc32Normal(data: ByteArray, offset: Int, length: Int, seed: Long): Long {
        var crc = seed and 0xFFFFFFFFL
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toLong() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000L != 0L) ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL
                      else (crc shl 1) and 0xFFFFFFFFL
            }
        }
        return crc and 0xFFFFFFFFL
    }
}

// ── Estruturas de glicemia (bitfields fiéis a glucose.h) ────────────────────────────────────────

/** `CurrentGlucose` — payload de 15 bytes decifrado da notificação f003 (glicemia ao vivo). */
data class CurrentGlucose(
    val type: Int,           // byte0 (via one16 LSB): 1=normal, 3=sensor encerrado
    val trend: Int,          // signed, ×0.1 = taxa mg/dL por min
    val minFromStart: Int,
    val glucoseMgDl: Int,    // 10 bits, já em mg/dL (sem conversão de unidade)
    val warmup: Boolean,
    val valid: Boolean
) {
    companion object {
        private const val SIZE = 15
        fun parse(plaintext: ByteArray): CurrentGlucose? {
            if (plaintext.size < SIZE) return null
            val type = plaintext[0].toInt() and 0xFF
            val trend = plaintext[3].toInt() // int8 signed
            val minFromStart = ((plaintext[5].toInt() and 0xFF) shl 8) or (plaintext[4].toInt() and 0xFF)
            val glucoseWord = ((plaintext[7].toInt() and 0xFF) shl 8) or (plaintext[6].toInt() and 0xFF)
            return CurrentGlucose(
                type = type,
                trend = trend,
                minFromStart = minFromStart,
                glucoseMgDl = glucoseWord and 0x03FF,
                warmup = (glucoseWord shr 10) and 0x01 != 0,
                valid = (glucoseWord shr 15) and 0x01 != 0
            )
        }
    }
}

/** `LastPast` — 8 bytes, usado no advertisement (glicemia mais recente) e na resposta 0x111. */
data class LastPastGlucose(
    val minFromStart: Int,
    val status: Int,
    val calTemp: Int,
    val trend: Int,
    val glucoseMgDl: Int,
    val valid: Boolean,
    val quality: Int
) {
    companion object {
        const val SIZE = 8
        fun parse(bytes: ByteArray, offset: Int = 0): LastPastGlucose? {
            if (bytes.size < offset + SIZE) return null
            val minFromStart = ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)
            val status = bytes[offset + 2].toInt() and 0xFF
            val calTemp = bytes[offset + 3].toInt() and 0xFF
            val trend = bytes[offset + 4].toInt() // int8 signed
            val glucoseWord = ((bytes[offset + 6].toInt() and 0xFF) shl 8) or (bytes[offset + 5].toInt() and 0xFF)
            val quality = bytes[offset + 7].toInt() and 0xFF
            return LastPastGlucose(
                minFromStart = minFromStart,
                status = status,
                calTemp = calTemp,
                trend = trend,
                glucoseMgDl = glucoseWord and 0x03FF,
                valid = (glucoseWord shr 15) and 0x01 != 0,
                quality = quality
            )
        }
    }
}

/** `NextGlucose` — 3 bytes, os até 2 registros anteriores ao mais recente (histórico curto do advertisement). */
data class NextGlucose(val glucoseMgDl: Int, val valid: Boolean) {
    companion object {
        const val SIZE = 3
        fun parse(bytes: ByteArray, offset: Int): NextGlucose? {
            if (bytes.size < offset + SIZE) return null
            val glucoseWord = ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)
            return NextGlucose(glucoseWord and 0x03FF, (glucoseWord shr 15) and 0x01 != 0)
        }
    }
}

/**
 * Pacote de advertisement BLE (62 bytes) — dá pra ler a glicemia SEM conectar/parear/decifrar
 * nada, só passivamente escaneando. Protegido por um CRC32 customizado (seed derivado dos
 * próprios bytes), NÃO criptografado. Layout fiel a `glucose.h::AdvertiseBytes`.
 */
object MedLevensohnAdvertisement {
    const val EXPECTED_SIZE = 62
    private const val PAYLOAD_OFFSET = 11 // início do bloco protegido pelo CRC (LastPast+NextGlucose×2+reserved+crc32)
    private const val PAYLOAD_LEN = 0x10  // 16 bytes cobertos pelo CRC (antes do próprio crc32)

    data class Advertisement(
        val last: LastPastGlucose,
        val previous: List<NextGlucose>,
        val deviceName: String,
        val crcOk: Boolean
    )

    private fun mkSeed(bytes: ByteArray): Long {
        fun le32(off: Int): Long {
            var v = 0L
            for (i in 0..3) v = v or ((bytes[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }
        val w0 = le32(PAYLOAD_OFFSET); val w1 = le32(PAYLOAD_OFFSET + 4)
        val w2 = le32(PAYLOAD_OFFSET + 8); val w3 = le32(PAYLOAD_OFFSET + 12)
        return (w0 + w1 + w2 + w3) % 0x7FA777L
    }

    fun parse(bytes: ByteArray): Advertisement? {
        if (bytes.size < EXPECTED_SIZE) return null
        val last = LastPastGlucose.parse(bytes, PAYLOAD_OFFSET) ?: return null
        val prev = listOf(
            NextGlucose.parse(bytes, PAYLOAD_OFFSET + LastPastGlucose.SIZE),
            NextGlucose.parse(bytes, PAYLOAD_OFFSET + LastPastGlucose.SIZE + NextGlucose.SIZE)
        ).filterNotNull()

        val crc32Offset = PAYLOAD_OFFSET + PAYLOAD_LEN
        val expectedCrc = readU32LE(bytes, crc32Offset)
        val actualCrc = MedLevensohnCrc.crc32Normal(bytes, PAYLOAD_OFFSET, PAYLOAD_LEN, mkSeed(bytes))

        val nameOffset = crc32Offset + 4 + 2 // +4 (crc32) +2 (name_len/name_type octets)
        val nameEnd = (nameOffset + 18).coerceAtMost(bytes.size)
        val deviceName = if (nameOffset < nameEnd)
            String(bytes, nameOffset, nameEnd - nameOffset).trimEnd(' ') else ""

        return Advertisement(last, prev, deviceName, actualCrc == expectedCrc)
    }

    private fun readU32LE(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0..3) v = v or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
