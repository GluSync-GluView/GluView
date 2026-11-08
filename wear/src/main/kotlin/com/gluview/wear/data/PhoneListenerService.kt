package com.gluview.wear.data

import android.util.Log
import com.gluview.wear.contract.WatchFaceSnapshot
import com.gluview.wear.contract.WearPaths
import com.gluview.wear.contract.WearToken
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Recebe do celular (Fase 5): a leitura em [WearPaths.READING] (DataItem com o mapa do contrato e o
 * PNG do minigráfico como Asset) e o token em [WearPaths.TOKEN] (mensagem).
 *
 * O Google Play Services chama isto numa thread de fundo e só segura o processo até o método
 * voltar, por isso o `runBlocking` curto para baixar o Asset.
 */
class PhoneListenerService : WearableListenerService() {
    private val tag = "GluViewWear"

    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearPaths.READING }
            .forEach { event ->
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val snapshot = WatchFaceSnapshot.fromMap(dataMap.toPlainMap())
                if (snapshot == null) {
                    // Formato inválido ou de versão futura: não mostra nada em vez de mostrar errado.
                    Log.w(tag, "Leitura recusada (formato inválido)")
                    return@forEach
                }
                val graph = dataMap.getAsset(WearPaths.ASSET_GRAPH)?.let { readAsset(it) }
                SnapshotStore.save(this, snapshot, graph)
                Log.i(tag, "Leitura recebida: ${snapshot.sgvMgdl} medida em ${snapshot.measuredAtMs}")
            }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.TOKEN) return
        val token = WearToken.decode(event.data) ?: return
        if (token.accessToken.isBlank()) TokenStore.clear(this) else TokenStore.save(this, token)
        CloudFetchWorker.schedule(this)
    }

    private fun readAsset(asset: Asset): ByteArray? = runCatching {
        runBlocking {
            withTimeoutOrNull(10_000) {
                Wearable.getDataClient(this@PhoneListenerService).getFdForAsset(asset).await()
                    .inputStream.use { it.readBytes() }
            }
        }
    }.getOrNull()
}

/** DataMap → mapa simples com os tipos que o contrato usa (Int, Long, Boolean, String). */
internal fun DataMap.toPlainMap(): Map<String, Any?> = keySet().associateWith { key ->
    @Suppress("DEPRECATION")
    when (val v = get<Any?>(key)) {
        is Int, is Long, is Boolean, is String -> v
        else -> null
    }
}
