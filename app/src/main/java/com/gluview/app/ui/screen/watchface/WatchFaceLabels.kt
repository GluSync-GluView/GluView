package com.gluview.app.ui.screen.watchface

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gluview.app.R
import com.gluview.app.watchface.WatchFaceComponent
import com.gluview.app.watchface.WatchFacePreset
import com.gluview.app.watchface.WatchFaceTextAlign
import com.gluview.app.watchface.WearableCatalog
import com.gluview.app.watchface.WearableKind
import com.gluview.app.watchface.WearableShape
import com.gluview.app.watchface.WearableSpec

/*
 * Nomes de exibição dos visores no idioma do app (redesign visual, Fase 9). O domínio guarda
 * rótulos em pt ("Grafico de glicemia V3") e o `label` gravado no JSON NÃO muda — ele é desenhado
 * na pulseira no estilo "labelled". A tela lê o nome pelo typeId, como o portal (componentNameKey).
 */

@StringRes
fun componentNameRes(typeId: String): Int = when (typeId) {
    "time_hms" -> R.string.wfc_time_hms
    "time_hm" -> R.string.wfc_time_hm
    "seconds" -> R.string.wfc_seconds
    "date_dm" -> R.string.wfc_date_dm
    "date_dmy" -> R.string.wfc_date_dmy
    "date_ymd" -> R.string.wfc_date_ymd
    "date_md" -> R.string.wfc_date_md
    "month" -> R.string.wfc_month
    "year" -> R.string.wfc_year
    "weekday" -> R.string.wfc_weekday
    "glucose_current" -> R.string.wfc_glucose_current
    "glucose_delta" -> R.string.wfc_glucose_delta
    "glucose_unit" -> R.string.wfc_glucose_unit
    "last_reading_time" -> R.string.wfc_last_reading_time
    "reading_age" -> R.string.wfc_reading_age
    "target_range" -> R.string.wfc_target_range
    "hipo_alert" -> R.string.wfc_hipo_alert
    "hiper_alert" -> R.string.wfc_hiper_alert
    "fixed_text" -> R.string.wfc_fixed_text
    "sensor_life" -> R.string.wfc_sensor_life
    "phone_battery" -> R.string.wfc_phone_battery
    "glucose_chart" -> R.string.wfc_glucose_chart
    "sensor_connected" -> R.string.wfc_sensor_connected
    "insulin_iob" -> R.string.wfc_insulin_iob
    "insulin_basal_rate" -> R.string.wfc_insulin_basal_rate
    "insulin_last_bolus" -> R.string.wfc_insulin_last_bolus
    "insulin_reservoir" -> R.string.wfc_insulin_reservoir
    "insulin_pump_battery" -> R.string.wfc_insulin_pump_battery
    "insulin_pump_connected" -> R.string.wfc_insulin_pump_connected
    else -> R.string.wfc_unknown
}

/** Tipos da bomba: ainda sem dado ("--"), marcados como beta na biblioteca. */
fun isPumpComponent(typeId: String) = typeId.startsWith("insulin_")

/** "Glicemia atual · Estilo 2" (antes "Glicemia atual V2", em pt mesmo no en). */
@Composable
fun componentDisplayName(component: WatchFaceComponent): String =
    stringResource(R.string.wf_component_style, stringResource(componentNameRes(component.typeId)), component.variation)

@Composable
fun presetName(preset: WatchFacePreset): String = when (preset.id) {
    "watchdrip_classic" -> stringResource(R.string.wfp_classic_name)
    "glucose_focus" -> stringResource(R.string.wfp_focus_name)
    "complete" -> stringResource(R.string.wfp_complete_name)
    "minimal" -> stringResource(R.string.wfp_minimal_name)
    "daily_complete" -> stringResource(R.string.wfp_daily_name)
    "debug_all_types" -> stringResource(R.string.wfp_debug_name)
    else -> preset.name
}

@Composable
fun presetDescription(preset: WatchFacePreset): String = when (preset.id) {
    "watchdrip_classic" -> stringResource(R.string.wfp_classic_desc)
    "glucose_focus" -> stringResource(R.string.wfp_focus_desc)
    "complete" -> stringResource(R.string.wfp_complete_desc)
    "minimal" -> stringResource(R.string.wfp_minimal_desc)
    "daily_complete" -> stringResource(R.string.wfp_daily_desc)
    "debug_all_types" -> stringResource(R.string.wfp_debug_desc)
    else -> preset.description
}

/** Modelo só para validar a geração na band real: fica no fim, com selo "Teste" (decisão 5.6 #3). */
fun isTestPreset(preset: WatchFacePreset) = preset.id == "debug_all_types"

@Composable
fun shapeLabel(shape: WearableShape): String = stringResource(when (shape) {
    WearableShape.RECTANGLE -> R.string.wf_shape_rectangle
    WearableShape.ROUND -> R.string.wf_shape_round
    WearableShape.SQUIRCLE -> R.string.wf_shape_squircle
})

@Composable
fun kindLabel(kind: WearableKind): String = stringResource(when (kind) {
    WearableKind.SMARTWATCH -> R.string.wf_kind_watch
    WearableKind.SMARTBAND -> R.string.wf_kind_band
})

/** "Xiaomi Mi Smart Band 6 · 152 × 486 px" (antes "Xiaomi Mi Smart Band 6 (152x486)"). */
@Composable
fun wearableLabel(spec: WearableSpec): String =
    if (spec.id.startsWith("custom_")) stringResource(R.string.wf_wearable_custom, spec.widthPx, spec.heightPx)
    else stringResource(R.string.wf_wearable_label, "${spec.brand} ${spec.name}".trim(), spec.widthPx, spec.heightPx)

/** Rótulo de um wearable pelo id (loja e diálogo de envio); [fallback] quando o catálogo não conhece. */
@Composable
fun wearableLabelById(id: String, fallback: String = id): String =
    WearableCatalog.items.firstOrNull { it.id == id }?.let { wearableLabel(it) } ?: fallback

@Composable
fun fontLabel(font: String): String = when (font) {
    "Sans" -> stringResource(R.string.wf_font_sans)
    "Serif" -> stringResource(R.string.wf_font_serif)
    "Mono" -> stringResource(R.string.wf_font_mono)
    "Condensed" -> stringResource(R.string.wf_font_condensed)
    else -> stringResource(R.string.wf_font_default)
}

@Composable
fun alignLabel(align: WatchFaceTextAlign): String = stringResource(when (align) {
    WatchFaceTextAlign.START -> R.string.wf_align_start
    WatchFaceTextAlign.CENTER -> R.string.wf_align_center
    WatchFaceTextAlign.END -> R.string.wf_align_end
})

/** Paleta do seletor de cor, com nome (o TalkBack dizia só "botão"). */
val WATCHFACE_COLORS: List<Pair<Long, Int>> = listOf(
    0xFFFFFFFFL to R.string.wf_color_white,
    0xFF69F0AEL to R.string.wf_color_green,
    0xFF64B5F6L to R.string.wf_color_blue,
    0xFFFFD54FL to R.string.wf_color_yellow,
    0xFFFF8A65L to R.string.wf_color_orange,
    0xFFE57373L to R.string.wf_color_red,
    0xFFBA68C8L to R.string.wf_color_purple,
    0xFF4DD0E1L to R.string.wf_color_cyan,
    0xFFAED581L to R.string.wf_color_lime,
    0xFFFFB74DL to R.string.wf_color_amber,
    0xFF000000L to R.string.wf_color_black
)

/** Seta da simulação descrita em palavras (o chip mostrava só "↗"). */
@StringRes
fun arrowDescriptionRes(arrow: String): Int = when (arrow) {
    "↑↑" -> R.string.wf_arrow_up_fast
    "↑" -> R.string.wf_arrow_up
    "↗" -> R.string.wf_arrow_up_slow
    "→" -> R.string.wf_arrow_flat
    "↘" -> R.string.wf_arrow_down_slow
    "↓" -> R.string.wf_arrow_down
    "↓↓" -> R.string.wf_arrow_down_fast
    else -> R.string.wf_arrow_flat
}
