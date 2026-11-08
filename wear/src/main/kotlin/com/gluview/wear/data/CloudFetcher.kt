package com.gluview.wear.data

import android.content.Context
import android.util.Log
import com.gluview.wear.contract.SnapshotSource
import com.gluview.wear.contract.SnapshotUnit
import com.gluview.wear.contract.SnapshotZone
import com.gluview.wear.contract.WatchFaceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Plano B (Fase 7): o relógio busca a glicemia direto no GluSync quando o celular não está
 * mandando. Usa a rota Nightscout `/api/v1/entries/sgv.json` (traz a hora exata da medição em ms e
 * a direção — o `/glucose/wear` só traz "HH:mm", que não serve para a regra de dado velho) com o
 * token de acesso que o celular entregou.
 */
object CloudFetcher {
    private const val TAG = "GluViewWear"

    sealed interface Result {
        data object Updated : Result
        data object NotNewer : Result
        data object NoToken : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun fetch(context: Context, nowMs: Long = System.currentTimeMillis()): Result = withContext(Dispatchers.IO) {
        val token = TokenStore.load(context)
        if (token == null || !token.isUsable(nowMs)) return@withContext Result.NoToken

        val conn = (URL("${token.baseUrl}/api/v1/entries/sgv.json?count=2").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer ${token.accessToken}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            when (val code = conn.responseCode) {
                200 -> Unit
                401, 403 -> { TokenStore.clear(context); return@withContext Result.NoToken }
                else -> return@withContext Result.Failed("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val previous = SnapshotStore.load(context)
            val snapshot = parse(body, previous) ?: return@withContext Result.Failed("resposta vazia")
            if (previous != null && previous.measuredAtMs >= snapshot.measuredAtMs) return@withContext Result.NotNewer
            SnapshotStore.save(context, snapshot, graphPng = null)
            Log.i(TAG, "Leitura buscada na nuvem: ${snapshot.sgvMgdl}")
            Result.Updated
        } catch (e: Exception) {
            Log.w(TAG, "Falha no plano B: ${e.message}")
            Result.Failed(e.message ?: e::class.java.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Resposta Nightscout → snapshot. Limites, unidade e formato de hora vêm da última leitura
     * mandada pelo celular (são as preferências do usuário); sem nenhuma, os padrões do GluView.
     */
    internal fun parse(json: String, previous: WatchFaceSnapshot?): WatchFaceSnapshot? {
        val arr = JSONArray(json)
        if (arr.length() == 0) return null
        val e0 = arr.getJSONObject(0)
        val sgv = e0.optDouble("sgv", Double.NaN).takeIf { !it.isNaN() }?.roundToInt() ?: return null
        val date = e0.optLong("date", 0L).takeIf { it > 0 } ?: return null
        val direction = e0.optString("direction", "NONE")
        val delta = if (arr.length() > 1) {
            val e1 = arr.getJSONObject(1)
            val d1 = e1.optLong("date", 0L)
            // Só calcula variação entre leituras próximas (até 15 min), como o resto do app.
            if (d1 > 0 && date - d1 in 1..15 * 60_000L) (sgv - e1.optDouble("sgv").roundToInt()) else null
        } else null

        val ul = previous?.urgentLowMgdl ?: 55
        val lo = previous?.lowMgdl ?: 70
        val hi = previous?.highMgdl ?: 180
        val uh = previous?.urgentHighMgdl ?: 250
        return WatchFaceSnapshot(
            sgvMgdl = sgv,
            deltaMgdl = delta,
            direction = direction,
            measuredAtMs = date,
            zone = SnapshotZone.classify(sgv, ul, lo, hi, uh, direction),
            urgentLowMgdl = ul, lowMgdl = lo, highMgdl = hi, urgentHighMgdl = uh,
            unit = previous?.unit ?: SnapshotUnit.MG_DL,
            use24h = previous?.use24h ?: true,
            source = SnapshotSource.CLOUD,
            staleAfterMin = previous?.staleAfterMin ?: WatchFaceSnapshot.DEFAULT_STALE_AFTER_MIN,
        ).takeIf { WatchFaceSnapshot.fromMap(it.toMap()) != null } // mesma validação de faixa do contrato
    }
}
