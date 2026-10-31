package com.gluview.app.ui.components.glucose

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.gluview.app.domain.model.GlucoseZone
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** O TalkBack lê o card numa parada só; sem a hora (seguidor), nada de "Medida às —". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "pt-rBR")
class GlucoseHeroCardA11yTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `com a hora fala a hora`() {
        compose.setContent {
            GlucoseHeroCard("135", "→", "mg/dL", GlucoseZone.NORMAL, timeText = "14:36")
        }
        compose.onNodeWithContentDescription("Medida às 14:36.", substring = true).assertExists()
    }

    @Test fun `sem a hora fala a linha de baixo`() {
        compose.setContent {
            GlucoseHeroCard("225", "↗", "mg/dL", GlucoseZone.HIGH, subtitle = "+5 · agora")
        }
        val desc = compose.onNodeWithContentDescription("Glicemia 225 mg/dL", substring = true)
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(desc, desc.endsWith(". +5 · agora"))
        assertFalse(desc, desc.contains("Medida"))
    }
}
