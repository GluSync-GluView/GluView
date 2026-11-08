package com.gluview.app.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.WatchFaceDao
import com.gluview.app.data.local.entity.WatchFaceEntity
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.SaveWatchfaceProjectRequestDto
import com.gluview.app.data.remote.dto.WatchfaceProjectDto
import com.gluview.app.data.remote.dto.WatchfaceStoreListingDto
import com.gluview.app.watchface.WearableDefinitionCatalog
import com.gluview.app.watchface.apply.ApplyResult
import com.gluview.app.watchface.miband.MiBandUploader
import com.gluview.app.watchface.miband.MiBandWatchfaceInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Loja fechada em beta (§21 do plano) — a conta não tem o entitlement `watchface_store` ainda. */
class StoreAccessDeniedException : Exception("Loja ainda não liberada pra esta conta (beta fechado).")

@Singleton
class WatchFaceRepository @Inject constructor(
    private val dao: WatchFaceDao,
    private val api: GluSyncApi,
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val uploader: MiBandUploader
) {
    fun observeAll(): Flow<List<WatchFaceEntity>> = dao.observeAll()
    fun observeById(id: Long): Flow<WatchFaceEntity?> = dao.observeById(id)
    suspend fun getById(id: Long): WatchFaceEntity? = dao.getById(id)
    suspend fun save(face: WatchFaceEntity): Long =
        if (face.id == 0L) dao.insert(face) else {
            dao.update(face)
            face.id
        }

    suspend fun delete(face: WatchFaceEntity) = dao.delete(face)

    suspend fun duplicate(face: WatchFaceEntity): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            face.copy(
                id = 0,
                name = "${face.name} (copia)",
                createdAt = now,
                updatedAt = now,
                applyState = "not_applied",
                // Cópia é um projeto NOVO e distinto — nunca deve herdar a identidade remota do
                // original (senão o próximo syncToServer() da cópia sobrescreveria o original).
                serverId = null
            )
        )
    }

    // ── Sincronização com o GluSync (Fase 4 do plano 06_watchfaces_v2_arquitetura.md) ──────────
    // Best-effort, mesmo padrão de CaregiverRepository/ApiKeyRepository: nunca lança, nunca
    // bloqueia o app offline. Estratégia simples (não é a máquina de estados completa do §10.2 do
    // plano): servidor sempre ganha na primeira sincronização de um projeto desconhecido
    // localmente; depois disso, quem tiver o `updatedAt` mais recente ganha (last-write-wins).

    /** Envia [face] ao servidor (cria se `serverId` for null, atualiza caso contrário). */
    suspend fun syncToServer(face: WatchFaceEntity): Boolean = runCatching {
        val req = SaveWatchfaceProjectRequestDto(
            name = face.name,
            wearableId = face.wearableId,
            wearableName = face.wearableName,
            wearableWidthPx = face.wearableWidthPx,
            wearableHeightPx = face.wearableHeightPx,
            componentsJson = face.componentsJson,
            schemaVersion = face.schemaVersion,
            compatibleWearableIdsJson = face.compatibleWearableIdsJson
        )
        val dto = if (face.serverId == null) {
            api.createWatchfaceProject(req)
        } else {
            api.updateWatchfaceProject(face.serverId, req)
        }
        dao.update(face.copy(serverId = dto.id))
        true
    }.getOrDefault(false)

    /** Puxa a biblioteca do servidor, preenchendo o que falta localmente / atualizando o que estiver desatualizado. */
    suspend fun pullFromServer() {
        runCatching {
            api.listWatchfaceProjects().forEach { dto ->
                val local = dao.getByServerId(dto.id)
                when {
                    local == null -> dao.insert(dto.toLocalEntity())
                    dto.updatedAtMs() > local.updatedAt -> dao.update(dto.toLocalEntity(id = local.id))
                    // local mais novo (ou igual): não sobrescreve — o próximo syncToServer() empurra.
                }
            }
        }
    }

    private fun WatchfaceProjectDto.toLocalEntity(id: Long = 0): WatchFaceEntity = WatchFaceEntity(
        id = id,
        name = name,
        wearableId = wearableId,
        wearableName = wearableName,
        wearableWidthPx = wearableWidthPx,
        wearableHeightPx = wearableHeightPx,
        createdAt = createdAtMs(),
        updatedAt = updatedAtMs(),
        componentsJson = componentsJson,
        schemaVersion = schemaVersion,
        serverId = this.id,
        compatibleWearableIdsJson = compatibleWearableIdsJson ?: "[]"
    )

    private fun WatchfaceProjectDto.createdAtMs(): Long =
        runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    private fun WatchfaceProjectDto.updatedAtMs(): Long =
        runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    /** Marca o pedido de envio em andamento — estado transitório até [markApplyResult]. */
    // L27: enviar não é editar — `updatedAt` fica como está (o card dizia "Atualizado agora" a cada envio).
    suspend fun markApplyRequested(face: WatchFaceEntity) {
        dao.update(face.copy(applyState = "pending"))
    }

    /**
     * Persiste o resultado REAL de [com.gluview.app.watchface.apply.WatchFaceApplierRegistry.apply]
     * (corrige o achado P4 do plano 06_watchfaces_v2_arquitetura.md: applyState ficava travado em
     * "pending_future_impl" e nunca refletia sucesso/falha de verdade).
     */
    suspend fun markApplyResult(face: WatchFaceEntity, result: ApplyResult) {
        val state = when (result) {
            is ApplyResult.Success, is ApplyResult.Delivered -> "success"
            is ApplyResult.NeedsSetup  -> "needs_setup"
            is ApplyResult.Unsupported -> "unsupported"
            is ApplyResult.InProgress  -> "in_progress"
            is ApplyResult.Failure     -> "failure"
        }
        dao.update(face.copy(applyState = state))
    }

    /**
     * Fase 6 do plano 06_watchfaces_v2_arquitetura.md — submete [face] pra revisão da loja.
     * Sincroniza primeiro se o projeto ainda não tem `serverId` (não dá pra publicar o que o
     * servidor não conhece). A loja em si (navegar/instalar de outros usuários) não tem tela no
     * app ainda — fica no GluWebView por enquanto (WatchfaceStorePage.tsx).
     */
    suspend fun publishToStore(face: WatchFaceEntity): Result<String> = runCatching {
        val serverId = face.serverId ?: run {
            if (!syncToServer(face)) error("Falha ao sincronizar com o GluSync antes de publicar")
            dao.getById(face.id)?.serverId ?: error("serverId ausente mesmo após sincronizar")
        }
        val response = api.publishWatchfaceProject(serverId)
        if (!response.isSuccessful) error("GluSync respondeu HTTP ${response.code()}")
        response.body()?.publicationId ?: error("Resposta vazia do GluSync")
    }

    // ── Loja: navegar/instalar (Fase 6 do plano 06_watchfaces_v2_arquitetura.md) ────────────────
    // Só existiam publicação (acima) e navegação/instalação pelo GluWebView até aqui — isto fecha
    // o loop dentro do próprio app: buscar a vitrine e instalar direto num Mi Band pareado, sem
    // passar pelo navegador. Sempre baixa o .bin recompilado pelo servidor (§9.2/§15.1 do plano) —
    // nunca reconstrói localmente, mesmo que o app soubesse desenhar aquele projeto.

    /** [StoreAccessDeniedException] quando a conta não tem o entitlement da loja (403). */
    suspend fun listStore(search: String? = null): Result<List<WatchfaceStoreListingDto>> = runCatching {
        val response = api.listWatchfaceStore(search)
        if (response.code() == 403) throw StoreAccessDeniedException()
        if (!response.isSuccessful) error("GluSync respondeu HTTP ${response.code()}")
        response.body() ?: emptyList()
    }

    /** Wearables que a LOJA sabe instalar hoje: só Mi Band (BLE). Relógio Wear OS recebe visor desenhado
     *  no app (WFF pelo Data Layer), não o artefato .bin da loja. */
    fun isInstallableWearable(wearableId: String): Boolean = WearableDefinitionCatalog.isStoreInstallable(wearableId)

    /** Baixa o artefato oficial da loja pro wearable escolhido e envia por Bluetooth pro Mi Band pareado. */
    suspend fun installFromStore(listing: WatchfaceStoreListingDto, wearableId: String, onLog: (String) -> Unit): ApplyResult {
        if (!hasBtConnectPermission()) {
            return ApplyResult.NeedsSetup("Conceda a permissão de Bluetooth para instalar visores da loja.")
        }
        val mac = preferences.miBandMac.first().trim()
        val authKey = preferences.miBandAuthKey.first().trim()
        if (mac.isBlank() || authKey.isBlank()) {
            return ApplyResult.NeedsSetup("Configure o MAC e a auth key da sua Mi Band para instalar visores da loja.")
        }
        if (!isValidAuthKey(authKey)) {
            return ApplyResult.NeedsSetup("Auth key inválida — deve ter 32 caracteres hexadecimais.")
        }

        return try {
            val response = api.downloadWatchfaceStoreArtifact(listing.publicationId, wearableId)
            if (!response.isSuccessful) {
                return ApplyResult.Failure(
                    if (response.code() == 404) "Artefato ainda não compilado pra este wearable — tente de novo em instantes."
                    else "GluSync respondeu HTTP ${response.code()}"
                )
            }
            val bytes = response.body()?.bytes() ?: return ApplyResult.Failure("Resposta vazia do GluSync")
            val installer = MiBandWatchfaceInstaller(uploader, mac, parseHex(authKey))
            val ok = installer.install(bytes, onLog)
            if (ok) ApplyResult.Success else ApplyResult.Failure("Falha ao enviar por Bluetooth")
        } catch (e: Exception) {
            ApplyResult.Failure(e.message ?: "erro desconhecido ao instalar")
        }
    }

    private fun hasBtConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun isValidAuthKey(key: String): Boolean {
        val k = key.removePrefix("0x")
        return k.length == 32 && k.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    private fun parseHex(hex: String): ByteArray {
        val k = hex.removePrefix("0x")
        return ByteArray(k.length / 2) { i -> ((k[i * 2].digitToInt(16) shl 4) + k[i * 2 + 1].digitToInt(16)).toByte() }
    }
}
