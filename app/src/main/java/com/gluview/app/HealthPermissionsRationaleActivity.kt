package com.gluview.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.theme.GluViewTheme
import com.gluview.app.ui.theme.Spacing

/**
 * Por que o GluView pede dados do Health Connect (plano 17, Fase 3) — o Health Connect exige esta
 * tela. Só texto: o que é gravado, o que é lido, que fica no aparelho e nunca vira recomendação.
 */
class HealthPermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GluViewTheme {
                Page(stringResource(R.string.hc_rationale_title), onBack = { finish() }) {
                    GluCard {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(stringResource(R.string.hc_rationale_write), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.hc_rationale_read), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.hc_rationale_local), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.hc_rationale_no_advice), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.hc_rationale_revoke), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
