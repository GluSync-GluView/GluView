package com.gluview.app.ui.screen.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.gluview.app.R
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.formatDate
import com.gluview.app.util.formatTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Número digitado com vírgula ou ponto decimal ("4,5" ou "4.5"); null se não for número. */
internal fun parseDecimalInput(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** Campo numérico (vírgula ou ponto). */
@Composable
internal fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onValueChange(new.filter { it.isDigit() || it == ',' || it == '.' }) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = isError,
        modifier = modifier.fillMaxWidth()
    )
}

/** Data e hora do registro (agora por padrão; dá para lançar algo que aconteceu antes). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateTimeRow(
    epochMs: Long,
    timeFormat: TimeFormat,
    onChange: (Long) -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val dt = Instant.ofEpochMilli(epochMs).atZone(zone)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.CalendarToday, null)
            Text(" " + formatDate(epochMs, Locale.getDefault(), zone))
        }
        OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Schedule, null)
            Text(" " + formatTime(epochMs, timeFormat, zone))
        }
    }

    if (pickTime) {
        val state = rememberTimePickerState(dt.hour, dt.minute, is24Hour = timeFormat == TimeFormat.H24)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(dt.with(LocalTime.of(state.hour, state.minute)).toInstant().toEpochMilli())
                    pickTime = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.common_cancel)) } },
            text = { TimePicker(state = state) }
        )
    }
    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val day = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(dateWithTime(day, dt.toLocalTime(), zone))
                    }
                    pickDate = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } }
        ) { DatePicker(state = state) }
    }
}

internal fun dateWithTime(day: LocalDate, time: LocalTime, zone: ZoneId): Long =
    day.atTime(time).atZone(zone).toInstant().toEpochMilli()
