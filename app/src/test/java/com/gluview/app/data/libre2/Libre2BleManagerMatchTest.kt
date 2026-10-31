package com.gluview.app.data.libre2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre o matcher de scan do [Libre2BleManager] — parte pura (JVM), sem Android.
 * Ver 07_revisao_ecossistema_2026-09.md (item A2).
 */
class Libre2BleManagerMatchTest {

    private fun matchesLibre2Sensor(
        name: String?,
        mac: String,
        serviceUuids: List<String>,
        savedMac: String?,
    ) = Libre2BleManager.matchesLibre2Sensor(name, mac, serviceUuids, savedMac)

    private val fde3 = "0000fde3-0000-1000-8000-00805f9b34fb"
    private val mac = "C1:B2:A3:94:85:76"

    @Test
    fun `casa Libre 2 Plus classico pelo nome ABBOTT`() {
        assertTrue(matchesLibre2Sensor("ABBOTT3MH01M7RT08", mac, emptyList(), savedMac = null))
    }

    @Test
    fun `casa nomes FSL e Libre`() {
        assertTrue(matchesLibre2Sensor("FSL 12345", mac, emptyList(), null))
        assertTrue(matchesLibre2Sensor("Libre2 98765", mac, emptyList(), null))
    }

    @Test
    fun `casa por servico fde3 no scanRecord mesmo sem nome`() {
        assertTrue(matchesLibre2Sensor(null, mac, listOf(fde3), savedMac = null))
        assertTrue(matchesLibre2Sensor("qualquer-coisa", mac, listOf("0000180a-0000-1000-8000-00805f9b34fb", fde3), null))
    }

    @Test
    fun `familia 7F que anuncia o MAC como nome so casa pelo MAC salvo`() {
        // 7F: sem nome estilo Abbott, sem fde3 no advertising.
        assertFalse(matchesLibre2Sensor(mac, mac, emptyList(), savedMac = null))
        assertTrue(matchesLibre2Sensor(mac, mac, emptyList(), savedMac = mac))
        assertTrue(matchesLibre2Sensor(mac, mac, emptyList(), savedMac = mac.lowercase()))
    }

    @Test
    fun `nao casa device aleatorio`() {
        assertFalse(matchesLibre2Sensor("Mi Band 6", "11:22:33:44:55:66", emptyList(), savedMac = mac))
        assertFalse(matchesLibre2Sensor(null, "11:22:33:44:55:66", emptyList(), savedMac = null))
        assertFalse(matchesLibre2Sensor(null, "11:22:33:44:55:66", emptyList(), savedMac = ""))
    }
}
