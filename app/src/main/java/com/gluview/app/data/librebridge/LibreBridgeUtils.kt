package com.gluview.app.data.librebridge

import java.util.Locale

/**
 * Utilitários de FRAM/serial/CRC do Libre 1/2, porte fiel de xDrip
 * `utilitymodels/LibreUtils.java` (GPLv3). Necessários para validar e identificar
 * leituras vindas de leitores acoplados (MiaoMiao/Tomato, Bubble, Atom).
 *
 * Referências: LibreUtils.java (decodeSerialNumberKey/decodeSerialNumber, computeCRC16,
 * CheckCRC16/verify, isSensorReady) e NFCReaderX/Constants para tamanho/estrutura.
 */
object LibreBridgeUtils {
    const val FRAM_SIZE = 344
    private const val TREND_START = 28
    private const val HISTORY_START = 124
    private const val FRAM_RECORD_SIZE = 6

    // Tabela CRC16 idêntica à de LibreUtils.java (xDrip).
    private val CRC16_TABLE = longArrayOf(
        0,4489,8978,12955,17956,22445,25910,29887,35912,40385,44890,48851,51820,56293,59774,63735,
        4225,264,13203,8730,22181,18220,30135,25662,40137,36160,49115,44626,56045,52068,63999,59510,
        8450,12427,528,5017,26406,30383,17460,21949,44362,48323,36440,40913,60270,64231,51324,55797,
        12675,8202,4753,792,30631,26158,21685,17724,48587,44098,40665,36688,64495,60006,55549,51572,
        16900,21389,24854,28831,1056,5545,10034,14011,52812,57285,60766,64727,34920,39393,43898,47859,
        21125,17164,29079,24606,5281,1320,14259,9786,57037,53060,64991,60502,39145,35168,48123,43634,
        25350,29327,16404,20893,9506,13483,1584,6073,61262,65223,52316,56789,43370,47331,35448,39921,
        29575,25102,20629,16668,13731,9258,5809,1848,65487,60998,56541,52564,47595,43106,39673,35696,
        33800,38273,42778,46739,49708,54181,57662,61623,2112,6601,11090,15067,20068,24557,28022,31999,
        38025,34048,47003,42514,53933,49956,61887,57398,6337,2376,15315,10842,24293,20332,32247,27774,
        42250,46211,34328,38801,58158,62119,49212,53685,10562,14539,2640,7129,28518,32495,19572,24061,
        46475,41986,38553,34576,62383,57894,53437,49460,14787,10314,6865,2904,32743,28270,23797,19836,
        50700,55173,58654,62615,32808,37281,41786,45747,19012,23501,26966,30943,3168,7657,12146,16123,
        54925,50948,62879,58390,37033,33056,46011,41522,23237,19276,31191,26718,7393,3432,16371,11898,
        59150,63111,50204,54677,41258,45219,33336,37809,27462,31439,18516,23005,11618,15595,3696,8185,
        63375,58886,54429,50452,45483,40994,37561,33584,31687,27214,22741,18780,15843,11370,7921,3960
    )

    private val LOOKUP = charArrayOf(
        '0','1','2','3','4','5','6','7','8','9',
        'A','C','D','E','F','G','H','J','K','L',
        'M','N','P','Q','R','T','U','V','W','X','Y','Z'
    )

    /** LibreUtils.computeCRC16: CRC sobre [start+2, start+size), comparado aos 2 primeiros bytes. */
    fun computeCrc16(data: ByteArray, start: Int, size: Int): Long {
        var crc = 0xffffL
        for (i in (start + 2) until (start + size)) {
            val idx = ((crc xor (data[i].toLong() and 0xFF)) and 0xffL).toInt()
            crc = (crc shr 8) xor CRC16_TABLE[idx]
        }
        var reverse = 0L
        var c = crc
        for (i in 0 until 16) {
            reverse = (reverse shl 1) or (c and 1L)
            c = c shr 1
        }
        return reverse
    }

    fun checkCrc16(data: ByteArray, start: Int, size: Int): Boolean {
        if (data.size < start + size || size < 2) return false
        val crc = computeCrc16(data, start, size)
        val stored = ((data[start + 1].toInt() and 0xFF) * 256 + (data[start].toInt() and 0xFF)).toLong()
        return crc == stored
    }

    /** LibreUtils.verify para Libre 1/2 (344 bytes): blocos 0..24, 24..296, 320..24. */
    fun verifyLibre1Frame(data: ByteArray): Boolean {
        if (data.size < FRAM_SIZE) return false
        var ok = checkCrc16(data, 0, 24)
        ok = ok and checkCrc16(data, 24, 296)
        ok = ok and checkCrc16(data, 320, 24)
        return ok
    }

    /** LibreUtils.isSensorReady(byte): 0x02 iniciando, 0x03 pronto, 0x04 expirado (aceito pelo xDrip em alguns fluxos). */
    fun isSensorReady(statusByte: Byte): Boolean = when (statusByte.toInt() and 0xFF) {
        0x02, 0x03, 0x04 -> true
        else -> false
    }

    /** Porte fiel de LibreUtils.decodeSerialNumberKey/decodeSerialNumber (UID/patchUid de 8 bytes). */
    fun decodeSerialNumberKey(patchUid: ByteArray): String {
        if (patchUid.size < 8) return ""
        val buf = ByteArray(11)
        for (i in 0 until 8) buf[3 + i] = patchUid[i]
        return decodeSerialNumber(buf)
    }

    fun decodeSerialNumber(input: ByteArray): String {
        if (input.size < 11) return ""
        val uuidShort = ByteArray(8)
        for (i in 2..7) uuidShort[i - 2] = input[(2 + 8) - i]
        uuidShort[6] = 0
        uuidShort[7] = 0
        val binary = buildString {
            for (i in 0 until 8) {
                append(((uuidShort[i].toInt() and 0xFF) + 256).toString(2).substring(1))
            }
        }
        val out = StringBuilder("0")
        for (i in 0 until 10) {
            var value = 0
            for (k in 0 until 5) {
                val bit = if (binary[5 * i + k] == '1') 1 else 0
                value = value * 2 + bit
            }
            if (value in LOOKUP.indices) out.append(LOOKUP[value]) else out.append('0')
        }
        return out.toString().uppercase(Locale.US)
    }

    fun getGlucoseRaw(lo: Byte, hi: Byte): Int =
        (((hi.toInt() and 0xFF) * 256) + (lo.toInt() and 0xFF)) and 0x1FFF

    /** Leitura bruta do minuto atual (trend[0]) a partir do FRAM Libre 1/2. */
    fun currentRawFromFram(data: ByteArray): Int? {
        if (data.size < FRAM_SIZE) return null
        val indexTrend = data[26].toInt() and 0xFF
        val i = (indexTrend - 1 + 16) % 16
        val off = i * FRAM_RECORD_SIZE + TREND_START
        if (off + 1 >= data.size) return null
        val raw = getGlucoseRaw(data[off], data[off + 1])
        return raw.takeIf { it in 1..4095 }
    }

    /** Idade do sensor em minutos (campo do FRAM, NFCReaderX: bytes 316/317). */
    fun sensorTimeMinutes(data: ByteArray): Int? {
        if (data.size <= 317) return null
        return 256 * (data[317].toInt() and 0xFF) + (data[316].toInt() and 0xFF)
    }

    // (removido em 21/11: "rawToMgdl" convertia BRUTO em glicemia; ver LibreBridgeFramDecoder)

    fun toHex(bytes: ByteArray?, max: Int = bytes?.size ?: 0): String {
        if (bytes == null) return ""
        val n = minOf(bytes.size, max)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) sb.append(String.format("%02X", bytes[i]))
        if (bytes.size > n) sb.append("…")
        return sb.toString()
    }
}
