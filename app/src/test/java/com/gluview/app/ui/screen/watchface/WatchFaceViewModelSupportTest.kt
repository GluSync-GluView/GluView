package com.gluview.app.ui.screen.watchface

import com.gluview.app.R
import com.gluview.app.util.UiText
import com.gluview.app.watchface.apply.ApplyResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * toMessage() é a parte pura da correção do achado P4/P5 (plano 06_watchfaces_v2_arquitetura.md),
 * agora traduzida pela tela (redesign visual, Fase 9): cada desfecho vira uma frase do idioma do app.
 */
class WatchFaceViewModelSupportTest {

    @Test
    fun `Success vira mensagem fixa de sucesso`() {
        assertEquals(WatchFaceMessage(UiText.Res(R.string.wf_msg_sent)), ApplyResult.Success.toMessage())
    }

    @Test
    fun `NeedsSetup oferece configurar a Mi Band`() {
        assertEquals(
            WatchFaceMessage(UiText.Res(R.string.wf_msg_needs_setup), WatchFaceMessageAction.SETUP_MIBAND),
            ApplyResult.NeedsSetup("configure algo").toMessage()
        )
    }

    @Test
    fun `Unsupported e InProgress viram frases proprias`() {
        assertEquals(WatchFaceMessage(UiText.Res(R.string.wf_msg_unsupported)), ApplyResult.Unsupported("sem suporte").toMessage())
        assertEquals(WatchFaceMessage(UiText.Res(R.string.wf_msg_in_progress)), ApplyResult.InProgress("em desenvolvimento").toMessage())
    }

    @Test
    fun `Failure mantem o detalhe`() {
        assertEquals(WatchFaceMessage(UiText.Res(R.string.wf_msg_failed, "timeout BLE")), ApplyResult.Failure("timeout BLE").toMessage())
    }
}
