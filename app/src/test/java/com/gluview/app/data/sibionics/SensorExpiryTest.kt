package com.gluview.app.data.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guarda de fim de vida do sensor.
 *
 * O caso real que motivou isto: o sensor anterior deste aparelho chegou ao índice 34.303 (23,8
 * dias), parou de enviar glicemia e continuou aceitando conexão normalmente — o app avisava
 * "verifique o sensor", mandando procurar um defeito que não existia, quando a única saída era
 * trocar.
 */
class SensorExpiryTest {

    private val agora = 1_755_000_000_000L

    private fun status(index: Int, minutosSemLeitura: Long = 0L) = SibionicsSensorStatus(
        connected     = true,
        lastIndex     = index,
        sensorStartMs = agora - index * 60_000L,
        lastReadingMs = agora - minutosSemLeitura * 60_000L
    )

    @Test
    fun `sensor dentro da vida util nao esta expirado`() {
        assertFalse(status(index = 1).isExpired())
        assertFalse(status(index = 20_160).isExpired())          // 14 dias — vida de rótulo
        assertFalse(status(index = 34_559).isExpired())          // 1 minuto antes do limite
    }

    @Test
    fun `sensor no limite do firmware esta expirado`() {
        assertTrue(status(index = 34_560).isExpired())           // 24 dias exatos
        assertTrue(status(index = 34_600).isExpired())
    }

    @Test
    fun `sensor sem nenhuma leitura nao e tratado como expirado`() {
        // lastIndex = -1 (nada recebido): sensor novo aquecendo não pode virar "expirado".
        assertFalse(SibionicsSensorStatus().isExpired())
        assertEquals(null, SibionicsSensorStatus().lifetimeMinutes())
    }

    @Test
    fun `expirado tem prioridade sobre verifique o sensor`() {
        // Cenário exato do incidente: passou da vida útil E está silencioso há horas.
        // A mensagem certa é "troque", não "verifique".
        val expirado = status(index = 34_600, minutosSemLeitura = 400)
        assertEquals(
            SensorWarning.EXPIRED,
            expirado.evaluateWarning(noSignalMin = 15, agingDays = 14, nowMs = agora)
        )
    }

    @Test
    fun `sensor silencioso ainda dentro da vida util pede verificacao`() {
        val silencioso = status(index = 10_000, minutosSemLeitura = 90)
        assertEquals(
            SensorWarning.CHECK_SENSOR,
            silencioso.evaluateWarning(noSignalMin = 15, agingDays = 14, nowMs = agora)
        )
    }

    @Test
    fun `aquecimento nunca alarma`() {
        val novo = status(index = 5, minutosSemLeitura = 0)
        assertEquals(
            SensorWarning.NONE,
            novo.evaluateWarning(noSignalMin = 15, agingDays = 14, nowMs = agora, warmingUp = true)
        )
    }
}
