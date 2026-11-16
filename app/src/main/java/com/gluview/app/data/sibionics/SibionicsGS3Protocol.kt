package com.gluview.app.data.sibionics

import com.gluview.app.util.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Protocolo Sibionics GS3 (Fase F6.3) com suporte automático a V2 (RC4) e V3 (AES-OFB).
 *
 * Referências no Juggluco 11.5.7:
 * - Common/src/main/cpp/sibionics3/v3protocol.hpp:12-144 (cripto AES-OFB, IV, checksum, frames, parsing)
 * - Common/src/main/cpp/sibionics3/v3session.hpp:18-186 (máquina de estados, relógio âncora, histórico)
 * - Common/src/main/cpp/sibionics3/interpretgs3.cpp:764-797 (decisão V2 x V3 e chave RC4)
 * - Common/src/main/cpp/sibionics3/java.cpp:189-199 (identificador de conta 12B)
 *
 * Regra de segurança A.7 / F6.3:
 * - Maturidade "Sem teste com hardware real".
 * - Leitura só é promovida após relógio âncora e histórico completos (como no v3session).
 */
class SibionicsGS3Protocol(
    private val mac: String,
    private val accountIdBytes: ByteArray = DEFAULT_ACCOUNT_ID,
    private val initialSavedIndex: Int = -1,
    private val initialSavedTimeSecs: Long = 0L,
    private val logger: AppLogger? = null
) : SibionicsProtocol {

    companion object {
        private const val LOG = "SIBIONICS_GS3"
        private const val MMOL_TO_MGDL = 18.0182

        // Juggluco v3protocol.hpp:18-20: chave de transporte AES-128
        val TRANSPORT_KEY = byteArrayOf(
            0x01, 0x38, 0x0b, 0x9a.toByte(), 0x00, 0x5b, 0x02, 0x5d,
            0xcd.toByte(), 0x9e.toByte(), 0xc3.toByte(), 0x99.toByte(), 0x09, 0x37, 0xaa.toByte(), 0xe8.toByte()
        )

        // Juggluco v3protocol.hpp:21-23: chave de máscara do algoritmo
        val ALGORITHM_KEY = byteArrayOf(
            0x11, 0xb8.toByte(), 0xab.toByte(), 0x00, 0x01, 0x4b, 0x2a, 0x5f,
            0xcb.toByte(), 0x91.toByte(), 0xf3.toByte(), 0xaa.toByte(), 0x99.toByte(), 0x27, 0xbf.toByte(), 0xe0.toByte()
        )

        // Juggluco v3protocol.hpp:25-26: registro global padrão
        val GLOBAL_REGISTRATION = "THE544U0TYITE461".toByteArray(Charsets.US_ASCII)

        // Juggluco interpret_data.cpp:47 / interpretgs3.cpp:50: chave RC4 V2 (mesmos 16 bytes)
        val RC4_KEY = TRANSPORT_KEY

        val DEFAULT_ACCOUNT_ID = ByteArray(12)

        // Sequência de consultas V3: v3session.hpp:36
        val QUERIES = byteArrayOf(0x0f, 0x0e, 0x0f, 0x03, 0x11, 0x01)

        /**
         * Deriva o IV de 16 bytes a partir do endereço BLE (display order, ex. F6:09:FF:E2:20:B2).
         * Inverte a ordem dos 6 bytes no início do buffer e preenche o restante com zero (v3protocol.hpp:28-32).
         */
        fun makeIv(address6: ByteArray): ByteArray {
            require(address6.size == 6) { "Endereço BLE deve ter 6 bytes" }
            val iv = ByteArray(16)
            for (i in 0 until 6) {
                iv[i] = address6[5 - i]
            }
            return iv
        }

        fun parseMacToAddress(macStr: String): ByteArray {
            val clean = macStr.replace(":", "").replace("-", "").trim()
            require(clean.length == 12) { "MAC inválido: $macStr" }
            val out = ByteArray(6)
            for (i in 0 until 6) {
                out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }

        /**
         * Calcula o checksum complementar de 1 byte de modo que sum(p) & 0xFF == 0 (v3protocol.hpp:40-44).
         */
        fun computeChecksum(p: ByteArray) {
            var sum = 0
            for (i in 0 until p.size - 1) {
                sum += (p[i].toInt() and 0xFF)
            }
            p[p.size - 1] = (-sum).toByte()
        }

        /**
         * Valida se o frame cumpre comprimento [4..250], tamanho p[0]+1 == p.size e soma zero (v3protocol.hpp:45-50).
         */
        fun isValidFrame(p: ByteArray): Boolean {
            if (p.size < 4 || p.size > 250) return false
            val lenByte = p[0].toInt() and 0xFF
            if (lenByte + 1 != p.size) return false
            var sum = 0
            for (b in p) {
                sum += (b.toInt() and 0xFF)
            }
            return (sum and 0xFF) == 0
        }

        /**
         * Cifra/decifra OFB usando AES-128 ECB sem padding, reiniciando o IV a cada pacote (v3protocol.hpp:51-66).
         */
        fun ofbCrypt(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)

            var feedback = iv.copyOf(16)
            val output = ByteArray(input.size)

            var offset = 0
            while (offset < input.size) {
                feedback = cipher.doFinal(feedback)
                val n = min(16, input.size - offset)
                for (i in 0 until n) {
                    output[offset + i] = (input[offset + i].toInt() xor feedback[i].toInt()).toByte()
                }
                offset += 16
            }
            return output
        }

        /**
         * Cifra/decifra RC4 (KSA + PRGA) para V2 (interpretgs3.cpp:420, 773).
         */
        fun rc4Crypt(key: ByteArray, input: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            }
            var i = 0
            j = 0
            val output = ByteArray(input.size)
            for (k in input.indices) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val tmp = s[i]; s[i] = s[j]; s[j] = tmp
                val t = (s[i] + s[j]) and 0xFF
                output[k] = (input[k].toInt() xor s[t]).toByte()
            }
            return output
        }

        /**
         * Constrói o pacote de autenticação V3 descriptografado (38 bytes) (v3protocol.hpp:79-87).
         */
        fun buildAuthPlain(address6: ByteArray, account: ByteArray, registration: ByteArray = GLOBAL_REGISTRATION): ByteArray {
            val p = ByteArray(38)
            p[0] = 0x25
            p[1] = 0xe2.toByte()
            p[2] = 0x00
            for (i in 0 until 6) {
                p[3 + i] = address6[5 - i]
            }
            System.arraycopy(registration, 0, p, 9, min(16, registration.size))
            System.arraycopy(account, 0, p, 25, min(12, account.size))
            computeChecksum(p)
            return p
        }

        /**
         * Constrói o pacote de consulta V3 (4 bytes) (v3protocol.hpp:88-90).
         */
        fun buildQueryPlain(subcommand: Byte): ByteArray {
            val p = byteArrayOf(0x03, 0xf0.toByte(), subcommand, 0x00)
            computeChecksum(p)
            return p
        }

        /**
         * Constrói a solicitação de histórico V3 (7 bytes) (v3protocol.hpp:91-95).
         */
        fun buildHistoryPlain(firstIndex: Int, lastIndex: Int = 0xFFFF): ByteArray {
            val p = ByteArray(7)
            p[0] = 0x06
            p[1] = 0x39
            p[2] = (firstIndex and 0xFF).toByte()
            p[3] = ((firstIndex shr 8) and 0xFF).toByte()
            p[4] = (lastIndex and 0xFF).toByte()
            p[5] = ((lastIndex shr 8) and 0xFF).toByte()
            p[6] = 0x00
            computeChecksum(p)
            return p
        }

        /**
         * Converte id de conta (Long ou String) para array de 12 bytes conforme Juggluco java.cpp:189-192.
         */
        fun accountIdFromLong(id: Long): ByteArray {
            val out = ByteArray(12)
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            buffer.putLong(id)
            System.arraycopy(buffer.array(), 0, out, 0, 8)
            // bytes 8..11 permanecem 0
            return out
        }
    }

    data class Gs3Record(
        val index: Int,
        val remaining: Int,
        val temperatureWire: Int,
        val dumpWire: Int,
        val currentWire: Int,
        val displayGlucose: Int,
        val algorithmGlucoseWire: Int,
        val algorithmGlucoseDecoded: Int,
        val trend: Int,
        val presentCstate: Int,
        val algorithmCstate: Int,
        val tstate: Int,
        val dstate: Int,
        val algorithmReserved: Int,
        val ceVoltageWire: Int,
        val reVoltageWire: Int
    )

    data class DeviceTime(
        val startoverTime: Int,
        val activationTime: Long,
        val currentTime: Long,
        val lastTime: Long,
        val lastIndex: Int
    )

    private val addressBytes: ByteArray = parseMacToAddress(mac)
    private val iv: ByteArray = makeIv(addressBytes)

    // Estado da sessão (v3session.hpp:18-35)
    var isV3: Boolean = false
        private set
    private var attempted: Boolean = false
    private var authenticated: Boolean = false
    private var historyDone: Boolean = false
    private var queryPosition: Int = 0
    private var anchorSecs: Long = 0L
    private var liveTimeSecs: Long = 0L
    private var liveIndex: Int = 0
    private var historyRequestedAtSecs: Long = 0L
    private var savedIndex: Int = if (initialSavedIndex >= 0) initialSavedIndex else 0
    private var cachedIndex: Int = 0
    private var deviceTime: DeviceTime? = null

    // Registros pendentes ordenados por índice
    private val pendingRecords = TreeMap<Int, Gs3Record>()

    init {
        if (initialSavedIndex >= 0 && initialSavedTimeSecs > initialSavedIndex * 60L) {
            anchorSecs = initialSavedTimeSecs - (initialSavedIndex * 60L)
            logger?.usage(LOG, "Horário âncora restaurado", mapOf("anchorSecs" to anchorSecs, "savedIndex" to savedIndex))
        }
    }

    override val lastProcessedIndex: Int
        get() = savedIndex

    override fun onReady(sendCommand: (ByteArray) -> Unit) {
        logger?.usage(LOG, "GS3 onReady — iniciando autenticação/handshake", mapOf("mac" to mac))
        // Envia pacote inicial de autenticação V3
        attempted = true
        isV3 = true
        val authPlain = buildAuthPlain(addressBytes, accountIdBytes)
        val authWire = ofbCrypt(TRANSPORT_KEY, iv, authPlain)
        sendCommand(authWire)
    }

    override fun onPacket(raw: ByteArray, sendCommand: (ByteArray) -> Unit): List<SibionicsProtocol.Reading> {
        if (raw.size < 4 || raw.size > 250) {
            logger?.error(LOG, "Pacote GS3 com tamanho inválido", mapOf("size" to raw.size))
            return emptyList()
        }

        val nowSecs = System.currentTimeMillis() / 1000L

        // Lógica de seleção V2 x V3 (interpretgs3.cpp:771-785)
        if (!isV3) {
            val legacy = rc4Crypt(RC4_KEY, raw)
            val isBootstrap = (raw.size == 5 && raw[0] == 4.toByte() && raw[1] == 0.toByte() &&
                    raw[2] == 0.toByte() && raw[3] == 0.toByte() && raw[4] == 0xFC.toByte())
            if (isValidFrame(legacy) || isBootstrap) {
                // Confirmado V2
                logger?.usage(LOG, "GS3 pacote V2 detectado")
                return processV2Packet(if (isBootstrap) raw else legacy, sendCommand, nowSecs)
            }

            // Teste de fallback: descriptografia V3
            val plain = ofbCrypt(TRANSPORT_KEY, iv, raw)
            if (isValidFrame(plain) && plain.size == 5 && plain[4] == 0xFC.toByte()) {
                isV3 = true
                attempted = true
                logger?.usage(LOG, "GS3 V3 detectado via bootstrap; autenticando")
                val authPlain = buildAuthPlain(addressBytes, accountIdBytes)
                sendCommand(ofbCrypt(TRANSPORT_KEY, iv, authPlain))
                return emptyList()
            }
            logger?.error(LOG, "GS3 pacote não reconhecido nem em V2 nem em V3")
            return emptyList()
        }

        // Caminho V3 (v3session.hpp)
        val plain = ofbCrypt(TRANSPORT_KEY, iv, raw)
        if (!isValidFrame(plain)) {
            logger?.error(LOG, "GS3 V3 falha de checksum/comprimento", mapOf("rawSize" to raw.size))
            return emptyList()
        }

        return processV3Plain(plain, sendCommand, nowSecs)
    }

    private fun processV3Plain(plain: ByteArray, sendCommand: (ByteArray) -> Unit, nowSecs: Long): List<SibionicsProtocol.Reading> {
        val cmd = plain[1].toInt() and 0xFF

        // Autenticação (cmd 0xe2)
        if (plain.size == 5 && cmd == 0xe2) {
            val status = plain[2].toInt() and 0xFF
            val error = plain[3].toInt() and 0xFF
            if (status != 1 || error != 0) {
                logger?.error(LOG, "GS3 V3 autenticação falhou", mapOf("status" to status, "error" to error))
                return emptyList()
            }
            if (!authenticated) {
                authenticated = true
                logger?.usage(LOG, "GS3 V3 autenticado com sucesso")
                queryPosition = 0
                sendNextQuery(sendCommand, nowSecs)
            }
            return emptyList()
        }

        if (!authenticated) {
            logger?.error(LOG, "GS3 V3 pacote recebido antes da autenticação concluída")
            return emptyList()
        }

        // Respostas de informação (cmd 0xf0)
        if (cmd == 0xf0) {
            val sub = plain[2].toInt() and 0xFF
            if (queryPosition < QUERIES.size && sub == (QUERIES[queryPosition].toInt() and 0xFF)) {
                if (sub == 0x03) {
                    val dt = parseDeviceTime(plain)
                    deviceTime = dt
                    logger?.usage(LOG, "GS3 V3 DeviceTime", mapOf("activation" to (dt?.activationTime ?: 0)))
                }
                queryPosition++
                sendNextQuery(sendCommand, nowSecs)
            }
            return emptyList()
        }

        // Pacotes de dados de glicemia: 0x32, 0x36, 0x39
        if (cmd == 0x32 || cmd == 0x36 || cmd == 0x39) {
            if (plain.size == 5) {
                val status = plain[2].toInt() and 0xFF
                val error = plain[3].toInt() and 0xFF
                if (status != 1 || error != 0) {
                    logger?.error(LOG, "GS3 V3 requisição de dados falhou", mapOf("status" to status, "error" to error))
                }
                return emptyList()
            }

            // Fim do histórico (0x39 com 0 registros)
            if (cmd == 0x39 && plain.size == 8 && plain[2] == 0.toByte()) {
                historyDone = true
                logger?.usage(LOG, "GS3 V3 histórico finalizado (sinal 0x39)")
                return flushPendingReadings()
            }

            val records = parseRecords(plain)
            if (records.isEmpty()) {
                logger?.error(LOG, "GS3 V3 pacote de glicemia malformado")
                return emptyList()
            }

            // Apenas registro ao vivo único (cmd 0x32, 1 registro, remaining == 0) calibra/observa o relógio
            if (cmd == 0x32 && records.size == 1 && records[0].remaining == 0) {
                observeLive(records[0].index, nowSecs)
            }

            for (r in records) {
                if (cmd == 0x36) {
                    cachedIndex = maxOf(cachedIndex, r.index)
                }
                if (r.index > savedIndex && isDisplayPresent(r)) {
                    pendingRecords[r.index] = r
                }
            }

            if (cmd == 0x39 && records.last().remaining == 0) {
                historyDone = true
                logger?.usage(LOG, "GS3 V3 histórico concluído pelos registros")
            }

            return flushPendingReadings()
        }

        return emptyList()
    }

    private fun sendNextQuery(sendCommand: (ByteArray) -> Unit, nowSecs: Long) {
        if (queryPosition < QUERIES.size) {
            val q = QUERIES[queryPosition]
            val queryPlain = buildQueryPlain(q)
            sendCommand(ofbCrypt(TRANSPORT_KEY, iv, queryPlain))
            logger?.usage(LOG, "GS3 V3 consulta enviada", mapOf("query" to (q.toInt() and 0xFF)))
        } else if (cachedIndex > 0 && savedIndex >= cachedIndex) {
            historyDone = true
            logger?.usage(LOG, "GS3 V3 conectado (histórico dispensado)")
        } else {
            historyRequestedAtSecs = nowSecs
            val histPlain = buildHistoryPlain(savedIndex + 1)
            sendCommand(ofbCrypt(TRANSPORT_KEY, iv, histPlain))
            logger?.usage(LOG, "GS3 V3 solicitando histórico", mapOf("fromIndex" to (savedIndex + 1)))
        }
    }

    private fun observeLive(index: Int, nowSecs: Long) {
        val elapsedSecs = index * 60L
        if (nowSecs <= elapsedSecs || (liveTimeSecs > 0 && index <= liveIndex)) return
        val candidate = nowSecs - elapsedSecs
        if (anchorSecs == 0L) {
            anchorSecs = candidate
        }

        // Validação de intervalo de 60s com tolerância de 15s (v3session.hpp:50-58)
        val tolerance = 15L
        if (liveTimeSecs > 0 && index == liveIndex + 1) {
            val spacingError = nowSecs - liveTimeSecs - 60L
            if (spacingError in -tolerance..tolerance) {
                val confirmed = minOf(candidate, liveTimeSecs - (liveIndex * 60L))
                val correction = confirmed - anchorSecs
                if (correction > tolerance || correction < -tolerance) {
                    anchorSecs = confirmed
                }
            }
        }
        liveTimeSecs = nowSecs
        liveIndex = index
    }

    private fun isDisplayPresent(r: Gs3Record): Boolean {
        // v3session.hpp:72-76: valores >= 0xff00 são sentinelas e present_cstate == 1 indica dado inválido
        return r.displayGlucose in 1..0xfeff && r.presentCstate == 0
    }

    private fun flushPendingReadings(): List<SibionicsProtocol.Reading> {
        // Regra de segurança v3session.hpp:77-83: só emite leituras com relógio âncora E histórico concluídos
        if (anchorSecs == 0L || !historyDone) {
            return emptyList()
        }

        val emitted = mutableListOf<SibionicsProtocol.Reading>()
        for ((index, rec) in pendingRecords) {
            if (index > savedIndex) {
                val mgdl = rec.displayGlucose
                val mmol = mgdl / MMOL_TO_MGDL
                val reading = SibionicsProtocol.Reading(
                    index = index,
                    mgdl = mgdl,
                    mmol = mmol,
                    trend = rec.trend,
                    rawCurrent = rec.currentWire,
                    batteryMv = rec.ceVoltageWire,
                    confirmed = true
                )
                emitted.add(reading)
                savedIndex = maxOf(savedIndex, index)
            }
        }
        pendingRecords.clear()
        if (emitted.isNotEmpty()) {
            logger?.usage(LOG, "GS3 leituras emitidas", mapOf("count" to emitted.size, "lastIndex" to savedIndex))
        }
        return emitted
    }

    private fun parseDeviceTime(p: ByteArray): DeviceTime? {
        if (!isValidFrame(p) || p.size != 20 || p[1] != 0xf0.toByte() || p[2] != 3.toByte()) return null
        val buf = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        return DeviceTime(
            startoverTime = buf.getShort(3).toInt() and 0xFFFF,
            activationTime = buf.getInt(5).toLong() and 0xFFFFFFFFL,
            currentTime = buf.getInt(9).toLong() and 0xFFFFFFFFL,
            lastTime = buf.getInt(13).toLong() and 0xFFFFFFFFL,
            lastIndex = buf.getShort(17).toInt() and 0xFFFF
        )
    }

    private fun parseRecords(p: ByteArray): List<Gs3Record> {
        val count = p[2].toInt() and 0xFF
        if (count == 0 || p.size != 8 + 16 * count) return emptyList()

        val buf = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val start = buf.getShort(3).toInt() and 0xFFFF
        val tail = buf.getShort(p.size - 3).toInt() and 0xFFFF

        // Máscara do algoritmo: AES-ECB com zero block
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ALGORITHM_KEY, "AES"))
        val mask = cipher.doFinal(ByteArray(16))
        val mask16 = (mask[0].toInt() and 0xFF) or ((mask[1].toInt() and 0xFF) shl 8)

        val list = mutableListOf<Gs3Record>()
        for (i in 0 until count) {
            val r = 5 + 16 * i
            val temp = buf.getShort(r).toInt() and 0xFFFF
            val dump = buf.getShort(r + 2).toInt() and 0xFFFF
            val curr = buf.getShort(r + 4).toInt() and 0xFFFF
            val disp = buf.getShort(r + 6).toInt() and 0xFFFF
            val algoWire = buf.getShort(r + 8).toInt() and 0xFFFF
            val algoDec = algoWire xor mask16
            val b10 = p[r + 10].toInt() and 0xFF
            val b11 = p[r + 11].toInt() and 0xFF
            val ceVolt = buf.getShort(r + 12).toInt() and 0xFFFF
            val reVolt = buf.getShort(r + 14).toInt() and 0xFFFF

            list.add(Gs3Record(
                index = start + i,
                remaining = tail + count - 1 - i,
                temperatureWire = temp,
                dumpWire = dump,
                currentWire = curr,
                displayGlucose = disp,
                algorithmGlucoseWire = algoWire,
                algorithmGlucoseDecoded = algoDec,
                trend = b10 and 7,
                presentCstate = (b10 shr 3) and 1,
                algorithmCstate = b10 shr 4,
                tstate = b11 and 3,
                dstate = (b11 shr 2) and 7,
                algorithmReserved = b11 shr 5,
                ceVoltageWire = ceVolt,
                reVoltageWire = reVolt
            ))
        }
        return list
    }

    private fun processV2Packet(buf: ByteArray, sendCommand: (ByteArray) -> Unit, nowSecs: Long): List<SibionicsProtocol.Reading> {
        val lenByte = buf[0].toInt() and 0xFF
        val cmd = buf[1].toInt() and 0xFF

        // Se for pacote de resposta V2, processa conforme formato legacy
        if (lenByte == 4 && cmd == 0x00) {
            logger?.usage(LOG, "GS3 V2 ACK recebido")
            return emptyList()
        }

        // Registros em V2 (formato similar a subpacotes)
        if (cmd == 0x15 || cmd == 0x88 || cmd == 0x32) {
            // Em V2, extrai glicemia direta se presente
            if (buf.size >= 12) {
                val index = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
                val mgdl = (buf[6].toInt() and 0xFF) or ((buf[7].toInt() and 0xFF) shl 8)
                if (index > savedIndex && mgdl in 20..600) {
                    savedIndex = index
                    return listOf(SibionicsProtocol.Reading(
                        index = index,
                        mgdl = mgdl,
                        mmol = mgdl / MMOL_TO_MGDL,
                        trend = 0,
                        confirmed = true
                    ))
                }
            }
        }
        return emptyList()
    }

    override fun release() {
        pendingRecords.clear()
        logger?.usage(LOG, "GS3 protocolo liberado")
    }
}
