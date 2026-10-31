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
 * F8 — Envio do visor para a Mi Band 6 (e demais Mi Band/Huami) DIRETO do celular, estilo WatchDrip:
 * não precisa de app no relógio — o celular fala o protocolo BLE Huami/Xiaomi e faz upload do
 * watchface, re-enviando para refletir glicemia/hora ao vivo. Coexiste com Zepp/Mi Fitness/MiNotify.
 *
 * Caminho ADITIVO e separado do envio automático de layout fixo ([com.gluview.app.watchface.miband.MiBandWatchFacePusher]),
 * que não é tocado por esta classe. Aqui: um visor desenhado livremente no editor
 * ([com.gluview.app.watchface.WatchFaceDesign]) é renderizado por [WatchFaceDesignGenerator] e
 * enviado via [MiBandUploader] (mesmo transporte BLE já validado na band real).
 */
@Singleton
class MiBand6WatchFaceApplier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val glucoseRepository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val uploader: MiBandUploader
) : WatchFaceApplier {

    override val id = "miband_huami"
    override val displayName = "Mi Band (Xiaomi/Huami)"

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
                "Configure o MAC e a auth key da sua Mi Band 6 para o envio (a chave de pareamento de 32 caracteres)."
            )
        }
        if (!isValidAuthKey(authKey)) {
            return ApplyResult.NeedsSetup("Auth key inválida — deve ter 32 caracteres hexadecimais.")
        }

        return try {
            val runtime = snapshotRuntime()
            val fw = withContext(Dispatchers.Default) {
                WatchFaceDesignGenerator(context, MiBandTemplateSpec.MI_BAND_6).generate(design, runtime)
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

    /** Snapshot único dos dados ao vivo (espelha o que WatchFaceEditorViewModel/WatchFaceManagerViewModel
     *  já expõem ao preview em StateFlow) — aqui é sob demanda, no momento de aplicar. */
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
        // Serviço/característica Huami de autenticação (fee1) — usadas também por MiBandClient
        // (camada 1, tela de teste de pareamento).
        const val HUAMI_SERVICE_FEE1 = "0000fee1-0000-1000-8000-00805f9b34fb"
        const val CHARACTERISTIC_AUTH = "00000009-0000-3512-2118-0009af100700"

        // Restrito à Mi Band 6 especificamente (não "qualquer mi_band_*"): o template/gerador por
        // trás (WatchFaceDesignGenerator, assets em miband_watchface_parts/miband6/) está
        // hardcoded pro canvas 152x240 da Mi Band 6. Aceitar outra Mi Band aqui produziria um
        // .bin incorreto sem aviso — ver achado P1 do plano 06_watchfaces_v2_arquitetura.md.
        // Função pura (sem as dependências injetadas da classe) para poder ser testada sem mock.
        fun matchesWearable(wearableId: String): Boolean = wearableId == "mi_band_6"
    }
}
