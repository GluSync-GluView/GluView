package com.gluview.app.data.source

import android.os.Build
import android.os.Process
import com.gluview.app.BuildConfig

/**
 * Os sensores com biblioteca nativa proprietária (Sibionics: libnative-algorithm-*.so; Libre 2
 * Plus: libDataProcessing.so da Abbott + a ponte gluviewlibre2) só existem em **arm64-v8a** — as
 * .so foram extraídas dos apps oficiais e não há versão 32 bits.
 *
 * O APK também leva a pasta armeabi-v7a (sem essas bibliotecas) para instalar em aparelhos 32 bits,
 * como o Moto Z2 Play do seguidor (Android 9 em armeabi-v7a). Nesses aparelhos esses sensores ficam
 * indisponíveis: a escolha é bloqueada na interface e o serviço não liga a fonte. As pontes nativas
 * já capturam a falha de carga (runCatching), então isto não evita crash — evita ligar NFC, BLE e o
 * serviço em primeiro plano de um sensor que nunca vai funcionar, e troca um erro técnico por uma
 * mensagem clara.
 */
object NativeSensorSupport {

    /** Única ABI em que as bibliotecas dos sensores existem. */
    const val REQUIRED_ABI = "arm64-v8a"

    /** Função pura (testável): o processo é 64 bits e o aparelho roda [REQUIRED_ABI]? */
    fun supports(supported64BitAbis: Array<String>?, is64BitProcess: Boolean): Boolean =
        is64BitProcess && supported64BitAbis.orEmpty().contains(REQUIRED_ABI)

    /**
     * Neste aparelho e neste processo. Exige o PROCESSO 64 bits (não só o aparelho): um APK
     * instalado à força com `--abi armeabi-v7a` num aparelho arm64 roda sem as bibliotecas.
     * Getter (e não `lazy`): é barato, e o harness visual pode simular o aparelho com ShadowBuild.
     */
    val isAvailable: Boolean
        get() = supports(Build.SUPPORTED_64_BIT_ABIS, Process.is64Bit())

    /**
     * O build inclui as bibliotecas proprietárias desse sensor? O código-fonte público não traz as
     * .so (não podem ser redistribuídas); quem compila extrai dos APKs oficiais com
     * tools/extract_native_libs.py. Sem elas, o sensor fica indisponível como num aparelho 32 bits.
     */
    fun libsBundled(type: DataSourceType): Boolean = when {
        type == DataSourceType.LIBRE2_PLUS -> BuildConfig.HAS_ABBOTT_LIBS
        type.isSibionics                   -> BuildConfig.HAS_SIBIONICS_LIBS
        else                               -> true
    }
}
