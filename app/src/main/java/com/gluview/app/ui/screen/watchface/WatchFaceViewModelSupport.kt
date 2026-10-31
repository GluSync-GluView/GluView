package com.gluview.app.ui.screen.watchface

import android.content.Context
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.entity.WatchFaceEntity
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.repository.WatchFaceRepository
import com.gluview.app.domain.model.GlucoseThresholds
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.PhoneBattery
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.UiText
import com.gluview.app.util.toGraphReading
import com.gluview.app.watchface.WearableCatalog
import com.gluview.app.watchface.apply.ApplyResult
import com.gluview.app.watchface.apply.WatchFaceApplierRegistry
import com.gluview.app.watchface.resolvedFor
import com.gluview.app.watchface.toDesign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay

private data class RuntimeExtras(val limits: GlucoseThresholds, val timeFormat: String, val unit: String)

/**
 * Composição de [WatchFaceRuntimeData] compartilhada entre [WatchFaceEditorViewModel] e
 * [WatchFaceManagerViewModel] — antes duplicada byte a byte nos dois (achado P5 do plano
 * 06_watchfaces_v2_arquitetura.md). Mesma lógica de antes, só extraída para um lugar só (agora
 * também lê a bateria real do celular a cada tick, para o componente PhoneBatteryKind).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun watchFaceRuntimeFlow(
    scope: CoroutineScope,
    context: Context,
    glucoseRepository: GlucoseRepository,
    statusHolder: SibionicsStatusHolder,
    preferences: AppPreferences
): StateFlow<WatchFaceRuntimeData> {
    val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000L)
        }
    }
    return combine(
        clock,
        glucoseRepository.currentReading,
        preferences.sibionicsCalFactor.flatMapLatest { calFactor ->
            val fromMs = System.currentTimeMillis() - 3 * 3_600_000L
            glucoseRepository.observeRecentReadings(fromMs)
                .map { rows -> rows.map { it.toGraphReading(calFactor) } }
        },
        statusHolder.status,
        combine(
            preferences.glucoseThresholds, preferences.timeFormat, preferences.glucoseUnit
        ) { limits, tf, unit -> RuntimeExtras(limits, tf, unit) }
    ) { now, reading, graphRows, sensorStatus, extras ->
        WatchFaceRuntimeData(
            now, reading, graphRows, sensorStatus, extras.limits.low, extras.limits.high,
            extras.limits.urgentLow, extras.limits.urgentHigh,
            TimeFormat.fromString(extras.timeFormat), PhoneBattery.currentPercent(context),
            GlucoseUnit.fromString(extras.unit)
        )
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        WatchFaceRuntimeData(System.currentTimeMillis(), null, emptyList(), statusHolder.status.value)
    )
}

/** Ação oferecida junto da mensagem (snackbar). */
enum class WatchFaceMessageAction { SETUP_MIBAND, UNDO }

/** Mensagem para a tela no idioma do app (antes texto pronto em pt, às vezes com exceção crua). */
data class WatchFaceMessage(val text: UiText, val action: WatchFaceMessageAction? = null)

/**
 * Frase de cada desfecho de [ApplyResult] — antes o texto em pt dos appliers ia direto para o
 * snackbar. "Precisa configurar" oferece abrir a Mi Band. O detalhe da falha continua visível
 * (é o que ajuda a entender o problema na band real).
 */
fun ApplyResult.toMessage(): WatchFaceMessage = when (this) {
    is ApplyResult.Success     -> WatchFaceMessage(UiText.Res(R.string.wf_msg_sent))
    is ApplyResult.NeedsSetup  -> WatchFaceMessage(UiText.Res(R.string.wf_msg_needs_setup), WatchFaceMessageAction.SETUP_MIBAND)
    is ApplyResult.Unsupported -> WatchFaceMessage(UiText.Res(R.string.wf_msg_unsupported))
    is ApplyResult.InProgress  -> WatchFaceMessage(UiText.Res(R.string.wf_msg_in_progress))
    is ApplyResult.Failure     -> WatchFaceMessage(UiText.Res(R.string.wf_msg_failed, message))
}

/**
 * Pede a aplicação de [face] ao wearable e persiste o resultado REAL em `applyState` (corrige o
 * achado P4 — antes ficava travado em "pending_future_impl", nunca refletindo sucesso/falha).
 * Devolve o desfecho (a tela traduz com [toMessage]).
 *
 * [targetWearableId] escolhe QUAL wearable recebe o envio, entre o principal e os compatíveis
 * declarados (Fase 5 — WatchfaceCompatibility). `null`/igual ao principal = comportamento de
 * sempre. Diferente do principal: o design é recalculado para a resolução do alvo via
 * `resolvedFor` antes de compilar/enviar — o alvo nunca herda posições da resolução errada.
 */
suspend fun WatchFaceRepository.applyAndPersist(
    face: WatchFaceEntity,
    applierRegistry: WatchFaceApplierRegistry,
    targetWearableId: String? = null
): ApplyResult {
    markApplyRequested(face)
    val design = face.toDesign()
    val target = targetWearableId?.takeIf { it != design.wearable.id }
        ?.let { WearableCatalog.find(it) }
    val result = applierRegistry.apply(if (target != null) design.resolvedFor(target) else design)
    markApplyResult(face, result)
    return result
}
