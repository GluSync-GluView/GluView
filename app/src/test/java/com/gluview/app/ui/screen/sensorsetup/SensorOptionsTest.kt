package com.gluview.app.ui.screen.sensorsetup

import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.SensorMaturity
import com.gluview.app.data.source.SensorVariantCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PT-1 do plano de sensores: nenhum sensor depende de liberação prévia do GluSync. A lista de
 * sensores é montada só com o catálogo local e o aparelho — não recebe conta, liberação de nuvem
 * nem candidatura de beta, então é a mesma para qualquer conta (inclusive sem nenhum pedido
 * aprovado).
 */
class SensorOptionsTest {

    private val allUsable: (DataSourceType) -> Boolean = { true }

    @Test fun `toda variante implementada fica disponível sem nenhuma liberação`() {
        val options = buildSensorOptions(currentId = null, variantId = null, usableOnDevice = allUsable)
        val expected = SensorVariantCatalog.all
            .filter { it.source != DataSourceType.GLUSYNC && it.implemented }
            .map { it.id }.toSet()
        assertEquals(expected, options.filter { it.isSupported }.map { it.variant.id }.toSet())
    }

    @Test fun `lista identica para conta nova e conta com sensor em uso`() {
        val fresh = buildSensorOptions(null, null, allUsable)
        val inUse = buildSensorOptions(DataSourceType.SIBIONICS_CHINES.id, "SIBIONICS_GS1_CN", allUsable)
        fun summary(list: List<SensorOption>) = list.map { it.variant.id to it.isSupported }.toSet()
        assertEquals(summary(fresh), summary(inUse))
    }

    @Test fun `sensores antes em beta agora entram com rotulo honesto`() {
        val options = buildSensorOptions(null, null, allUsable).associateBy { it.variant.id }
        listOf("ACCU_CHEK_SMARTGUIDE", "MEDLEVENSOHN_SMART2", "SIBIONICS_GS1_SA", "DEXCOM_G6", "DEXCOM_G7").forEach { id ->
            val option = options.getValue(id)
            assertTrue("$id deveria estar disponível", option.isSupported)
            assertTrue("$id não pode aparecer como Funciona", option.maturity != SensorMaturity.WORKS)
        }
    }

    @Test fun `so o que foi validado em sensor real aparece como Funciona`() {
        val works = SensorVariantCatalog.all.filter { it.maturity == SensorMaturity.WORKS }.map { it.id }.toSet()
        assertEquals(setOf("GLUSYNC", "LIBRE2PLUS_SA", "SIBIONICS_GS1_CN"), works)
    }

    @Test fun `variante sem protocolo implementado fica em Em breve`() {
        val options = buildSensorOptions(null, null, allUsable).associateBy { it.variant.id }
        SensorVariantCatalog.all.filter { !it.implemented }.forEach {
            assertFalse("${it.id} não pode ser escolhível", options.getValue(it.id).isSupported)
        }
    }

    @Test fun `biblioteca nativa ausente deixa indisponivel neste aparelho`() {
        val options = buildSensorOptions(null, null) { !it.requiresNativeLibs }
        val libre = options.first { it.variant.id == "LIBRE2PLUS_SA" }
        assertFalse(libre.isSupported)
        assertTrue(libre.unavailableOnDevice)
        assertTrue(options.first { it.variant.id == "ACCU_CHEK_SMARTGUIDE" }.isSupported)
    }

    @Test fun `sensor em uso vem primeiro`() {
        val options = buildSensorOptions(DataSourceType.ACCU_CHEK_SMARTGUIDE.id, null, allUsable)
        assertEquals("ACCU_CHEK_SMARTGUIDE", options.first().variant.id)
        assertTrue(options.first().isCurrent)
    }

    @Test fun `fonte sem variante implementada nao quebra a resolucao`() {
        assertEquals("DEXCOM_G7", SensorVariantCatalog.defaultFor(DataSourceType.DEXCOM_G7).id)
        assertEquals("DEXCOM_G6", SensorVariantCatalog.resolve(null, DataSourceType.DEXCOM_G6.id).id)
    }
}
