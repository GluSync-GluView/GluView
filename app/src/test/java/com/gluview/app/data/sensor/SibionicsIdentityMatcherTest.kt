package com.gluview.app.data.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regra de identidade do Sibionics (4 primeiros do código de conexão == 4 últimos do nome
 * anunciado). Os casos usam os dois sensores reais do aparelho de teste, cujos seriais estão
 * registrados no histórico local:
 *
 *  - sensor atual:   serial 260109UUU1673DEX81 → código UUU1673D → nome deve terminar em "UUU1"
 *  - sensor anterior: serial 251101DGMN803EFY68 → código DGMN803E → nome deve terminar em "DGMN"
 *
 * O par é o cenário que motivou a mudança: com os dois sensores no ar ao mesmo tempo, o app tem
 * de conectar no novo e ignorar o velho, sem perguntar nada ao usuário.
 */
class SibionicsIdentityMatcherTest {

    private val matcher = SibionicsIdentityMatcher()

    private val atual    = SensorCode(connectionCode = "UUU1673D", serial = "260109UUU1673DEX81")
    private val anterior = SensorCode(connectionCode = "DGMN803E", serial = "251101DGMN803EFY68")

    @Test
    fun `casa o nome anunciado do proprio sensor`() {
        assertTrue(matcher.matches("LT2588UUU1", atual))
        assertTrue(matcher.matches("LT2588DGMN", anterior))
    }

    @Test
    fun `nao casa o sensor da outra caixa`() {
        assertFalse(matcher.matches("LT2588DGMN", atual))
        assertFalse(matcher.matches("LT2588UUU1", anterior))
    }

    @Test
    fun `ignora diferenca de caixa alta`() {
        assertTrue(matcher.matches("lt2588uuu1", atual))
        assertTrue(matcher.matches("LT2588uuu1", atual))
    }

    @Test
    fun `ignora espacos ao redor do nome`() {
        assertTrue(matcher.matches("  LT2588UUU1  ", atual))
    }

    @Test
    fun `exige o prefixo da familia`() {
        // Mesmo terminando nos 4 caracteres certos, um dispositivo de outra família é descartado:
        // sem isso, qualquer periférico do ambiente poderia ser adotado como sensor.
        assertFalse(matcher.matches("FoneUUU1", atual))
        assertFalse(matcher.matches("UUU1", atual))
    }

    @Test
    fun `rejeita entradas ausentes ou curtas demais`() {
        assertFalse(matcher.matches(null, atual))
        assertFalse(matcher.matches("", atual))
        assertFalse(matcher.matches("LT", atual))
        assertFalse(matcher.matches("LT2588UUU1", SensorCode(connectionCode = "UUU")))
        assertFalse(matcher.matches("LT2588UUU1", SensorCode(connectionCode = "")))
    }

    @Test
    fun `codigo com menos de quatro caracteres nunca e valido`() {
        assertFalse(SensorCode(connectionCode = "UUU").isValid)
        assertTrue(SensorCode(connectionCode = "UUU1").isValid)
        assertTrue(atual.isValid)
    }
}
