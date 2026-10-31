package com.gluview.app.data.sensor

import com.gluview.app.data.sibionics.SibionicsBleManager
import com.gluview.app.util.AppLogger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encontra o sensor físico que corresponde à embalagem escaneada.
 *
 * Substitui a escolha manual de dispositivo: em vez de listar tudo que está no ar e esperar o
 * usuário acertar, procura ativamente o rádio cujo nome anunciado casa com o código da caixa (ver
 * [SensorIdentityMatcher]) e devolve só ele.
 *
 * Regras de custo/segurança:
 *  - janela fechada de [DISCOVERY_WINDOW_MS] (mesmo timeout do app oficial), nunca scan perpétuo;
 *  - o scan para assim que a identidade casa, sem esperar o fim da janela;
 *  - uma descoberta por vez ([mutex]) — dois toques em "procurar" não viram dois scans;
 *  - o scan é sempre encerrado no `finally`, inclusive em cancelamento/timeout.
 */
@Singleton
class SensorDiscovery @Inject constructor(
    private val ble: SibionicsBleManager,
    private val appLogger: AppLogger
) {

    /** Dispositivo que passou na verificação de identidade. */
    data class Found(val name: String, val mac: String, val rssi: Int)

    private val mutex = Mutex()

    /**
     * Procura o sensor de [code] usando [matcher].
     *
     * @return o dispositivo correspondente, ou null se a janela fechar sem nenhum casamento
     *         (sensor longe, desligado, Bluetooth indisponível ou caixa de outro sensor).
     */
    suspend fun findSensor(
        code: SensorCode,
        matcher: SensorIdentityMatcher,
        windowMs: Long = DISCOVERY_WINDOW_MS
    ): Found? = mutex.withLock {
        if (!code.isValid) {
            appLogger.error(LOG, "Descoberta abortada — código da embalagem inválido")
            return@withLock null
        }

        appLogger.usage(LOG, "Procurando sensor da embalagem",
            mapOf("janelaSegundos" to windowMs / 1000))

        // Candidatos acumulados na janela. Só entram os que JÁ passaram pela identidade, então
        // "melhor candidato" nunca é um dispositivo alheio — no pior caso são dois rádios com o
        // mesmo código (não observado em campo), e aí o sinal mais forte é o que está no corpo.
        val candidates = LinkedHashMap<String, Found>()

        val result = try {
            withTimeoutOrNull(windowMs) {
                ble.startScan { name, mac, rssi ->
                    if (matcher.matches(name, code)) {
                        candidates[mac] = Found(name = name.orEmpty(), mac = mac, rssi = rssi)
                    }
                }
                // Espera curta após o primeiro casamento: dá chance de um segundo anúncio chegar
                // (RSSI mais estável) sem custar a janela inteira.
                var settleMs = 0L
                while (candidates.isEmpty() || settleMs < SETTLE_MS) {
                    delay(POLL_MS)
                    if (candidates.isNotEmpty()) settleMs += POLL_MS
                }
                candidates.values.maxByOrNull { it.rssi }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            ble.stopScan()
        }

        if (result == null) {
            appLogger.error(LOG, "Sensor da embalagem não encontrado na janela de busca",
                mapOf("candidatos" to candidates.size))
        } else {
            appLogger.usage(LOG, "Sensor da embalagem encontrado",
                mapOf("mac" to result.mac, "rssi" to result.rssi,
                      "candidatos" to candidates.size))
        }
        result
    }

    private companion object {
        const val LOG = "SENSOR_DISCOVERY"

        /** Janela de busca — 60 s, igual ao `LocalBleService.A()` do app oficial. */
        const val DISCOVERY_WINDOW_MS = 60_000L

        /** Intervalo de verificação dos candidatos acumulados. */
        const val POLL_MS = 250L

        /** Tempo de acomodação após o primeiro casamento, para comparar RSSI de forma justa. */
        const val SETTLE_MS = 1_500L
    }
}
