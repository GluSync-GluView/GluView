package com.gluview.app.watchface.apply

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.ui.screen.watchface.WatchFaceRuntimeData
import com.gluview.app.util.applyDisplayMode
import com.gluview.app.util.toGraphReading
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.watchface.miband.MiBandTemplateSpec
import com.gluview.app.watchface.miband.MiBandUploader
import com.gluview.app.watchface.miband.MiBandWatchfaceInstaller
import com.gluview.app.watchface.miband.WatchFaceDesignGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fase 5 do plano 06_watchfaces_v2_arquitetura.md — segundo [WatchFaceApplier] real, provando que
 * a extração da Fase 3 ([com.gluview.app.watchface.miband.MiBandCanvasCompiler]/
 * [com.gluview.app.watchface.miband.MiBandPackageBuilder]) generaliza pra outro wearable sem
 * mudar nenhuma linha desses dois — só o [MiBandTemplateSpec.MI_BAND_5] muda.
 *
 * Deliberadamente uma CÓPIA de [MiBand6WatchFaceApplier], não uma classe base compartilhada — mesmo
 * princípio já documentado em [WatchFaceDesignGenerator] e [com.gluview.app.watchface.miband.MiBandPackageBuilder]:
 * a Mi Band 6 já é hardware-validada, uma abstração nova arrisca as duas ao mesmo tempo por uma
 * economia de poucas linhas. Reaproveita [MiBandUploader] (transporte BLE) e as MESMAS
 * `preferences.miBandMac`/`miBandAuthKey` (um usuário pareia um Huami por vez, mesmo padrão de hoje).
 *
 * **Não testado em hardware real** — o mantenedor não tem uma Mi Band 5 (só a 6). O que está
 * verificado por evidência: o template `.bin` (`_research/wd_src/.../miband5/watchface.bin`) tem a
 * MESMA assinatura de cabeçalho `UIHH\1\0` da Mi Band 6, e o WatchDrip original trata MI_BAND5 e
 * MI_BAND6 com os MESMOS flags de auth/cripto "alternativos" (`useAlternativeAuthFlag`/
 * `useAlternativeCryptFlag` em `MiBandType.java`) — o mesmo fix já validado em hardware pra Mi Band
 * 6 (ver notas internas do projeto) deveria valer aqui sem mudança. Resolução usada (126×170) foi lida
 * do cabeçalho IHDR real do `canvas.png`, não copiada de ficha técnica — o valor antes catalogado
 * em `WearableCatalog` (126×294) estava errado, corrigido junto com esta mudança.
 */
@Singleton
class MiBand5WatchFaceApplier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val glucoseRepository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val uploader: MiBandUploader
) : WatchFaceApplier {

    override val id = "miband5_huami"
    override val displayName = "Mi Band 5 (Xiaomi/Huami)"

    override fun supports(design: WatchFaceDesign): Boolean =
        matchesWearable(design.wearable.id)

    override suspend fun apply(design: WatchFaceDesign): ApplyResult {
        if (!hasBtConnectPermission()) {
            return ApplyResult.NeedsSetup("Conceda a permissão de Bluetooth para enviar visores à Mi Band.")
        }
        val mac = preferences.miBandMac.first().trim()
        val authKey = preferences.miBandAuthKey.first().trim()
        if (mac.isBlank() || authKey.isBlank()) {
            return ApplyResult.NeedsSetup(
                "Configure o MAC e a auth key da sua Mi Band 5 para o envio (a chave de pareamento de 32 caracteres)."
            )
        }
        if (!isValidAuthKey(authKey)) {
            return ApplyResult.NeedsSetup("Auth key inválida — deve ter 32 caracteres hexadecimais.")
        }

        return try {
            val runtime = snapshotRuntime()
            val fw = withContext(Dispatchers.Default) {
                WatchFaceDesignGenerator(context, MiBandTemplateSpec.MI_BAND_5).generate(design, runtime)
            }
            val installer = MiBandWatchfaceInstaller(uploader, mac, parseHex(authKey))
            var lastLog = ""
            val ok = withContext(Dispatchers.IO) {
                installer.install(fw) { lastLog = it }
            }
            if (ok) ApplyResult.Success
            else ApplyResult.Failure(lastLog.ifBlank { "sem detalhes" })
        } catch (e: Exception) {
            ApplyResult.Failure(e.message ?: "erro desconhecido ao gerar/enviar o visor")
        }
    }

    private suspend fun snapshotRuntime(): WatchFaceRuntimeData {
        val reading = glucoseRepository.currentReading.value ?: glucoseRepository.latestFromDb.firstOrNull()
        val calFactor = preferences.sibionicsCalFactor.first()
        val fromMs = System.currentTimeMillis() - 3 * 3_600_000L
        // L11: modo de exibição do Sibionics também no visor.
        val mode = com.gluview.app.data.sibionics.GlucoseDisplayMode.fromId(preferences.glucoseDisplayMode.first())
        val graphReadings = glucoseRepository.getRecentReadingsForNotification(fromMs).map { it.toGraphReading(calFactor) }
            .applyDisplayMode(mode)
        return WatchFaceRuntimeData(
            nowMs = System.currentTimeMillis(),
            reading = reading,
            graphReadings = graphReadings,
            sensorStatus = statusHolder.status.value,
            hipo = preferences.lowThreshold.first(),
            hiper = preferences.highThreshold.first(),
            urgentLow = preferences.urgentLowThreshold.first(),
            urgentHigh = preferences.urgentHighThreshold.first(),
            timeFormat = com.gluview.app.util.TimeFormat.fromString(preferences.timeFormat.first()),
            batteryPercent = com.gluview.app.util.PhoneBattery.currentPercent(context),
            unit = com.gluview.app.util.GlucoseUnit.fromString(preferences.glucoseUnit.first())
        )
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

    companion object {
        fun matchesWearable(wearableId: String): Boolean = wearableId == "mi_band_5"
    }
}
