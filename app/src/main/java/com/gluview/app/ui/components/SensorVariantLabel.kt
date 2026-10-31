package com.gluview.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gluview.app.R
import com.gluview.app.data.source.SensorVariant

/** Nome mostrado na lista: modelo + sufixo de região ("FreeStyle Libre 2 Plus SA"). */
@Composable
fun SensorVariant.label(): String {
    val model = stringResource(modelRes)
    return region?.let { stringResource(R.string.sensor_variant_label, model, it.code) } ?: model
}
