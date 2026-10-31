package com.gluview.app.data.dexcom

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Protocolo BLE do Dexcom G6 (e o formato de dados, compartilhado com o G7 — ver nota no fim).
 *
 * G6: porta o driver nativo e maduro do próprio **xDrip** (`NightscoutFoundation/xDrip`, GPLv3,
 * pacote `com.eveningoutpost.dexdrip.g5model` — nome "g5" porque o G6 reaproveita o mesmo
 * protocolo BLE do G5 no xDrip). Handshake de autenticação DESAFIO-RESPOSTA clássico e bem
 * documentado publicamente (não é segredo de fabricante — pesquisa de segurança sobre o G5/G6
 * já publicou esse esquema): a chave AES-128 é derivada TRIVIALMENTE do Transmitter ID de 6
 * caracteres impresso no transmissor (`"00"+id+"00"+id`, 16 bytes ASCII), sem handshake
 * criptográfico de troca de chave nenhum.
 *
 * G7: usa um protocolo BEM mais complexo — EC-JPAKE (troca de chave autenticada por senha sobre
 * curva elíptica P-256) + validação de cadeia de certificados, ~800 linhas de aritmética de curva
 * elíptica no Juggluco (`Common/src/main/cpp/dexcom/ecJPake.cpp`, GPLv3). NÃO portado nesta
 * sessão — é um projeto de RE criptográfico por si só (dias de trabalho dedicado, não uma tarde).
 * O que ESTÁ pronto pro G7: os UUIDs GATT e o formato do registro de glicemia pós-decifra (ambos
 * idênticos/muito próximos ao G6 nesse nível), reaproveitáveis quando alguém portar o handshake.
 */
object DexcomGatt {
    private fun sig(shortUuid: String): UUID =
        UUID.fromString("0000$shortUuid-0000-1000-8000-00805f9b34fb")

    /** Serviço anunciado no scan BLE — filtro usado por xDrip E Juggluco para os dois modelos. */
    val ADVERTISEMENT_SERVICE: UUID = sig("febc")

    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    val MANUFACTURER_NAME: UUID   = sig("2a29")

    /** Serviço CGM do transmissor — mesmo UUID no G6 (xDrip) e no G7 (Juggluco). */
    val CGM_SERVICE: UUID = UUID.fromString("F8083532-849E-531C-C594-30F1F86A4EA5")

    val COMMUNICATION: UUID  = UUID.fromString("F8083533-849E-531C-C594-30F1F86A4EA5") // dados/comandos gerais
    val CONTROL: UUID        = UUID.fromString("F8083534-849E-531C-C594-30F1F86A4EA5")
    val AUTHENTICATION: UUID = UUID.fromString("F8083535-849E-531C-C594-30F1F86A4EA5")
    val BACKFILL: UUID       = UUID.fromString("F8083536-849E-531C-C594-30F1F86A4EA5")
    val EXTRA_DATA: UUID     = UUID.fromString("F8083538-849E-531C-C594-30F1F86A4EA5")
    val CCCD: UUID           = sig("2902")

    /** true se [transmitterId] tem o formato esperado (6 caracteres alfanuméricos). */
    fun isValidTransmitterId(transmitterId: String): Boolean =
        transmitterId.length == 6 && transmitterId.all { it.isLetterOrDigit() }
}

/**
 * Handshake de autenticação do G6 — porte fiel de `Ob1G5StateMachine.getCryptKey/
 * calculateChallengeHash` do xDrip. AES-128-ECB/PKCS7, SEM troca de chave: a chave é derivada
 * diretamente do Transmitter ID (documentado publicamente, não é engenharia reversa de binário).
 */
object DexcomG6Crypto {
    /** Chave AES-128 = ASCII("00" + id + "00" + id), 16 bytes. [transmitterId] deve ter 6 caracteres. */
    fun deriveKey(transmitterId: String): ByteArray {
        require(transmitterId.length == 6) { "Transmitter ID deve ter 6 caracteres" }
        return ("00$transmitterId" + "00$transmitterId").toByteArray(Charsets.UTF_8)
    }

    /**
     * "Challenge hash": duplica os 8 bytes de [challenge] pra formar um bloco de 16, cifra com
     * AES-ECB/PKCS7 usando [key], e devolve só os primeiros 8 bytes do resultado. Usada nos dois
     * sentidos: para verificar o tokenHash que o transmissor manda de volta, E para responder ao
     * challenge que ELE manda — mesma função, fiel ao `calculateChallengeHash` do xDrip.
     */
    fun challengeHash(challenge: ByteArray, key: ByteArray): ByteArray {
        require(challenge.size == 8) { "challenge deve ter 8 bytes" }
        require(key.size == 16) { "key deve ter 16 bytes" }
        val plaintext = ByteArray(16)
        System.arraycopy(challenge, 0, plaintext, 0, 8)
        System.arraycopy(challenge, 0, plaintext, 8, 8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding") // PKCS5 e PKCS7 são o mesmo padding p/ AES no JCE
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plaintext).copyOfRange(0, 8)
    }
}

/** CRC-16/XMODEM (poly 0x1021, init 0x0000, sem reflexão, sem XOR final) — usado em todo pacote G5/G6. */
object DexcomCrc {
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** Anexa o CRC16 (little-endian) ao fim de [payload], devolvendo um novo array. */
    fun append(payload: ByteArray): ByteArray {
        val crc = crc16(payload)
        return payload + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    /** Confere se os 2 últimos bytes de [packet] são o CRC16 correto do restante. */
    fun isValid(packet: ByteArray): Boolean {
        if (packet.size < 3) return false
        val expected = crc16(packet, 0, packet.size - 2)
        val actual = ((packet[packet.size - 1].toInt() and 0xFF) shl 8) or (packet[packet.size - 2].toInt() and 0xFF)
        return expected == actual
    }
}

/** Mensagens que o APP envia ao transmissor G6 — cada uma já com o CRC16 embutido. */
object DexcomG6Tx {
    private const val OPCODE_AUTH_REQUEST = 0x01
    private const val OPCODE_AUTH_CHALLENGE = 0x04
    private const val OPCODE_ASK_GLUCOSE = 0x4E // pedido de leitura, fiel ao Juggluco (mesmo opcode no G7)

    /** [singleUseToken] deve ter 8 bytes (nonce aleatório). */
    fun authRequest(singleUseToken: ByteArray, slot: Int = 0x02): ByteArray {
        require(singleUseToken.size == 8) { "singleUseToken deve ter 8 bytes" }
        return byteArrayOf(OPCODE_AUTH_REQUEST.toByte(), *singleUseToken.toTypedArray().toByteArray(), slot.toByte())
    }

    fun authChallengeResponse(challengeHash: ByteArray): ByteArray {
        require(challengeHash.size == 8) { "challengeHash deve ter 8 bytes" }
        return byteArrayOf(OPCODE_AUTH_CHALLENGE.toByte()) + challengeHash
    }

    fun askGlucose(): ByteArray = byteArrayOf(OPCODE_ASK_GLUCOSE.toByte())
}

sealed class AuthChallengeResult {
    data class Parsed(val tokenHash: ByteArray, val challenge: ByteArray) : AuthChallengeResult()
    object Invalid : AuthChallengeResult()
}

data class AuthStatus(val authenticated: Boolean, val bonded: Boolean)

/** `TransmitterTimeRxMessage` do xDrip — usado pra calcular o início real da sessão do sensor. */
data class TransmitterTime(val currentTimeSec: Int, val sessionStartTimeSec: Int) {
    val sessionInProgress: Boolean get() = sessionStartTimeSec != -1 && currentTimeSec != sessionStartTimeSec
}

/** `GlucoseRxMessage`/`EGlucoseRxMessage` do xDrip (opcode 0x31/0x4F) — layout idêntico salvo o campo extra de predição. */
data class DexcomGlucose(
    val sequence: Int,
    val timestampSec: Int,
    val mgdl: Int,
    val displayOnly: Boolean,
    val state: Int,
    val trend: Int,          // signed; unidade real ainda a confirmar com hardware
    val predictedMgdl: Int?  // só presente no opcode 0x4F (EGlucoseRx)
)

/** Parsers dos pacotes recebidos do transmissor G6 — todos exigem CRC16 válido (ver [DexcomCrc]). */
object DexcomG6Rx {
    private const val OPCODE_AUTH_CHALLENGE = 0x03
    private const val OPCODE_AUTH_STATUS = 0x05
    private const val OPCODE_TRANSMITTER_TIME = 0x25
    private const val OPCODE_GLUCOSE = 0x31
    private const val OPCODE_EGLUCOSE = 0x4F

    fun parseAuthChallenge(packet: ByteArray): AuthChallengeResult {
        if (packet.size < 17 || (packet[0].toInt() and 0xFF) != OPCODE_AUTH_CHALLENGE) return AuthChallengeResult.Invalid
        return AuthChallengeResult.Parsed(
            tokenHash = packet.copyOfRange(1, 9),
            challenge = packet.copyOfRange(9, 17)
        )
    }

    /** AuthStatusRxMessage NÃO leva CRC (só 3 bytes: opcode+authenticated+bonded), fiel ao xDrip. */
    fun parseAuthStatus(packet: ByteArray): AuthStatus? {
        if (packet.size < 3 || (packet[0].toInt() and 0xFF) != OPCODE_AUTH_STATUS) return null
        return AuthStatus(authenticated = packet[1].toInt() == 1, bonded = packet[2].toInt() == 1)
    }

    fun parseTransmitterTime(packet: ByteArray): TransmitterTime? {
        if (packet.size < 10 || (packet[0].toInt() and 0xFF) != OPCODE_TRANSMITTER_TIME || !DexcomCrc.isValid(packet)) return null
        return TransmitterTime(
            currentTimeSec = readS32LE(packet, 2),
            sessionStartTimeSec = readS32LE(packet, 6)
        )
    }

    fun parseGlucose(packet: ByteArray): DexcomGlucose? {
        if (packet.size < 15) return null
        val opcode = packet[0].toInt() and 0xFF
        if (opcode != OPCODE_GLUCOSE && opcode != OPCODE_EGLUCOSE) return null
        if (!DexcomCrc.isValid(packet)) return null

        val sequence = readS32LE(packet, 2)
        val timestamp = readS32LE(packet, 6)
        val glucoseWord = readU16LE(packet, 10)
        val displayOnly = (glucoseWord and 0xF000) != 0
        val mgdl = glucoseWord and 0x0FFF
        val state = packet[12].toInt()
        val trend = packet[13].toInt() // signed
        val predicted = if (opcode == OPCODE_EGLUCOSE && packet.size >= 17)
            readU16LE(packet, 14) and 0x03FF else null

        return DexcomGlucose(sequence, timestamp, mgdl, displayOnly, state, trend, predicted)
    }

    private fun readU16LE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)

    private fun readS32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

/**
 * G7 (Juggluco `dexcom/java.cpp::glucoseinput`) — formato do registro de glicemia JÁ DECIFRADO
 * (19 bytes). Reaproveitável quando o handshake EC-JPAKE for portado; o pacote CRU que chega por
 * BLE ainda está cifrado e não é parseável direto por isto.
 */
data class DexcomG7Glucose(
    val type: Int,
    val status: Int,
    val secsSinceStart: Int,
    val sequence: Int,
    val ageSec: Int,
    val mgdl: Int,
    val state: Int,
    val trend: Int,          // signed, ÷10.0 = taxa mg/dL por minuto
    val predictedMgdl: Int
)

object DexcomG7Parser {
    private const val SIZE = 19

    /** [plaintext] já deve estar decifrado (ver header da classe — a decifra ainda não está portada). */
    fun parse(plaintext: ByteArray): DexcomG7Glucose? {
        if (plaintext.size < SIZE) return null
        val type = plaintext[0].toInt() and 0xFF
        val status = plaintext[1].toInt()
        val secsSinceStart = readS32LE(plaintext, 2)
        val sequence = readU16LE(plaintext, 6)
        val age = readU16LE(plaintext, 10)
        val mgdlWord = readU16LE(plaintext, 12)
        val mgdl = mgdlWord and 0x0FFF
        val state = plaintext[14].toInt()
        val trend = plaintext[15].toInt()
        val predictedWord = readU16LE(plaintext, 16)
        val predicted = predictedWord and 0x03FF
        return DexcomG7Glucose(type, status, secsSinceStart, sequence, age, mgdl, state, trend, predicted)
    }

    private fun readU16LE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)

    private fun readS32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
