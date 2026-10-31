package com.gluview.app.data.repository

import com.gluview.app.data.local.dao.SensorHistoryDao
import com.gluview.app.data.local.entity.SensorHistoryEntity
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.SensorCalibrationRequestDto
import com.gluview.app.data.remote.dto.SensorHistoryDto
import com.gluview.app.data.remote.dto.SensorHistoryStartRequestDto
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Histórico de sensores — mesmo modelo do histórico de glicemia: log local (Room, retenção de
 * 3 meses) espelhado no GluSync (retenção de 1 ano, gated no servidor). A sincronização com a
 * nuvem é best-effort (falha silenciosa — reenviada no próximo flush) já que o histórico local
 * é a fonte de verdade para a UI do app.
 */
@Singleton
class SensorHistoryRepository @Inject constructor(
    private val dao: SensorHistoryDao,
    private val api: GluSyncApi
) {
    fun observeActive(): Flow<SensorHistoryEntity?> = dao.observeActive()
    fun observeAll(): Flow<List<SensorHistoryEntity>> = dao.observeAll()

    /** Fecha o sensor ativo (se houver) e registra um novo — chamado ao salvar um sensor DIFERENTE. */
    suspend fun startNew(mac: String, serial: String?, customerId: String?, source: String) {
        val now = System.currentTimeMillis()
        dao.endOpenEntries(now)
        val id = dao.insert(
            SensorHistoryEntity(mac = mac, serial = serial, customerId = customerId, source = source, startedAt = now)
        )
        // isSuccessful é OBRIGATÓRIO aqui: o Retrofit só lança quando o retorno NÃO é Response<T>,
        // então um 401/400/500 chegava como "sucesso" do runCatching e o registro era marcado como
        // sincronizado sem nunca ter chegado ao servidor — e, como o flushPending só reenvia o que
        // está pendente, o sensor ficava perdido pra sempre (some do portal, fica só no aparelho).
        if (postSensorStart(mac, serial, customerId, source)) dao.markSynced(id)
    }

    /** Envia um período de sensor; true só quando o servidor confirmou (2xx). Com startedAt/endedAt
     *  o servidor trata como reenvio de histórico (preserva as datas e não encerra o sensor ativo). */
    private suspend fun postSensorStart(
        mac: String, serial: String?, customerId: String?, source: String,
        startedAtMs: Long? = null, endedAtMs: Long? = null
    ): Boolean =
        runCatching {
            api.startSensorHistory(
                SensorHistoryStartRequestDto(
                    mac, serial, customerId, source,
                    startedAt = startedAtMs?.let { Instant.ofEpochMilli(it).toString() },
                    endedAt = endedAtMs?.let { Instant.ofEpochMilli(it).toString() }
                )
            )
        }.getOrNull()?.isSuccessful == true

    /**
     * Reenvia ao GluSync os períodos que existem no aparelho mas não no servidor.
     *
     * Diferente do flushPending, NÃO confia na flag syncedToCloud: ela é justamente o que ficou
     * errado (um bug antigo marcava como sincronizado sem o servidor ter aceitado — ver startNew),
     * então períodos perdidos aparecem como "já enviados" e nunca seriam reenviados. Aqui a
     * comparação é com o que o servidor REALMENTE tem, por (mac, início) — a mesma chave natural
     * que o servidor usa pra ser idempotente, então repetir a reconciliação não duplica nada.
     *
     * Silencioso por design: é uma manutenção de fundo, não deve interromper o uso do app.
     */
    suspend fun reconcileWithServer() {
        val remote = runCatching { api.getSensorHistory(limit = 200) }.getOrNull() ?: return
        val remoteKeys = remote.mapNotNull { dto ->
            val startMs = runCatching { Instant.parse(dto.startedAt).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            dto.mac to startMs
        }.toSet()

        for (local in dao.getAll()) {
            // Tolerância de 1s na comparação: o servidor guarda em microssegundos e o round-trip
            // pode não devolver o mesmo milissegundo exato que saiu daqui.
            val jaExiste = remoteKeys.any { (mac, startMs) ->
                mac == local.mac && kotlin.math.abs(startMs - local.startedAt) <= 1000
            }
            if (jaExiste) continue

            val ok = postSensorStart(
                local.mac, local.serial, local.customerId, local.source,
                startedAtMs = local.startedAt, endedAtMs = local.endedAt
            )
            // Para no primeiro erro (sem rede/sessão): o resto é tentado na próxima execução, em
            // vez de queimar tentativas uma a uma contra um servidor que já recusou.
            if (!ok) break
            dao.markSynced(local.id)
        }
    }

    /** Encerra o sensor ativo sem abrir um novo — "esquecer configuração". */
    suspend fun endActive() {
        dao.endOpenEntries(System.currentTimeMillis())
        runCatching { api.endSensorHistory() }
    }

    /**
     * Grava o fator de calibração — SEMPRE local primeiro (funciona mesmo sem rede/erro no
     * GluSync), e best-effort no servidor (cópia de segurança, sobrevive a limpeza de dados
     * do app). Chamado toda vez que o usuário recalibra.
     */
    suspend fun updateCalibration(factor: Double) {
        dao.updateActiveCalibration(factor)
        runCatching { api.updateSensorCalibration(SensorCalibrationRequestDto(factor)) }
    }

    /**
     * Consulta o sensor ATIVO no GluSync — usado quando o app não tem período aberto local
     * (ex.: após limpar dados/reinstalar), pra reconciliar mac/serial/customerId e recuperar
     * o fator de calibração sem precisar recalibrar do zero.
     */
    suspend fun fetchActiveFromServer(): SensorHistoryDto? =
        runCatching { api.getActiveSensorHistory() }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    /** Espelha localmente um período já aberto no servidor (reconciliação pós-limpeza), sem
     *  reabrir/fechar nada no GluSync — só sincroniza o lado local. */
    suspend fun adoptFromServer(remote: SensorHistoryDto) {
        dao.insert(
            SensorHistoryEntity(
                mac = remote.mac, serial = remote.serial, customerId = remote.customerId,
                source = remote.source, startedAt = System.currentTimeMillis(),
                syncedToCloud = true, calibrationFactor = remote.calibrationFactor
            )
        )
    }

    /** Reenvia ao GluSync entradas que ainda não sincronizaram (app esteve offline). */
    suspend fun flushPending() {
        for (entry in dao.getUnsynced()) {
            // Mesmo motivo do startNew: `.isSuccess` do runCatching ignorava o código HTTP, então
            // uma resposta de erro marcava como sincronizado e a entrada saía da fila sem ter sido
            // gravada — justamente o oposto do que um "flush de pendências" deve fazer.
            val ok = postSensorStart(entry.mac, entry.serial, entry.customerId, entry.source)
            if (ok) dao.markSynced(entry.id) else break
        }
    }

    suspend fun pruneOld(retentionDays: Int = 90) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        dao.deleteOlderThan(cutoff)
    }
}
