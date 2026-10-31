package com.gluview.app.data.sibionics

/**
 * Interface comum para os protocolos de sensor Sibionics:
 *   - [SibionicsV110Protocol]: Sibionics Nacional (protocolo v1.1.0, transmite automático)
 *   - [SibionicsV112Protocol]: Sibionics Chinês  (protocolo v1.1.2, Sugar Joy / família LT25xx;
 *     requer comando de início no FF32, depois transmite pacotes AA 55 09)
 *
 * Ambos produzem [Reading] com os mesmos campos e caem no mesmo pipeline
 * Room → notificação → widgets → alertas.
 *
 * Regra de ouro: NENHUMA implementação deve cortar leituras por `index`/idade.
 */
interface SibionicsProtocol {

    /** Uma leitura calibrada. `index` = minutos desde o início do sensor. */
    data class Reading(
        val index: Int,
        val mgdl: Int,
        val mmol: Double,
        val trend: Int,
        /** Corrente bruta (ISIG) do sensor, quando disponível. Base da calibração v1.1.2. 0 = n/d. */
        val rawCurrent: Int = 0,
        /** Tensão da bateria do sensor em mV, quando disponível (v1.1.2). 0 = n/d. */
        val batteryMv: Int = 0,
        /**
         * `true` quando este `mgdl` difere do da leitura anterior processada pela mesma conexão
         * (a `.so` "confirmou"/mudou de valor); `false` quando repete o valor anterior (ainda na
         * janela de observação interna do algoritmo — ver modos "Todas"/"Precisas a cada 5min" em
         * Ajustes > Sensor). Default `true` para protocolos sem esse conceito (v1.1.0/Libre2).
         */
        val confirmed: Boolean = true
    ) {
        /** Reconstrói o timestamp do início do sensor a partir do index. */
        fun sensorStartMs(nowMs: Long = System.currentTimeMillis()): Long =
            nowMs - index.toLong() * 60_000L
    }

    /**
     * Chamado quando as notificações BLE foram habilitadas (CCCD escrito → FF31 ativo).
     * Protocolo v1.1.0: geralmente não precisa enviar nada.
     * Protocolo v1.1.2: envia o comando de início ("abrir broadcast") via [sendCommand] → FF32.
     */
    fun onReady(sendCommand: (ByteArray) -> Unit)

    /**
     * Chamado para cada pacote recebido na characteristic FF31.
     * Pode chamar [sendCommand] para enviar comandos ao sensor.
     * Retorna 0..N leituras de glicemia decodificadas.
     * NUNCA filtra por idade de sensor.
     */
    fun onPacket(raw: ByteArray, sendCommand: (ByteArray) -> Unit): List<Reading>

    /** Libera recursos nativos (contextos JNI). */
    fun release()

    /** Serializa o estado acumulado do algoritmo JNI (v1.1.2 apenas; outros retornam null). */
    fun getContextJson(): String? = null

    /** Último índice alimentado ao algoritmo, para persistência (v1.1.2). -1 = n/d. */
    val lastProcessedIndex: Int get() = -1
}
