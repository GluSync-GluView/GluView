package com.gluview.app.data.accuchek.cgm

/**
 * E2E-CRC do serviço CGM: **CRC-16/MCRF4XX** (polinômio CCITT refletido 0x8408, início 0xFFFF,
 * sem XOR final), gravado little-endian nos 2 últimos bytes.
 *
 * O SmartGuide liga o E2E-CRC (bit 12 do CGM Feature): vem em toda característica do serviço CGM e
 * é exigido nos comandos que escrevemos no SOCP. O RACP é genérico e NÃO leva CRC.
 * Conferido em bytes reais (leituras, status, feature, start time e nos comandos/respostas do
 * SOCP usados pelo Juggluco, ex. `02 95 2C` e `03 05 7D 8D`).
 */
object E2eCrc {

    fun compute(data: ByteArray, from: Int = 0, end: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until end) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    /** Devolve [payload] com o CRC anexado (little-endian). */
    fun append(payload: ByteArray): ByteArray {
        val crc = compute(payload)
        return payload + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
    }

    /** true quando os 2 últimos bytes de [record] são o CRC correto de todo o resto. */
    fun hasValidTrailing(record: ByteArray): Boolean {
        if (record.size < 3) return false
        val n = record.size
        val stored = (record[n - 2].toInt() and 0xFF) or ((record[n - 1].toInt() and 0xFF) shl 8)
        return compute(record, 0, n - 2) == stored
    }
}
