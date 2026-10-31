package com.gluview.app.data.source

import com.gluview.app.data.libre2.LibrePatchInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Variantes por modelo e região e a detecção pelo patchInfo do Libre. */
class SensorVariantTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ── geração (regra do Juggluco) ─────────────────────────────────────────────

    @Test fun `geracao pelo byte 2 do patchInfo`() {
        assertEquals(2, LibrePatchInfo.generation(hex("2B0A3A081FE1")))   // Libre 2 Plus brasileiro testado
        assertEquals(1, LibrePatchInfo.generation(hex("9D0830010000")))   // Libre 2 europeu (xDrip)
        assertEquals(1, LibrePatchInfo.generation(hex("C60931010000")))   // Libre 2 Plus europeu (xDrip)
        assertEquals(1, LibrePatchInfo.generation(hex("7F0E31010000")))   // família nova "7F" europeia
        assertEquals(0, LibrePatchInfo.generation(hex("DF0000")))         // curto/Libre 1
    }

    // ── região (byte 3) e variante detectada ────────────────────────────────────

    @Test fun `sensor brasileiro gen2 com regiao 08 vira Libre 2 Plus SA`() =
        assertEquals("LIBRE2PLUS_SA", LibrePatchInfo.detectedVariantId(hex("2B0A3A081FE1"), "LIBRE2PLUS_NA"))

    @Test fun `gen2 com regiao 02 vira NA mantendo o modelo escolhido`() {
        assertEquals("LIBRE2_NA", LibrePatchInfo.detectedVariantId(hex("2B0A3A021FE1"), "LIBRE2_NA"))
        assertEquals("LIBRE2PLUS_NA", LibrePatchInfo.detectedVariantId(hex("2B0A3A021FE1"), ""))
    }

    @Test fun `Libre 2 sem variante SA cai no Libre 2 Plus SA`() =
        assertEquals("LIBRE2PLUS_SA", LibrePatchInfo.detectedVariantId(hex("2B0A3A081FE1"), "LIBRE2_NA"))

    @Test fun `gen1 ou regiao desconhecida nao afirmam nada`() {
        assertNull(LibrePatchInfo.detectedVariantId(hex("9D0830010000"), ""))
        assertNull(LibrePatchInfo.detectedVariantId(hex("2B0A3A041FE1"), ""))
    }

    // ── catálogo ────────────────────────────────────────────────────────────────

    @Test fun `instalacoes antigas resolvem para a variante padrao da fonte`() {
        assertEquals("LIBRE2PLUS_SA", SensorVariantCatalog.resolve("", "LIBRE2_PLUS").id)
        assertEquals("SIBIONICS_GS1_CN", SensorVariantCatalog.resolve(null, "SIBIONICS_CHINES").id)
        assertEquals("GLUSYNC", SensorVariantCatalog.resolve("", "GLUSYNC").id)
    }

    @Test fun `variante salva que nao bate com a fonte ativa e ignorada`() =
        assertEquals("SIBIONICS_GS1_CN", SensorVariantCatalog.resolve("LIBRE2PLUS_NA", "SIBIONICS_CHINES").id)

    @Test fun `toda fonte tem uma variante implementada e os ids sao unicos`() {
        DataSourceType.entries.forEach { assertTrue(SensorVariantCatalog.defaultFor(it).implemented) }
        assertEquals(SensorVariantCatalog.all.size, SensorVariantCatalog.all.map { it.id }.toSet().size)
    }

    @Test fun `Libre europeu aparece mas nao esta implementado`() {
        assertFalse(SensorVariantCatalog.byId("LIBRE2PLUS_EU")!!.implemented)
        assertEquals(SensorRegion.EU, SensorVariantCatalog.byId("LIBRE2_EU")!!.region)
    }
}
