package com.gluview.app.watchface

/**
 * Dado de CAPACIDADE de um wearable — complementa [WearableSpec] (que só descreve layout:
 * resolução/forma, usado pelo editor pra desenhar). Ver plano 06_watchfaces_v2_arquitetura.md,
 * §8.1: [WearableSpec] é o subconjunto "dados de layout"; [WearableDefinition] é o superconjunto
 * "dados de capacidade" (transporte, se existe compilador de verdade, limites de paleta/arquivo).
 *
 * Puramente ADITIVO nesta etapa: nada no editor ([WatchFaceEditorScreen]/[WearableCatalog]) lê ou
 * filtra por isto ainda — `WearableCatalog.items` continua sendo a lista completa mostrada no
 * seletor. Achado P7 do plano (catálogo mistura "resolução conhecida" com "destino alcançável")
 * fica documentado aqui, sem mudar comportamento visível até uma decisão explícita de UX.
 */
enum class TransportKind { BLE_HUAMI, DATA_LAYER, SIDELOAD_APK, UNKNOWN }

data class WearableCapabilities(
    val transport: TransportKind,
    /** Existe hoje um WatchfaceCompiler/Applier real para este wearable (não só resolução catalogada)? */
    val compilerAvailable: Boolean,
    val maxPaletteColors: Int? = null,
    val maxFileSizeBytes: Long? = null,
    val minFirmwareVersion: String? = null
)

data class WearableDefinition(
    val spec: WearableSpec,
    val capabilities: WearableCapabilities
)

object WearableDefinitionCatalog {

    // Mi Band 6: compilador testado byte a byte em hardware real (é a banda usada nos testes).
    private val miBand6Capabilities = WearableCapabilities(
        transport = TransportKind.BLE_HUAMI,
        compilerAvailable = true,
        maxPaletteColors = 256 // ImageMiBandPalette.MAX_PALETTE_COLORS
    )

    // Mi Band 5 (Fase 5 do plano): compilador real (MiBand5WatchFaceApplier), mas NUNCA testado em
    // hardware — o mantenedor não tem uma Mi Band 5. Formato/auth verificados por evidência (mesma
    // assinatura de .bin e mesmos flags BLE da Mi Band 6), não por teste ao vivo.
    private val miBand5Capabilities = WearableCapabilities(
        transport = TransportKind.BLE_HUAMI,
        compilerAvailable = true,
        maxPaletteColors = 256
    )

    // Relógios Wear OS (plano 14, Fase 10 / plano 16): visor em Watch Face Format compilado no celular
    // (WffCompiler) e entregue pelo Data Layer ao app GluView do relógio (WearOsWatchFaceApplier).
    // Instalar exige Wear OS 6; em versões anteriores o relógio responde "sem suporte" e o envio avisa.
    private val wearOsCapabilities = WearableCapabilities(
        transport = TransportKind.DATA_LAYER,
        compilerAvailable = true
    )

    // Todo o resto do catálogo: resolução/forma conhecidas (WearableCatalog), mas sem nenhum
    // WatchfaceCompiler/Applier implementado — não é possível gerar nem instalar um visor hoje,
    // mesmo que o editor permita desenhar layout pra eles.
    private val unknownCapabilities = WearableCapabilities(
        transport = TransportKind.UNKNOWN,
        compilerAvailable = false
    )

    val all: List<WearableDefinition> = WearableCatalog.items.map { spec ->
        val capabilities = when (spec.id) {
            "mi_band_6" -> miBand6Capabilities
            "mi_band_5" -> miBand5Capabilities
            else -> if (com.gluview.app.watchface.apply.WearOsWatchFaceApplier.isWearOs(spec.id)) wearOsCapabilities
                    else unknownCapabilities
        }
        WearableDefinition(spec = spec, capabilities = capabilities)
    }

    fun definitionFor(wearableId: String): WearableDefinition? =
        all.firstOrNull { it.spec.id == wearableId }

    fun hasRealCompiler(wearableId: String): Boolean =
        definitionFor(wearableId)?.capabilities?.compilerAvailable == true

    /** Instalável a partir da LOJA: hoje a loja só baixa o artefato .bin e envia pela Mi Band (BLE Huami). */
    fun isStoreInstallable(wearableId: String): Boolean =
        definitionFor(wearableId)?.capabilities?.let { it.compilerAvailable && it.transport == TransportKind.BLE_HUAMI } == true
}
