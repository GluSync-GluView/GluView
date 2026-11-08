package com.gluview.app.data.sibionics

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.SensorHistoryDao
import com.gluview.app.data.sensor.SensorCode
import com.gluview.app.data.sensor.SensorDiscovery
import com.gluview.app.data.sensor.SibionicsIdentityMatcher
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.oracle.SibionicsOracle
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.GlucoseSource
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.AppLogger
import com.gluview.app.watchface.miband.MiBandWatchFacePusher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquestrador da Frente B — leitura BLE direta do sensor Sibionics.
 *
 * Suporta dois protocolos, selecionados por [DataSourceType]:
 *   - [DataSourceType.SIBIONICS_NACIONAL] → [SibionicsV110Protocol] (v1.1.0, sem auth)
 *   - [DataSourceType.SIBIONICS_CHINES]   → [SibionicsV112Protocol] (v1.1.2, Sugar Joy/LT25xx)
 *
 * ## Reconexão automática — Tiered Backoff
 *   • Fase 1 (0–5 min):   5 × 1 min  — detecção rápida de reconexão
 *   • Fase 2 (5–20 min):  3 × 5 min  — BLE e bateria estabilizam
 *   • Fase 3 (20–60 min): 4 × 10 min — conserva bateria
 * Após 60 min sem reconectar: notifica o usuário e para de tentar.
 *
 * ## Logs gerados (app_usage.jsonl / app_error.jsonl)
 * - Cada pacote BLE recebido (hex + tamanho)
 * - Cada leitura decodificada (mmol, mgdl, trend, index)
 * - Cada tentativa de reconexão (fase, tentativa/total)
 * - Todos os passos de auth v1.2.0 (bytes enviados/recebidos, JSON retornado pela .so)
 * - Erros de decode, falhas de writeCommand, RSSI
 */
@Singleton
class SibionicsBleSource @Inject constructor(
    private val ble: SibionicsBleManager,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger,
    // Instrumentação de validação da futura reimplementação da .so (não invasiva). Ver [SibionicsOracle].
    private val oracle: SibionicsOracle,
    private val miBandPusher: MiBandWatchFacePusher,
    private val sensorHistoryDao: SensorHistoryDao,
    private val discovery: SensorDiscovery
) : SibionicsBleManager.Listener, GlucoseSource {

    private companion object {
        const val LOG = "SIBIONICS_SRC"
        const val MMOL_TO_MGDL = 18.0182
        // Faixa fisiologicamente plausível; valores fora disso são descartados.
        val PLAUSIBLE_MGDL = 20..600

        /**
         * Grade de reconexão:
         *   5 × 1 min + 3 × 5 min + 4 × 10 min = 12 tentativas em ~60 min.
         * Cada tentativa tem 20 s para o handshake GATT antes da próxima espera.
         */
        val RECONNECT_SCHEDULE_MS = buildList {
            repeat(5) { add(TimeUnit.MINUTES.toMillis(1)) }
            repeat(3) { add(TimeUnit.MINUTES.toMillis(5)) }
            repeat(4) { add(TimeUnit.MINUTES.toMillis(10)) }
        }
        private const val GATT_HANDSHAKE_MS = 20_000L

        /**
         * Tentativas de reconexão no endereço memorizado antes de procurar o sensor pela
         * IDENTIDADE da embalagem. Cobre a fase 1 do backoff (5 × 1 min): falha rápida por sensor
         * longe/Bluetooth ocupado se resolve sozinha nesse intervalo, sem custo de scan.
         * Passando disso, o endereço memorizado provavelmente não é mais o sensor certo — e aí
         * reescanear é o que devolve o app ao sensor correto sem intervenção do usuário.
         */
        private const val RESCAN_AFTER_ATTEMPTS = 5

        /**
         * Minutos de sensor a partir dos quais uma leitura válida pode virar "leitura atual" e
         * disparar alerta. Antes disso ela é gravada normalmente (gráfico/histórico/sincronização)
         * mas não é promovida — ver o uso em [persist].
         */
        private const val STABILIZATION_MIN_INDEX = 15
    }

    // Lidos na GATT callback thread, escritos na IO/main thread → @Volatile obrigatório.
    @Volatile private var protocol: SibionicsProtocol? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var reconnectJob: Job? = null

    @Volatile private var currentMac: String? = null
    @Volatile private var stopped = false
    @Volatile private var lastSgv: Int = -1
    @Volatile private var sourceType: DataSourceType? = null
    @Volatile private var sensorCode: String = ""   // 连接码 atual, p/ persistir o contexto do algoritmo

    // ── Ciclo de vida ──────────────────────────────────────────────────────────

    override fun start(dataSourceType: DataSourceType) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        sourceType = dataSourceType
        s.launch {
            val mac        = preferences.sibionicsMac.first()
            val serial     = preferences.sibionicsSerial.first()
            val customerId = preferences.sibionicsCustomerId.first()

            if (mac.isBlank()) {
                appLogger.error(LOG, "MAC não configurado — configure em Sensor > Trocar de sensor")
                return@launch
            }

            // statusHolder é um singleton EM MEMÓRIA — todo reinício de processo (comum: watchdog,
            // Doze, BLE instável) o recria zerado, o que parece "sensor diferente" mesmo sendo o
            // MESMO sensor físico. Distinguimos pela âncora PERSISTIDA (sibionicsSensorStartMs):
            // se existe, é só o processo reiniciando → restauramos em vez de resetar (resetar aqui
            // reancorava o início usando o índice do primeiro pacote de BACKFILL pós-reconexão,
            // sempre atrasado em relação ao índice "ao vivo" — empurrando toda leitura pro futuro).
            // Sensor físico REALMENTE diferente zera a âncora persistida no momento do scan (ver
            // SensorScanViewModel.saveSensor) — só nesse caso caímos no reset de verdade abaixo.
            if (statusHolder.status.value.serial != serial) {
                val persistedStartMs = preferences.sibionicsSensorStartMs.first()
                if (persistedStartMs > 0L) {
                    appLogger.usage(LOG, "Processo reiniciado — restaurando início do sensor persistido",
                        mapOf("serial" to serial, "sensorStartMs" to persistedStartMs))
                    statusHolder.restoreAnchor(serial, persistedStartMs)
                } else {
                    appLogger.usage(LOG, "Sensor diferente do anterior — reiniciando início/idade rastreados",
                        mapOf("serialAnterior" to (statusHolder.status.value.serial ?: ""), "serialNovo" to serial))
                    statusHolder.resetForNewSensor(serial)
                }
            }

            currentMac = mac
            appLogger.usage(LOG, "Iniciando Frente B",
                mapOf("fonte" to dataSourceType.label, "mac" to mac, "serial" to serial,
                      "temCustomerId" to customerId.isNotBlank()))

            protocol = when (dataSourceType) {
                DataSourceType.SIBIONICS_NACIONAL -> SibionicsV110Protocol.create(serial, appLogger, oracle)
                // Sensor Chinês (Sugar Joy, família LT25xx) = protocolo v1.1.2, NÃO v1.2.0.
                // Confirmado por RE: o app oficial com.sugarjoy.plus usa libnative-algorithm-jni-v112.so.
                // O caminho v1.2.0 (libdata-handle-lib.so) era de outro app (Sijoy) e travava o processo.
                // O algoritmo quer o ID de 8 chars (连接码), não o serial GS1 completo:
                //   prefere o customerId escaneado; senão deriva do serial (chars 6-13).
                DataSourceType.SIBIONICS_CHINES   -> {
                    val sensorId = customerId.ifBlank { SibionicsQrParser.connectionCodeFrom(serial) }
                    sensorCode = sensorId
                    // Restaura o estado do algoritmo SOMENTE se for do MESMO sensor (código bate).
                    // Caso contrário, começa limpo (aquecimento) — nunca restaura estado alheio/lixo.
                    val savedCode  = preferences.sibionicsAlgoCode.first()
                    val savedCtx   = preferences.sibionicsAlgoCtx.first()
                    val savedIdx   = preferences.sibionicsAlgoIdx.first()
                    val canRestore = savedCode == sensorId && savedCtx.isNotBlank() && savedIdx >= 0
                    appLogger.usage(LOG, "Preparando protocolo v1.1.2",
                        mapOf("sensorId" to sensorId, "restauraContexto" to canRestore,
                              "idxSalvo" to savedIdx))
                    SibionicsV112Protocol.create(
                        mac, sensorId, serial, appLogger,
                        restoreContextJson = if (canRestore) savedCtx else null,
                        restoreLastIndex   = if (canRestore) savedIdx else -1,
                        oracle             = oracle
                    )
                }
                else -> null
            }

            if (protocol == null) {
                appLogger.error(LOG, "Protocolo indisponível (.so ausente?)",
                    mapOf("fonte" to dataSourceType.label, "serial" to serial))
                return@launch
            }

            appLogger.usage(LOG, "Protocolo criado — conectando ao sensor",
                mapOf("protocolo" to protocol!!::class.simpleName.orEmpty()))
            ble.setListener(this@SibionicsBleSource)
            ble.connect(mac)
        }

        s.launch { warningLoop() }
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Frente B")
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        ble.setListener(null)
        ble.disconnect()
        protocol?.release()
        protocol = null
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        scope?.cancel()
        scope = null
        lastSgv = -1
        currentMac = null
    }

    // ── SibionicsBleManager.Listener ───────────────────────────────────────────

    override fun onConnectionChanged(connected: Boolean) {
        statusHolder.onConnectionChanged(connected)
        if (connected) {
            reconnectJob?.cancel()
            reconnectJob = null
            notificationHelper.cancelSensorWarning()
            // Dedup é responsabilidade do protocolo (gate de ordem por lastIndex, persistido).
            // NÃO limpamos nada aqui — o contexto do algoritmo permanece quente na reconexão.
            appLogger.usage(LOG, "Sensor conectado — prontos para receber pacotes")
            scope?.launch { verifyConnectedIdentity() }
        } else if (!stopped) {
            val mac = currentMac
            if (mac != null) {
                appLogger.error(LOG, "Conexão perdida — agendando reconexão")
                startReconnect(mac)
            }
        }
    }

    override fun onRssi(rssi: Int) = statusHolder.onRssi(rssi)

    /**
     * Rádio do sistema religado (ver [SibionicsBleManager.Listener.onBluetoothTurnedOn]). Enquanto
     * ele esteve desligado, a conexão pode ter caído sem nunca disparar [onConnectionChanged]
     * (bug conhecido do stack BLE de vários aparelhos) — sem isto, o app ficaria preso esperando
     * um callback que nunca chega. Cancela qualquer backoff em andamento e tenta na hora; se
     * falhar, [onConnectionChanged] reagenda o cronograma normal a partir do zero.
     */
    override fun onBluetoothTurnedOn() {
        if (stopped) return
        val mac = currentMac ?: return
        appLogger.usage(LOG, "Bluetooth religado pelo sistema — reconectando imediatamente", mapOf("mac" to mac))
        reconnectJob?.cancel()
        reconnectJob = null
        ble.connect(mac)
    }

    override fun onReady() {
        appLogger.usage(LOG, "BLE pronto (CCCD ok) — inicializando protocolo")
        protocol?.onReady { bytes -> ble.writeCommand(bytes) }
    }

    override fun onPacket(raw: ByteArray) {
        val p = protocol ?: run {
            appLogger.error(LOG, "Pacote recebido mas protocolo é null (ainda inicializando?)",
                mapOf("bytes" to raw.size))
            return
        }

        // O protocolo loga internamente cada passo (auth, decode, JSON).
        // Aqui registramos só o envelope de entrada e o resultado final.
        val readings = p.onPacket(raw) { bytes -> ble.writeCommand(bytes) }

        if (readings.isEmpty()) {
            // Normal durante auth v1.2.0; inesperado no v1.1.0 após READY
            appLogger.usage(LOG, "Pacote processado sem leituras (auth ou sem glicemia)",
                mapOf("bytes" to raw.size))
            return
        }

        appLogger.usage(LOG, "Leituras prontas para persistir", mapOf("n" to readings.size))
        scope?.launch { persist(readings) }
    }

    // ── Reconexão com tiered backoff ───────────────────────────────────────────

    private fun startReconnect(mac: String) {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            appLogger.usage(LOG, "Agenda de reconexão iniciada",
                mapOf("mac" to mac, "tentativas" to RECONNECT_SCHEDULE_MS.size, "duracaoMinutos" to 60))

            RECONNECT_SCHEDULE_MS.forEachIndexed { idx, delayMs ->
                delay(delayMs)
                if (!isActive) return@launch

                val fase = when {
                    idx < 5  -> "1/3 (1 min)"
                    idx < 8  -> "2/3 (5 min)"
                    else     -> "3/3 (10 min)"
                }
                appLogger.usage(LOG, "Tentativa de reconexão BLE",
                    mapOf("tentativa" to "${idx + 1}/${RECONNECT_SCHEDULE_MS.size}", "fase" to fase))

                ble.connect(mac)

                // Janela para o handshake GATT completar antes da próxima espera
                delay(GATT_HANDSHAKE_MS)
                if (!isActive) return@launch

                // Auto-cura: se o endereço memorizado não conecta há várias tentativas, ele pode
                // não ser mais o sensor certo (troca de sensor em que o endereço antigo ficou
                // salvo, sensor substituído, endereço aleatorizado). A embalagem escaneada é a
                // fonte da verdade — procuramos o rádio que casa com ELA e adotamos o endereço
                // novo. Sem isso o app fica preso tentando um endereço que nunca vai responder.
                if (idx + 1 == RESCAN_AFTER_ATTEMPTS && !statusHolder.status.value.connected) {
                    val novoMac = rediscoverByIdentity()
                    if (novoMac != null && !novoMac.equals(mac, ignoreCase = true)) {
                        appLogger.usage(LOG, "Sensor reencontrado em outro endereço — adotando",
                            mapOf("anterior" to mac, "novo" to novoMac))
                        currentMac = novoMac
                        preferences.saveSibionicsMac(novoMac)
                        ble.connect(novoMac)
                        delay(GATT_HANDSHAKE_MS)
                        if (!isActive) return@launch
                    }
                }
            }

            // Todas as tentativas esgotadas
            if (!stopped) {
                appLogger.error(LOG, "Sensor não reconectou após 60 min",
                    mapOf("mac" to mac, "tentativas" to RECONNECT_SCHEDULE_MS.size))
                notificationHelper.showSensorWarning(
                    "Sensor desconectado",
                    "Não foi possível reconectar ao sensor Sibionics após 60 minutos. " +
                        "Verifique se o sensor está próximo e o Bluetooth está ativo."
                )
            }
        }
    }

    /**
     * Confere se o rádio conectado é mesmo o sensor da embalagem escaneada, comparando o nome
     * anunciado com o código de conexão ([SibionicsIdentityMatcher]).
     *
     * **Só observa — nunca desconecta.** Cortar a conexão por um nome que não casa arriscaria
     * derrubar um sensor que está entregando dado (nome ausente no cache do sistema, lote com
     * padrão diferente), e a regra da Fase 3 é nunca interromper leitura. O valor aqui é
     * diagnóstico: um sensor errado passa a ser visível no log em vez de se disfarçar de sensor
     * em aquecimento — que foi exatamente o que custou 1h13 sem dado ao trocar de sensor.
     *
     * Também é o ponto de migração: instalações que só tinham o endereço salvo ganham o nome
     * memorizado na primeira conexão, sem precisar reescanear a caixa.
     */
    private suspend fun verifyConnectedIdentity() {
        if (sourceType != DataSourceType.SIBIONICS_CHINES) return
        val name = ble.connectedDeviceName?.trim().orEmpty()
        if (name.isBlank()) return

        val serial     = preferences.sibionicsSerial.first()
        val customerId = preferences.sibionicsCustomerId.first()
        val code = SensorCode(
            connectionCode = customerId.ifBlank { SibionicsQrParser.connectionCodeFrom(serial) },
            serial         = serial
        )
        if (!code.isValid) return

        if (SibionicsIdentityMatcher().matches(name, code)) {
            if (preferences.sibionicsBleName.first() != name) {
                preferences.saveSibionicsBleName(name)
                appLogger.usage(LOG, "Identidade do sensor confirmada",
                    mapOf("nome" to name, "codigo" to code.connectionCode))
            }
        } else {
            appLogger.error(LOG, "Sensor conectado NÃO corresponde à embalagem escaneada",
                mapOf("nomeAnunciado" to name, "codigoEsperado" to code.connectionCode,
                      "mac" to (currentMac ?: "?")))
        }
    }

    /**
     * Procura o sensor da embalagem configurada e devolve o endereço encontrado, ou null.
     * Só roda para Sibionics Chinês — é a família cuja regra de identidade está confirmada
     * (ver [SibionicsIdentityMatcher]).
     */
    private suspend fun rediscoverByIdentity(): String? {
        if (sourceType != DataSourceType.SIBIONICS_CHINES) return null
        val serial     = preferences.sibionicsSerial.first()
        val customerId = preferences.sibionicsCustomerId.first()
        val code = SensorCode(
            connectionCode = customerId.ifBlank { SibionicsQrParser.connectionCodeFrom(serial) },
            serial         = serial
        )
        if (!code.isValid) {
            appLogger.error(LOG, "Redescoberta ignorada — sem código de conexão utilizável")
            return null
        }
        appLogger.usage(LOG, "Reconexão falhou repetidamente — procurando o sensor da embalagem")
        val found = runCatching { discovery.findSensor(code, SibionicsIdentityMatcher()) }
            .onFailure { e ->
                appLogger.error(LOG, "Falha na redescoberta do sensor",
                    mapOf("erro" to (e.message ?: e::class.simpleName.orEmpty())))
            }
            .getOrNull() ?: return null
        runCatching { preferences.saveSibionicsBleName(found.name) }
        return found.mac
    }

    // ── Persistência ───────────────────────────────────────────────────────────

    private suspend fun persist(readings: List<SibionicsProtocol.Reading>) {
        val now      = System.currentTimeMillis()
        val maxIndex = readings.maxOf { it.index }
        val existingStartMs = statusHolder.status.value.sensorStartMs
        // `index` do sensor = minutos desde a ativação; o horário de cada leitura é
        // `startMs + index·60s`. Como o índice `maxIndex` já foi GERADO pelo sensor (chegou
        // agora, com latência ≥ 0), a ativação foi no máximo em `now - maxIndex·60s` — esse é
        // um teto rígido para `startMs`.
        val impliedStartMs = now - maxIndex.toLong() * 60_000L
        val startMs = when {
            // 1ª leitura desta vida do sensor: sem âncora em memória NEM persistida.
            existingStartMs <= 0L -> impliedStartMs.also { preferences.saveSibionicsSensorStart(it) }

            // Âncora ficou DEPOIS do teto → a leitura mais nova sai datada no futuro e some do
            // gráfico (o render corta timestamp > agora) enquanto o card ainda a mostra. Causa:
            // a âncora foi cravada cedo demais (1º pacote pós-scan era backfill, com maxIndex
            // atrasado) ou o relógio do sensor deriva do relógio do aparelho ao longo dos dias
            // de vida. Corrige monotonicamente (só puxa para TRÁS), no máx. 90 s por pacote para
            // um pico de latência BLE / ajuste de fuso não arrastar todo o histórico de uma vez.
            // Pacotes de backfill têm maxIndex antigo → impliedStartMs > existingStartMs → cai
            // no `else` e nada muda.
            impliedStartMs < existingStartMs -> {
                val corrected = maxOf(existingStartMs - 90_000L, impliedStartMs)
                preferences.saveSibionicsSensorStart(corrected)
                appLogger.usage(LOG, "Âncora do sensor corrigida (leitura estava no futuro)",
                    mapOf("ajusteSeg" to (existingStartMs - corrected) / 1000, "maxIndex" to maxIndex))
                corrected
            }

            else -> existingStartMs
        }

        val mac    = currentMac ?: ""
        val latest = readings.maxBy { it.index }

        // Glicemia bruta = saída do algoritmo de fábrica (mmol/L). O 连接码 injeta a calibração
        // de fábrica (dia 0) em initAlgorithmContext. Num sensor JÁ USADO o GluView não tem o
        // histórico de deriva que o Sugar Joy acumulou desde a ativação → aplicamos uma ÂNCORA
        // de ponto único (ponta de dedo): fator = ref / bruto. Num sensor NOVO rastreado desde o
        // índice 1 o fator fica 1.0 (precisão nativa, sem âncora).
        val anchor = preferences.sibionicsCalFactor.first().takeIf { it > 0.0 } ?: 1.0
        statusHolder.onRawMmol(latest.mmol)

        // Modo de exibição (Ajustes > Sensor): no modo CONFIRMED_ONLY, só leituras confirmadas
        // (a .so mudou de valor) viram a leitura atual/disparam alerta — ver GlucoseDisplayMode.
        // Nada deixa de ser GRAVADO (savedCount/Room abaixo são os mesmos em qualquer modo).
        val displayMode = GlucoseDisplayMode.fromId(preferences.glucoseDisplayMode.first())

        var savedCount = 0
        var lastSavedMgdl = -1
        readings.sortedBy { it.index }.forEach { r ->
            val mmolDisp = r.mmol * anchor
            val mgdl = Math.round(mmolDisp * MMOL_TO_MGDL).toInt()
            if (mgdl !in PLAUSIBLE_MGDL) {
                appLogger.usage(LOG, "Leitura descartada (fora da faixa)", mapOf("mgdl" to mgdl, "index" to r.index))
                return@forEach
            }
            val dateMs = startMs + r.index.toLong() * 60_000L
            val delta  = if (lastSgv >= 0) mgdl - lastSgv else 0
            // Piso de estabilização: nos primeiros minutos do sensor o algoritmo ainda está
            // assentando e pode devolver valor positivo porém incoerente — o caso observado foi
            // 36 mg/dL com seta dupla para CIMA no minuto 5, valor e tendência se contradizendo.
            // A leitura continua sendo GRAVADA (entra no gráfico e no histórico, como você pediu),
            // mas não vira a leitura atual nem dispara alerta antes do piso. O app oficial é ainda
            // mais restritivo: não exibe valor nenhum antes do índice 60.
            val estabilizado = r.index >= STABILIZATION_MIN_INDEX
            val effectiveMakeCurrent = (r.index == maxIndex) && estabilizado &&
                (displayMode != GlucoseDisplayMode.CONFIRMED_ONLY || r.confirmed)
            if (!estabilizado) {
                appLogger.usage(LOG, "Leitura gravada sem promover (sensor estabilizando)",
                    mapOf("index" to r.index, "mgdl" to mgdl, "pisoMin" to STABILIZATION_MIN_INDEX))
            }
            repository.storeExternalReading(
                dateMs       = dateMs,
                sgvMgDl      = mgdl.toDouble(),
                arrow        = trendToArrow(r.trend),
                delta        = delta,
                makeCurrent  = effectiveMakeCurrent,
                rawMmol      = r.mmol,
                calibFactor  = anchor,
                sensorMac    = mac,
                sensorAgeMin = r.index,
                confirmed    = r.confirmed
            )
            lastSgv = mgdl
            lastSavedMgdl = mgdl
            savedCount++
        }

        // T27: atualizar status com bateria do pacote mais recente
        val latestBatteryMv = readings.maxByOrNull { it.index }?.batteryMv ?: 0
        statusHolder.onReading(maxIndex, startMs, now, latestBatteryMv)

        // Persiste o estado do algoritmo JNI (código + JSON + último índice) para manter o filtro
        // quente entre reinícios do processo. Usa o índice REAL alimentado ao algoritmo.
        val p = protocol
        if (p != null && sensorCode.isNotBlank()) {
            p.getContextJson()?.takeIf { it.isNotBlank() }?.let { ctxJson ->
                preferences.saveAlgoContext(sensorCode, ctxJson, p.lastProcessedIndex)
            }
        }

        if (savedCount > 0) {
            appLogger.usage(LOG, "Leituras salvas",
                mapOf(
                    "n"          to savedCount,
                    "mgdl"       to lastSavedMgdl,
                    "ancora"     to "%.4f".format(anchor),
                    "index"      to maxIndex,
                    "sensorDias" to "%.1f".format((now - startMs) / 86_400_000.0),
                    "bateriaMv"  to latestBatteryMv
                ))
        }
    }

    // ── Loop de aviso de saúde ─────────────────────────────────────────────────

    private suspend fun warningLoop() {
        while (true) {
            runCatching {
                if (!preferences.sensorWarnEnabled.first()) {
                    notificationHelper.cancelSensorWarning()
                } else {
                    val agingDays   = preferences.sensorAgingDays.first()
                    val noSignalMin = preferences.sensorNoSignalMin.first()
                    val status      = statusHolder.status.value
                    // Sensor recém-inserido: os 60 min de aquecimento são silêncio ESPERADO
                    // (ver SibionicsSensorStatus.WARMUP_MINUTES). Alarmar aqui seria alarme
                    // falso a cada troca de sensor; a Home sinaliza o aquecimento no lugar.
                    // pairedMs = startedAt do período ATIVO em sensor_history — gravado no
                    // instante real do scan (SensorScanViewModel.saveSensor → startNew), não
                    // um timestamp reconstruído depois — por isso é a referência confiável.
                    val pairedMs  = sensorHistoryDao.observeActive().first()?.startedAt ?: 0L
                    val warmingUp = status.isWarmingUp(pairedMs)
                    if (warmingUp) {
                        appLogger.usage(LOG, "Sensor em aquecimento",
                            mapOf("minutosDecorridos" to (status.minutesSinceStart(pairedMs) ?: 0),
                                  "faltamMin" to status.warmupMinutesLeft(pairedMs)))
                    }
                    when (status.evaluateWarning(noSignalMin, agingDays, warmingUp = warmingUp)) {
                        SensorWarning.NONE      -> notificationHelper.cancelSensorWarning()
                        SensorWarning.NO_SIGNAL -> {
                            val min = status.minutesSinceReading() ?: 0
                            appLogger.usage(LOG, "Aviso: sem sinal", mapOf("minutosDesde" to min))
                            notificationHelper.showSensorWarning(
                                "Sensor sem sinal",
                                "Sem novas leituras há $min min. Verifique a proximidade e o Bluetooth."
                            )
                            // Sem leitura nova não há gatilho pro envio automático da Mi Band —
                            // sem isto o visor ficaria congelado sem nenhum indício de que os
                            // dados pararam de chegar.
                            miBandPusher.maybeSendStaleRefresh(min.toInt())
                        }
                        SensorWarning.EXPIRED -> {
                            val dias = status.ageDays() ?: 0
                            appLogger.error(LOG, "Aviso: sensor expirado",
                                mapOf("dias" to dias, "index" to status.lastIndex))
                            notificationHelper.showSensorWarning(
                                "Sensor expirado",
                                "Este sensor chegou ao fim da vida útil ($dias dias) e não envia " +
                                    "mais medições. Coloque um sensor novo para voltar a medir."
                            )
                            miBandPusher.maybeSendStaleRefresh(status.minutesSinceReading()?.toInt() ?: 0)
                        }
                        SensorWarning.CHECK_SENSOR -> {
                            val min = status.minutesSinceReading() ?: 0
                            appLogger.error(LOG, "Aviso: sensor inoperante", mapOf("minutosDesde" to min))
                            notificationHelper.showSensorWarning(
                                "Verifique o sensor",
                                "Sensor sem dados há $min min. Pode estar expirado, descolado ou sem Bluetooth."
                            )
                            miBandPusher.maybeSendStaleRefresh(min.toInt())
                        }
                        SensorWarning.AGING -> {
                            val days = status.ageDays() ?: 0
                            appLogger.usage(LOG, "Aviso: sensor antigo", mapOf("dias" to days))
                            notificationHelper.cancelSensorWarning()
                        }
                    }
                }
            }.onFailure { e ->
                appLogger.error(LOG, "Erro no warningLoop",
                    mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())))
            }
            delay(60_000L)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Mapeia ig_trend (campo da .so) para seta Unicode.
     * TODO(SB01): confirmar mapeamento no device — valores negativos/positivos podem variar.
     */
    private fun trendToArrow(trend: Int): String = when {
        trend >= 3  -> "↑↑"
        trend == 2  -> "↑"
        trend == 1  -> "↗"
        trend == 0  -> "→"
        trend == -1 -> "↘"
        trend == -2 -> "↓"
        else        -> "↓↓"
    }
}
