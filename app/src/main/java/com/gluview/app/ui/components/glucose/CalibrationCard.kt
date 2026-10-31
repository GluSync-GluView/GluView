package com.gluview.app.ui.components.glucose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gluview.app.R
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Tone
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MMOL_TO_MGDL = 18.0182

/** Fator ainda não lido (0) ou 1,000: o sensor está no valor de fábrica. */
internal fun isFactoryFactor(factor: Double) = factor <= 0.0 || abs(factor - 1.0) < 0.001

/**
 * Calibração por ponta de dedo, igual para Sibionics e Libre 2 (redesign visual, Fase 8). Antes
 * cada tela tinha a sua: a do Libre não mostrava a leitura, deixava "Calibrar" ativo sem leitura,
 * nunca dizia "(fábrica)" (o fator salvo é 1,0, não 0) e espremia campo e botão numa linha; a do
 * Sibionics tinha o título em ciano ilegível no tema claro e o campo sem teclado numérico.
 *
 * [rawMmol] é a leitura de fábrica (0 = ainda sem leitura); o valor digitado é mg/dL, como os
 * ViewModels esperam.
 */
@Composable
fun CalibrationCard(
    title: String,
    description: String,
    rawMmol: Double,
    factor: Double,
    onCalibrate: (Int) -> Unit,
    onReset: () -> Unit
) {
    var ref by rememberSaveable { mutableStateOf("") }
    val factory = isFactoryFactor(factor)
    val hasReading = rawMmol > 0.0
    val factorText = DecimalFormat("0.000", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(if (factory) 1.0 else factor)
    GluSection(title, description = description) {
        GluCard {
            if (hasReading) {
                val factoryMgdl = (rawMmol * MMOL_TO_MGDL).roundToInt()
                val shownMgdl = (rawMmol * (if (factory) 1.0 else factor) * MMOL_TO_MGDL).roundToInt()
                FactList(buildList {
                    add(Fact(stringResource(R.string.calibration_factory_reading), "$factoryMgdl mg/dL"))
                    if (!factory) add(Fact(stringResource(R.string.calibration_adjusted_reading), "$shownMgdl mg/dL"))
                    add(Fact(stringResource(R.string.calibration_factor),
                        if (factory) stringResource(R.string.calibration_factor_factory, factorText) else factorText))
                })
            } else {
                GluCallout(stringResource(R.string.sensorscan_waiting_reading), tone = Tone.Info)
            }
            OutlinedTextField(
                value = ref,
                onValueChange = { ref = it.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.calibration_meter_label)) },
                placeholder = { Text(stringResource(R.string.sensorscan_meter_glucose_example)) },
                suffix = { Text("mg/dL") },
                supportingText = { Text(stringResource(R.string.calibration_meter_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { ref.toIntOrNull()?.let(onCalibrate); ref = "" },
                enabled = hasReading && ref.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text(stringResource(if (factory) R.string.sensorscan_adjust else R.string.sensorscan_readjust)) }
            if (!factory) {
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sensorscan_restore_factory))
                }
            }
            if (!hasReading) {
                Text(stringResource(R.string.calibration_needs_reading), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
