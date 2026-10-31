package com.gluview.app.data.source

import androidx.annotation.StringRes
import com.gluview.app.R

/**
 * Região de COMERCIALIZAÇÃO do sensor (não o país de fabricação). Só existe uma variante regional
 * quando há diferença técnica ou de mercado comprovada — ver docs de pesquisa em NOTICE/README:
 *
 * - Libre 2 / 2 Plus: o protocolo é decidido pela geração no patchInfo (Juggluco). Europa usa Gen1
 *   (caminho ainda não implementado); EUA e Brasil usam Gen2 (o mesmo código). O byte 3 do
 *   patchInfo traz a região (DiaBLE: 1 Europa, 2 EUA, 8 resto do mundo — o sensor brasileiro
 *   testado vem com 8).
 * - Sibionics GS1: cada mercado usa outro app oficial e outra chave de autenticação; o sensor só
 *   conversa com a do seu mercado. CN = caixa chinesa (硅基动感, registro NMPA), testado.
 */
enum class SensorRegion(val code: String, @StringRes val nameRes: Int) {
    EU("EU", R.string.sensor_region_eu),
    NA("NA", R.string.sensor_region_na),
    SA("SA", R.string.sensor_region_sa),
    CN("CN", R.string.sensor_region_cn)
}

/**
 * O que a pessoa escolhe na lista ("FreeStyle Libre 2 Plus SA"). Várias variantes podem usar a
 * MESMA implementação ([source]) quando o protocolo é igual — a variante muda o nome e a detecção,
 * não o código de leitura. [source] continua sendo o que o serviço e o servidor conhecem
 * (ids gravados em instalações existentes e em `sensor_beta:<id>` não mudam).
 */
data class SensorVariant(
    val id: String,
    @StringRes val modelRes: Int,
    val region: SensorRegion?,
    val source: DataSourceType,
    /** false = variante conhecida, mas o protocolo dela ainda não foi implementado. */
    val implemented: Boolean = true
)

object SensorVariantCatalog {

    /** Ordem da lista: por modelo, e dentro do modelo por região. */
    val all: List<SensorVariant> = listOf(
        single(DataSourceType.GLUSYNC),
        SensorVariant("LIBRE2PLUS_SA", R.string.sensor_model_libre2_plus, SensorRegion.SA, DataSourceType.LIBRE2_PLUS),
        SensorVariant("LIBRE2PLUS_NA", R.string.sensor_model_libre2_plus, SensorRegion.NA, DataSourceType.LIBRE2_PLUS),
        SensorVariant("LIBRE2PLUS_EU", R.string.sensor_model_libre2_plus, SensorRegion.EU, DataSourceType.LIBRE2_PLUS, implemented = false),
        SensorVariant("LIBRE2_NA", R.string.sensor_model_libre2, SensorRegion.NA, DataSourceType.LIBRE2_PLUS),
        SensorVariant("LIBRE2_EU", R.string.sensor_model_libre2, SensorRegion.EU, DataSourceType.LIBRE2_PLUS, implemented = false),
        SensorVariant("SIBIONICS_GS1_CN", R.string.sensor_model_sibionics_gs1, SensorRegion.CN, DataSourceType.SIBIONICS_CHINES),
        SensorVariant("SIBIONICS_GS1_SA", R.string.sensor_model_sibionics_gs1, SensorRegion.SA, DataSourceType.SIBIONICS_NACIONAL),
        single(DataSourceType.ACCU_CHEK_SMARTGUIDE),
        single(DataSourceType.MEDLEVENSOHN_SMART2),
        single(DataSourceType.DEXCOM_G6),
        single(DataSourceType.DEXCOM_G7)
    )

    private fun single(source: DataSourceType) = SensorVariant(source.id, source.labelRes, null, source)

    fun byId(id: String?): SensorVariant? = all.firstOrNull { it.id == id }

    /** Variante padrão de uma fonte (instalações anteriores à escolha por região). */
    fun defaultFor(source: DataSourceType): SensorVariant = all.first { it.source == source && it.implemented }

    /** Variante em uso: a salva, se ainda bate com a fonte ativa; senão a padrão da fonte. */
    fun resolve(variantId: String?, sourceId: String?): SensorVariant {
        val source = DataSourceType.fromId(sourceId)
        return byId(variantId)?.takeIf { it.source == source } ?: defaultFor(source)
    }

    /**
     * Variante Libre (Gen2) para uma região detectada, mantendo o modelo (2 ou 2 Plus) que a pessoa
     * escolheu — o patchInfo dos sensores Gen2 testados não distingue os dois com segurança. Sem
     * variante para aquele modelo/região, cai no Libre 2 Plus da região.
     */
    fun libreVariant(region: SensorRegion, plus: Boolean): SensorVariant? {
        val model = if (plus) R.string.sensor_model_libre2_plus else R.string.sensor_model_libre2
        val candidates = all.filter { it.source == DataSourceType.LIBRE2_PLUS && it.region == region && it.implemented }
        return candidates.firstOrNull { it.modelRes == model } ?: candidates.firstOrNull()
    }
}
