package com.gluview.app.data.source

import com.gluview.app.data.accuchek.AccuChekSource
import com.gluview.app.data.dexcom.DexcomG6Source
import com.gluview.app.data.libre2.Libre2Source
import com.gluview.app.data.medlevensohn.MedLevensohnSource
import com.gluview.app.data.sibionics.SibionicsBleSource
import com.gluview.app.data.librebridge.LibreBridgeSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolve a [GlucoseSource] correta para um [DataSourceType]. Ponto único de extensão para novos
 * sensores: adicione a nova fonte no `when` e nada mais no serviço precisa mudar.
 *
 * As fontes são `@Singleton` (injetadas), então o registro só as seleciona — não as cria.
 *
 * PT-3: [DataSourceType.DEXCOM_G7] entrou com o pareamento EC-JPAKE da libkeks do xDrip (módulo
 * `:keks`), para o G7 já pareado pelo app oficial no mesmo celular.
 */
@Singleton
class SensorSourceRegistry @Inject constructor(
    private val sibionics: SibionicsBleSource,
    private val libre2: Libre2Source,
    private val accuChek: AccuChekSource,
    private val medLevensohn: MedLevensohnSource,
    private val dexcomG6: DexcomG6Source,
    private val dexcomG7: com.gluview.app.data.dexcom.DexcomG7Source,
    private val libreBridge: LibreBridgeSource
) {
    /**
     * A fonte para [type], ou null se a fonte não é um sensor local (ex.: [DataSourceType.GLUSYNC]),
     * ainda não tem driver funcional, ou depende de biblioteca nativa que este aparelho não roda
     * (32 bits — ver [NativeSensorSupport]); nesse caso a fonte nunca leria nada.
     */
    fun forType(type: DataSourceType): GlucoseSource? = when {
        !type.isUsableOnThisDevice         -> null
        type.isSibionics                   -> sibionics
        type == DataSourceType.LIBRE2_PLUS -> libre2
        type == DataSourceType.ACCU_CHEK_SMARTGUIDE -> accuChek
        type == DataSourceType.MEDLEVENSOHN_SMART2  -> medLevensohn
        type == DataSourceType.DEXCOM_G6            -> dexcomG6
        type == DataSourceType.DEXCOM_G7            -> dexcomG7
        type.isLibreBridge                           -> libreBridge
        else                                -> null
    }
}
