package com.gluview.app.data.sibionics

/**
 * Aviso de saúde do sensor, estilo "bateria fraca".
 *
 * Regra de ouro da Fase 3: o GluView **nunca** corta leitura por idade do sensor.
 * Este aviso só SINALIZA — não bloqueia nenhum dado. Preserva a "vida ilimitada"
 * (o sensor segue transmitindo; só avisamos que está velho ou sem sinal).
 */
enum class SensorWarning {
    /** Tudo normal. */
    NONE,

    /**
     * Sensor passou da vida útil do firmware e não produz mais leitura — só a troca resolve.
     * Tem prioridade sobre [CHECK_SENSOR]: quando o sensor expirou, "verifique o sensor" manda o
     * usuário procurar um problema que não existe (sinal, posição, Bluetooth), enquanto a ação
     * certa é trocar. Foi exatamente essa confusão que custou horas sem dado numa troca de sensor.
     */
    EXPIRED,

    /** Sem dados há muito tempo — possível perda de sinal/conexão (prioridade média). */
    NO_SIGNAL,

    /**
     * Sensor silencioso além do ciclo completo de reconexão (≥ 60 min) — pode estar expirado,
     * descolado ou sem bateria. Tem prioridade sobre [NO_SIGNAL] e [AGING].
     */
    CHECK_SENSOR,

    /** Sensor além da vida nominal — informativo, leitura continua. */
    AGING
}

/**
 * Estado corrente do sensor Sibionics conectado via BLE (Frente B).
 *
 * O início do sensor é reconstruído do campo `index` da leitura
 * (index = minutos desde o início → início = agora − index*60s),
 * exatamente como o SDK/Juggluco fazem. Não há teto de vida.
 */
data class SibionicsSensorStatus(
    val connected: Boolean = false,
    /** Início do sensor reconstruído (epoch ms). 0 = desconhecido. */
    val sensorStartMs: Long = 0L,
    /** Última leitura recebida (epoch ms). 0 = nunca. */
    val lastReadingMs: Long = 0L,
    /** Último index (minuto do sensor) recebido. -1 = nenhum. */
    val lastIndex: Int = -1,
    /** Força do sinal BLE em dBm, se lida. null = desconhecida. */
    val rssi: Int? = null,
    /** Serial / nome BLE do sensor, se conhecido. */
    val serial: String? = null,
    /** Última glicemia BRUTA do algoritmo (mmol/L, sem calibração do usuário). 0 = nenhuma. */
    val lastRawMmol: Double = 0.0,
    /** Última tensão de bateria do sensor em mV (protocolo v1.1.2). 0 = n/d. */
    val batteryMv: Int = 0
) {
    /** Idade do sensor em dias inteiros, ou null se o início é desconhecido. */
    fun ageDays(nowMs: Long = System.currentTimeMillis()): Int? =
        if (sensorStartMs > 0L) ((nowMs - sensorStartMs) / 86_400_000L).toInt() else null

    /**
     * Minutos de vida do sensor segundo o índice recebido (o índice É a idade em minutos).
     * null = nenhum pacote recebido ainda.
     */
    fun lifetimeMinutes(): Int? = lastIndex.takeIf { it >= 0 }

    /**
     * Sensor além da vida útil do firmware ([EXPIRY_MINUTES]).
     *
     * Não é o mesmo que "sensor velho" ([ageDays] vs. a vida nominal de rótulo, 14 dias): passar
     * do rótulo é informativo e o sensor segue medindo — passar do limite do firmware é o fim,
     * o sensor para de gerar glicemia e nenhuma reconexão traz dado de volta.
     */
    fun isExpired(): Boolean = (lifetimeMinutes() ?: 0) >= EXPIRY_MINUTES

    /** Minutos desde a última leitura, ou null se nunca recebeu. */
    fun minutesSinceReading(nowMs: Long = System.currentTimeMillis()): Long? =
        if (lastReadingMs > 0L) (nowMs - lastReadingMs) / 60_000L else null

    /**
     * Minutos de vida do sensor já decorridos, na melhor fonte disponível:
     *   1. [lastIndex] — o índice do pacote É a idade em minutos (fonte da verdade do sensor);
     *   2. [sensorStartMs] — âncora reconstruída/persistida, quando já houve alguma leitura;
     *   3. [pairedAtMs] — instante em que o sensor NOVO foi pareado (ver
     *      `AppPreferences.sibionicsSensorPairedMs`). É o único que existe no começo do
     *      aquecimento, quando o sensor ainda não transmitiu nada.
     * null = nenhuma referência (sensor desconhecido) → não dá para afirmar que está aquecendo.
     */
    fun minutesSinceStart(pairedAtMs: Long = 0L, nowMs: Long = System.currentTimeMillis()): Int? =
        when {
            lastIndex >= 0      -> lastIndex
            sensorStartMs > 0L  -> ((nowMs - sensorStartMs) / 60_000L).toInt().coerceAtLeast(0)
            pairedAtMs > 0L     -> ((nowMs - pairedAtMs) / 60_000L).toInt().coerceAtLeast(0)
            else                -> null
        }

    /**
     * Sensor dentro da janela de aquecimento ([WARMUP_MINUTES] min desde a inserção).
     *
     * Fiel ao app oficial (SugarJoy+): `zj.c()` trata índices 0..60 como inicialização e
     * `BsMonitoringViewModel.l(i)` exibe "faltam 60 − i min"; o valor só passa a valer a partir
     * do índice 60. Aqui isto é **apenas sinalização**: leitura válida que chegue antes do fim
     * do aquecimento continua sendo gravada, exibida e considerada normalmente (regra de ouro
     * da Fase 3 — o GluView nunca corta leitura).
     */
    fun isWarmingUp(pairedAtMs: Long = 0L, nowMs: Long = System.currentTimeMillis()): Boolean =
        minutesSinceStart(pairedAtMs, nowMs)?.let { it < WARMUP_MINUTES } ?: false

    /** Minutos que ainda faltam para o fim do aquecimento (0 se já terminou/desconhecido). */
    fun warmupMinutesLeft(pairedAtMs: Long = 0L, nowMs: Long = System.currentTimeMillis()): Int =
        minutesSinceStart(pairedAtMs, nowMs)
            ?.let { (WARMUP_MINUTES - it).coerceIn(0, WARMUP_MINUTES) } ?: 0

    /**
     * Avalia o aviso a exibir. NO_SIGNAL tem prioridade sobre AGING.
     * Nunca retorna nada que implique "parar de ler".
     *
     * @param noSignalMin minutos sem leitura para alertar perda de sinal
     * @param agingDays   dias de vida a partir dos quais avisar que o sensor está velho
     * @param warmingUp   sensor na janela de aquecimento (ver [isWarmingUp]) → nunca alarma
     */
    fun evaluateWarning(
        noSignalMin: Int,
        agingDays: Int,
        checkSensorMin: Int = 60,
        nowMs: Long = System.currentTimeMillis(),
        warmingUp: Boolean = false
    ): SensorWarning {
        // Aquecimento: o sensor legitimamente não entrega leitura na 1ª hora — avisar "sem
        // sinal"/"verifique o sensor" aqui seria alarme falso logo depois de trocar o sensor.
        // A UI mostra o estado de aquecimento no lugar (ver HomeViewModel.sensorWarmup).
        if (warmingUp) return SensorWarning.NONE
        // Expirado vem primeiro: é a explicação REAL de um sensor silencioso no fim da vida, e
        // manda o usuário trocar em vez de caçar um defeito inexistente.
        if (isExpired()) return SensorWarning.EXPIRED
        val mins = minutesSinceReading(nowMs)
        if (mins != null && mins >= checkSensorMin) return SensorWarning.CHECK_SENSOR
        if (mins != null && mins >= noSignalMin) return SensorWarning.NO_SIGNAL
        val age = ageDays(nowMs)
        if (age != null && age >= agingDays) return SensorWarning.AGING
        return SensorWarning.NONE
    }

    companion object {
        /**
         * Aquecimento do Sibionics: 60 minutos (índices 0..59). Confirmado na engenharia
         * reversa do SugarJoy+ — `zj.c()` (`index <= 60` = inicialização, `index == 60` encerra),
         * `BsMonitoringViewModel.h()/s0()` (`index < 60` não alarma nem carimba) e
         * `BsMonitoringViewModel.l(i)` (tela "faltam 60 − i min").
         */
        const val WARMUP_MINUTES = 60

        /**
         * Vida útil do firmware do Sibionics: 34.560 minutos = 24 dias.
         * Confirmado na engenharia reversa do app oficial — `BsMonitoringViewModel.e0()` calcula o
         * tempo restante como `34561 - index`. É bem mais que os 14 dias nominais do rótulo, e é
         * este o limite em que o sensor efetivamente para (o sensor anterior deste aparelho parou
         * no índice 34.303, a poucas horas do teto).
         */
        const val EXPIRY_MINUTES = 34_560
    }
}
