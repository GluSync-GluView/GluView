package com.gluview.app.data.libre2

// ── Hex helpers (top-level: acessíveis em todo o pacote data.libre2 sem import) ──
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun hexToBytes(hex: String): ByteArray {
    val clean = hex.trim().removePrefix("0x").filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "Hex de comprimento ímpar: '${hex.take(16)}…'" }
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}

/**
 * Criptografia e codificação do FreeStyle Libre 2 / 2 Plus — reimplementação CLEAN-ROOM em Kotlin.
 *
 * IMPORTANTE (licença): este arquivo NÃO copia código de xDrip/Juggluco/DiaBLE (GPL). Ele reimplementa,
 * a partir do entendimento público do protocolo, apenas as transformações necessárias para
 * interoperabilidade com o dado de glicemia do próprio usuário.
 *
 * Componentes:
 *   - [crc16]                  : CRC dos blocos da FRAM (padrão Libre, determinístico e testável).
 *   - [decodeSerial]           : UID+patchInfo → número de série impresso no sensor.
 *   - [streamingUnlockPayload] : payload enviado por NFC/BLE para habilitar o streaming.
 *   - [decryptBleValue]        : descriptografa o bloco recebido na notificação BLE.
 *
 * SEAM (a validar com sensor real): os passos criptográficos exatos do Libre 2 dependem de
 * constantes/ordem de bytes que só podem ser confirmadas capturando pacotes de um sensor. Os pontos
 * incertos estão marcados com TODO(libre2-hw) e são totalmente logados em runtime para permitir a
 * validação quando o hardware estiver disponível. As partes determinísticas (CRC, decode de série,
 * montagem de bytes) estão completas e cobertas por testes.
 */
object Libre2Crypto {

    // ── CRC16 (blocos da FRAM) ──────────────────────────────────────────────────
    /**
     * CRC16 usado pelo Libre para validar cada bloco de dados da FRAM. Polinômio reverso 0x8408
     * (CCITT refletido), com bytes processados do índice [start] por [len] bytes. Determinístico —
     * usado para conferir a integridade da FRAM lida por NFC.
     */
    fun crc16(data: ByteArray, start: Int, len: Int): Int {
        var crc = 0xffff
        for (i in start until start + len) {
            crc = crc xor (data[i].toInt() and 0xff)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
            }
        }
        // O Libre armazena o CRC com os nibbles/bytes invertidos; devolvemos o valor "cru" e a
        // comparação é feita por quem lê a FRAM (mantém esta função pura).
        return crc and 0xffff
    }

    // ── Número de série impresso ────────────────────────────────────────────────
    private const val SERIAL_ALPHABET = "0123456789ACDEFGHJKLMNPQRTUVWXYZ"

    /**
     * Deriva o número de série impresso no sensor a partir do UID (8 bytes) e do patchInfo.
     * Empacotamento base-32 de 5 bits por caractere — algoritmo determinístico e público.
     * O prefixo depende da família do sensor (byte 0 do patchInfo).
     *
     * TODO(libre2-hw): confirmar o prefixo do Libre 2 Plus contra um sensor real (o mapa abaixo
     * cobre as famílias conhecidas; um valor desconhecido cai no prefixo genérico "0").
     */
    fun decodeSerial(uid: ByteArray, patchInfo: ByteArray): String {
        if (uid.size < 8) return ""
        val prefix = when (patchInfo.getOrNull(0)?.toInt()?.and(0xff)) {
            0x9d, 0xe5, 0xe6 -> "3"   // famílias Libre 2 / 2 Plus conhecidas
            0x70             -> "1"   // Libre 1 (referência)
            else             -> "0"
        }
        // Bytes relevantes: UID sem o código de fabricante (2 primeiros), em ordem invertida.
        val body = uid.copyOfRange(2, 8).reversedArray()
        val bits = StringBuilder()
        for (b in body) {
            bits.append((b.toInt() and 0xff).toString(2).padStart(8, '0'))
        }
        val sb = StringBuilder(prefix)
        var i = 0
        while (i + 5 <= bits.length) {
            val idx = bits.substring(i, i + 5).toInt(2)
            sb.append(SERIAL_ALPHABET[idx])
            i += 5
        }
        return sb.toString()
    }

    // ── Streaming unlock ────────────────────────────────────────────────────────
    /**
     * Monta o payload que habilita/renova o streaming BLE. Deriva de patchInfo + UID + um código de
     * unlock fixo e um contador que incrementa a cada sessão. A montagem de bytes (comprimento,
     * ordem, contador) está completa; o embaralhamento final é o seam a validar.
     *
     * @param unlockCode código de unlock do streaming (constante do protocolo).
     * @param unlockCount contador incremental persistido entre sessões.
     */
    fun streamingUnlockPayload(
        uid: ByteArray,
        patchInfo: ByteArray,
        unlockCode: Int,
        unlockCount: Int
    ): ByteArray {
        val out = ByteArray(4)
        // Layout observado: [code low, code high, count low, count high] combinados com o patchInfo.
        out[0] = (unlockCode and 0xff).toByte()
        out[1] = ((unlockCode ushr 8) and 0xff).toByte()
        out[2] = (unlockCount and 0xff).toByte()
        out[3] = ((unlockCount ushr 8) and 0xff).toByte()
        // TODO(libre2-hw): aplicar o embaralhamento final (keystream derivado de uid+patchInfo).
        // Sem um sensor para validar, devolvemos o payload cru — o keystream entra aqui.
        return keystreamXor(out, uid, patchInfo, unlockCount)
    }

    /**
     * Descriptografa o valor recebido numa notificação BLE. O keystream é derivado do UID, do
     * patchInfo e do contador; aplicamos XOR posição a posição. Estrutura completa; a derivação
     * exata do keystream é o seam a validar com hardware.
     */
    fun decryptBleValue(uid: ByteArray, patchInfo: ByteArray, counter: Int, data: ByteArray): ByteArray =
        keystreamXor(data, uid, patchInfo, counter)

    /**
     * Keystream determinístico (clean-room) usado tanto no unlock quanto no decrypt. Combina UID,
     * patchInfo e contador num gerador simples e reversível (XOR). NÃO é o keystream final do Libre
     * — é um placeholder estrutural, reversível e testável, a ser substituído pela derivação real
     * validada com sensor.
     *
     * TODO(libre2-hw): substituir por AES/derivação real do Libre 2 após captura de pacotes.
     */
    private fun keystreamXor(data: ByteArray, uid: ByteArray, patchInfo: ByteArray, counter: Int): ByteArray {
        val out = ByteArray(data.size)
        var seed = counter
        for (b in uid) seed = (seed * 31 + (b.toInt() and 0xff)) and 0x7fffffff
        for (b in patchInfo) seed = (seed * 31 + (b.toInt() and 0xff)) and 0x7fffffff
        var state = seed
        for (i in data.indices) {
            // LCG simples só para gerar um keystream reversível determinístico.
            state = (state * 1103515245 + 12345) and 0x7fffffff
            out[i] = (data[i].toInt() xor ((state ushr 16) and 0xff)).toByte()
        }
        return out
    }
}
