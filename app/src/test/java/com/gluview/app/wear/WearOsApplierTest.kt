package com.gluview.app.wear

import com.gluview.app.R
import com.gluview.app.ui.screen.watchface.WatchFaceMessageAction
import com.gluview.app.ui.screen.watchface.toMessage
import com.gluview.app.util.UiText
import com.gluview.app.watchface.apply.ApplyResult
import com.gluview.app.watchface.apply.WearOsWatchFaceApplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearOsApplierTest {

    @Test fun soRelogiosWearOsDoCatalogo() {
        listOf("galaxy_watch_4_40", "galaxy_watch_7_44", "galaxy_watch_ultra", "pixel_watch_3_45", "xiaomi_watch_2")
            .forEach { assertTrue(it, WearOsWatchFaceApplier.isWearOs(it)) }
        // Sistema próprio (não Wear OS) e Apple ficam de fora.
        listOf("xiaomi_watch_s1", "xiaomi_watch_s3", "apple_watch_40", "miband6")
            .forEach { assertFalse(it, WearOsWatchFaceApplier.isWearOs(it)) }
    }

    @Test fun fraseDoWearOs_semAtalhoDaMiBand() {
        val m = ApplyResult.NeedsSetup("x", R.string.wear_apply_needs_app).toMessage()
        assertEquals(UiText.Res(R.string.wear_apply_needs_app), m.text)
        assertNull(m.action)
        assertEquals(UiText.Res(R.string.wear_apply_coming), ApplyResult.Unsupported("x", R.string.wear_apply_coming).toMessage().text)
    }

    @Test fun miBand_continuaComAFraseEOAtalhoDeSempre() {
        val m = ApplyResult.NeedsSetup("Configure a Mi Band").toMessage()
        assertEquals(UiText.Res(R.string.wf_msg_needs_setup), m.text)
        assertEquals(WatchFaceMessageAction.SETUP_MIBAND, m.action)
        assertEquals(UiText.Res(R.string.wf_msg_unsupported), ApplyResult.Unsupported("x").toMessage().text)
    }
}
