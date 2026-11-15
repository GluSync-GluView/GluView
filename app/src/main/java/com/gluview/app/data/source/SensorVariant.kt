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
 * Quanto cada variante foi validada (regra 6 do plano de sensores: rótulo honesto). Nenhum sensor
 * depende de liberação do GluSync para ser escolhido: a variante implementada entra na lista para
 * qualquer conta, e o rótulo diz o que se sabe dela.
 *
 * - [WORKS]: validado em sensor real com o GluView.
 * - [TESTING]: em teste em sensor real, ainda sem conclusão.
 * - [UNTESTED]: implementado, mas nunca rodou num sensor real (só testes de unidade/simulador).
 */
enum class SensorMaturity(@StringRes val labelRes: Int) {
    WORKS(R.string.sensor_maturity_works),
    TESTING(R.string.sensor_maturity_testing),
    UNTESTED(R.string.sensor_maturity_untested)
}

/**
 * O que a pessoa escolhe na lista ("FreeStyle Libre 2 Plus SA"). Várias variantes podem usar a
 * MESMA implementação ([source]) quando o protocolo é igual — a variante muda o nome e a detecção,
 * não o código de leitura. [source] continua sendo o que o serviço e o servidor conhecem
 * (ids gravados em instalações existentes não mudam).
 */
data class SensorVariant(
    val id: String,
    @StringRes val modelRes: Int,
    val region: SensorRegion?,
    val source: DataSourceType,
    /** Quanto já foi validada em sensor real (rótulo mostrado na lista). */
    val maturity: SensorMaturity,
    /**
     * false = variante conhecida, mas o protocolo dela (ou o passo do assistente que a configura)
     * ainda não foi implementado: aparece em "Em breve", não dá para escolher.
     */
    val implemented: Boolean = true
)

object SensorVariantCatalog {

    /** Ordem da lista: por modelo, e dentro do modelo por região. */
    val all: List<SensorVariant> = listOf(
        single(DataSourceType.GLUSYNC, SensorMaturity.WORKS),
        SensorVariant("LIBRE2PLUS_SA", R.string.sensor_model_libre2_plus, SensorRegion.SA, DataSourceType.LIBRE2_PLUS, SensorMaturity.WORKS),
        SensorVariant("LIBRE2PLUS_NA", R.string.sensor_model_libre2_plus, SensorRegion.NA, DataSourceType.LIBRE2_PLUS, SensorMaturity.UNTESTED),
        SensorVariant("LIBRE2PLUS_EU", R.string.sensor_model_libre2_plus, SensorRegion.EU, DataSourceType.LIBRE2_PLUS, SensorMaturity.UNTESTED, implemented = false),
        SensorVariant("LIBRE2_NA", R.string.sensor_model_libre2, SensorRegion.NA, DataSourceType.LIBRE2_PLUS, SensorMaturity.UNTESTED),
        SensorVariant("LIBRE2_EU", R.string.sensor_model_libre2, SensorRegion.EU, DataSourceType.LIBRE2_PLUS, SensorMaturity.UNTESTED, implemented = false),
        SensorVariant("SIBIONICS_GS1_CN", R.string.sensor_model_sibionics_gs1, SensorRegion.CN, DataSourceType.SIBIONICS_CHINES, SensorMaturity.WORKS),
        SensorVariant("SIBIONICS_GS1_SA", R.string.sensor_model_sibionics_gs1, SensorRegion.SA, DataSourceType.SIBIONICS_NACIONAL, SensorMaturity.TESTING),
        single(DataSourceType.ACCU_CHEK_SMARTGUIDE, SensorMaturity.UNTESTED),
        single(DataSourceType.MEDLEVENSOHN_SMART2, SensorMaturity.UNTESTED),
        // PT-3: Dexcom pelo código (G6: ID do transmissor; G7: pareado pelo app oficial + código).
        single(DataSourceType.DEXCOM_G6, SensorMaturity.UNTESTED),
        single(DataSourceType.DEXCOM_G7, SensorMaturity.UNTESTED)
    )

    private fun single(source: DataSourceType, maturity: SensorMaturity, implemented: Boolean = true) =
        SensorVariant(source.id, source.labelRes, null, source, maturity, implemented)

    fun byId(id: String?): SensorVariant? = all.firstOrNull { it.id == id }

    /** Variante padrão de uma fonte (instalações anteriores à escolha por região). */
    fun defaultFor(source: DataSourceType): SensorVariant =
        all.firstOrNull { it.source == source && it.implemented } ?: all.first { it.source == source }

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
