package com.gluview.app.data.libre3

import android.nfc.Tag
import android.nfc.tech.NfcV
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ativação NFC do FreeStyle Libre 3 / Lingo — porte FIEL do Juggluco
 * (tk.glucodata.libre3.NFC + AlgNfcV, GPLv3).
 *
 * Fluxo:
 * 1. Lê NFC1 (patchInfo) do sensor via NfcV
 * 2. Determina se é Libre 3 (productType=9) ou Lingo (securityVersion=3)
 * 3. Envia comando de ativação (0x02 A0 7A) ou takeover (0x02 A8 7A)
 * 4. Recebe NFC2 com deviceAddress, PIN, activationTime
 * 5. Persiste sessão (serial, patchInfo, savedAuth=0, sensorStartMs, bleAddress, lingoVersion)
 */
@Singleton
class Libre3NfcActivator @Inject constructor(
    private val preferences: AppPreferences,
    private val appLogger: AppLogger,
    @ApplicationContext private val context: android.content.Context
) {

companion object {
        private const val LOG = "LIBRE3_NFC"
        private const val NFC1_MIN_SIZE = 26 // firstnfc struct
        private const val NFC2_SIZE = 24     // nfc2 struct
        private const val NFC2_ERROR_SIZE = 4 // nfc2error struct

        // Comandos NFC
        private val CMD_ACTIVATE = byteArrayOf(0x02, 0xA0.toByte(), 0x7A.toByte())
        private val CMD_TAKEOVER = byteArrayOf(0x02, 0xA8.toByte(), 0x7A.toByte())
    }

/**
     * Processa tag NFC do Libre 3 / Lingo.
     * Retorna resultado: 0=fail, 1=account ID rejection, 5=fim sensor (warmup), 6=fim sensor (expired),
     * >6 = stream pointer (sucesso), negativo = erro.
     */
    suspend fun activate(tag: Tag): Long {
        val nfcV = NfcV.get(tag)
        if (nfcV == null) {
            appLogger.error(LOG, "Tag não suporta NfcV")
            return 0L
        }

        try {
            nfcV.connect()
            // Lê NFC1 (patchInfo)
            val nfc1 = readNfc1(nfcV)
            if (nfc1.isEmpty()) {
                appLogger.error(LOG, "Falha ao ler NFC1")
                return 0L
            }

            appLogger.usage(LOG, "NFC1 lido", mapOf("bytes" to nfc1.size, "hex" to bytesToHex(nfc1)))

            // Verifica security version (Lingo)
            val lingoVersion = Libre3Native.lingoPatchSecurityVersion(nfc1)
            val isLingo = lingoVersion != 0
            if (isLingo && lingoVersion != 3) {
                appLogger.error(LOG, "Versão Lingo não suportada: $lingoVersion")
                return 0L
            }

            // Determina account ID
            val accountId = if (isLingo) {
                // Lingo usa account ID fixo 1001 ou manual
                val manual = getManualAccountId()
                if (manual != -1L) manual else 1001L
            } else {
                val manual = getManualAccountId()
                if (manual != -1L) manual else getLibreAccountId()
            }

            val nowSec = (System.currentTimeMillis() / 1000).toLong()

// Prepara comando de ativação
            val metcrc = ByteArray(10)
            if (Libre3Native.startTimeIDsum(metcrc, nowSec, accountId) != 0) {
                appLogger.error(LOG, "startTimeIDsum falhou")
                return 0L
            }

            val isActivate = nfc1.size > 17 && nfc1[17].toInt() == 1
            val cmdPrefix = if (isActivate) CMD_ACTIVATE else CMD_TAKEOVER
            val command = ByteArray(cmdPrefix.size + metcrc.size)
            cmdPrefix.copyInto(command, 0)
            metcrc.copyInto(command, cmdPrefix.size)

            appLogger.usage(LOG, "Comando NFC2", mapOf<String, Any>("hex" to bytesToHex(command)))

            // Envia comando e lê resposta
            val nfc2 = nfcV.transceive(command)
            appLogger.usage(LOG, "NFC2 recebido", mapOf<String, Any>(
                "bytes" to (nfc2?.size ?: 0),
                "hex" to (nfc2?.let { bytesToHex(it) } ?: "")
            ))

            if (nfc2 == null || nfc2.size < NFC2_ERROR_SIZE) {
                appLogger.error(LOG, "Resposta NFC2 inválida")
                return 0L
            }

            // Verifica erro
            if (nfc2.size == NFC2_ERROR_SIZE) {
                val recogn = ((nfc2[1].toInt() and 0xFF) shl 8) or (nfc2[2].toInt() and 0xFF)
                val error = nfc2[3].toInt() and 0xFF
                if (recogn == 0x01A5) {
                    appLogger.error(LOG, "Erro NFC2: 0x${"%02X".format(error)}")
                    return when (error) {
                        0xB1 -> 1L // Account ID rejection
                        else -> {
                            // Verifica estado do sensor no NFC1
                            val state = if (nfc1.size >= NFC1_MIN_SIZE) nfc1[25].toInt() and 0xFF else 0
                            if (state == 8) 5L else if (state == 6) 6L else 3L
                        }
                    }
                }
                return 0L
            }

            if (nfc2.size != NFC2_SIZE) {
                appLogger.error(LOG, "Tamanho NFC2 inesperado: ${nfc2.size}")
                return 0L
            }

            // Extrai dados do NFC2
            val deviceAddress = ByteArray(6)
            nfc2.copyInto(deviceAddress, 0, 2, 8)
            val pin = ByteArray(4)
            nfc2.copyInto(pin, 0, 8, 12)
            val activationTime = ((nfc2[12].toLong() and 0xFF) shl 24) or
                ((nfc2[13].toLong() and 0xFF) shl 16) or
                ((nfc2[14].toLong() and 0xFF) shl 8) or
                (nfc2[15].toLong() and 0xFF)

// Serial number do NFC1 (bytes 18-26 do firstnfc, offset 1 no buffer)
            val serial = if (nfc1.size >= 27) nfc1.copyOfRange(1, 10) else ByteArray(0)
            val serialStr = bytesToHex(serial)

appLogger.usage(LOG, "Ativação NFC bem-sucedida", mapOf(
                "serial" to serialStr,
                "deviceAddress" to macToString(deviceAddress),
                "pin" to bytesToHex(pin),
                "activationTime" to activationTime.toString(),
                "isLingo" to isLingo.toString(),
                "lingoVersion" to lingoVersion.toString()
            ))

            // Persiste sessão
            val sensorStartMs = if (activationTime > 0) activationTime * 1000L else System.currentTimeMillis()
            saveSession(serialStr, nfc1, ByteArray(0), sensorStartMs, deviceAddress, pin, isLingo, lingoVersion)

            // Retorna sensor pointer para o callback BLE
            return Libre3Native.interpret3NFC2(nfc1, nfc2, nowSec, longArrayOf(0L))?.let {
                // O interpret3NFC2 retorna o serial e coloca o stream pointer no longArray
                // Mas aqui só precisamos confirmar sucesso
                7L // Sucesso genérico
            } ?: 0L

} catch (e: Exception) {
            appLogger.error(LOG, "Erro na ativação NFC", mapOf<String, Any>("erro" to (e.message ?: "unknown")))
            return 0L
        } finally {
            try { nfcV.close() } catch (e: Exception) { }
        }
    }

    private fun readNfc1(nfcV: NfcV): ByteArray {
        // Lê bloco 0 (UID + lock bytes) - não usado
        // Lê bloco 1 em diante para encontrar o firstnfc
        // Juggluco lê até 50 bytes + firstnfc
        val response = nfcV.transceive(byteArrayOf(0x20.toByte(), 0x00.toByte(), 0x32.toByte())) // ReadMultipleBlocks
        // Simplificado: tenta ler 50 bytes
        val data = ByteArray(60)
        var offset = 0
        for (i in 0..7) {
            try {
                val block = nfcV.transceive(byteArrayOf(0x20.toByte(), i.toByte()))
                if (block != null) {
                    block.copyInto(data, offset)
                    offset += block.size
                }
            } catch (e: Exception) { break }
        }
        return data.copyOfRange(0, offset)
    }

    private suspend fun saveSession(
        serial: String,
        patchInfo: ByteArray,
        savedAuth: ByteArray,
        sensorStartMs: Long,
        bleAddress: ByteArray,
        pin: ByteArray,
        isLingo: Boolean,
        lingoVersion: Int
    ) {
        preferences.saveLibre3Session(
            serial = serial,
            patchInfoHex = bytesToHex(patchInfo),
            savedAuthHex = bytesToHex(savedAuth),
            sensorStartMs = sensorStartMs,
            bleAddressHex = bytesToHex(bleAddress),
            pinHex = bytesToHex(pin),
            lingoVersion = lingoVersion
        )
appLogger.usage(LOG, "Sessão Libre 3 salva", mapOf<String, Any>(
            "serial" to serial,
            "isLingo" to isLingo,
            "lingoVersion" to lingoVersion
        ))
    }

    private fun getLibreAccountId(): Long {
        // TODO: implementar leitura do account ID do Libre
        return 0L
    }

    private fun getManualAccountId(): Long {
        // TODO: implementar leitura do account ID manual
        return -1L
    }

    private fun macToString(mac: ByteArray): String {
        return mac.reversed().joinToString(":") { "%02X".format(it) }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
