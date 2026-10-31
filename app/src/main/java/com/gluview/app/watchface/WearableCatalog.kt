package com.gluview.app.watchface

object WearableCatalog {
    val default = WearableSpec(
        id = "custom_454_454",
        brand = "Personalizado",
        name = "454 x 454",
        widthPx = 454,
        heightPx = 454,
        kind = WearableKind.SMARTWATCH,
        shape = WearableShape.ROUND
    )

    val items: List<WearableSpec> = listOf(
        WearableSpec("mi_band_1", "Xiaomi", "Mi Band 1", 128, 32, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("mi_band_2", "Xiaomi", "Mi Band 2", 128, 80, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("mi_band_3", "Xiaomi", "Mi Band 3", 128, 80, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("mi_band_4", "Xiaomi", "Mi Band 4", 120, 240, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        // 126x170 — verificado lendo o IHDR do canvas.png real do template WatchDrip (Fase 5 do
        // plano 06_watchfaces_v2_arquitetura.md), não copiado de ficha técnica. O valor anterior
        // (126x294) estava errado — divergia do que o compilador Mi Band 5 realmente usa.
        WearableSpec("mi_band_5", "Xiaomi", "Mi Band 5", 126, 170, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("mi_band_6", "Xiaomi", "Mi Smart Band 6", 152, 240, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("mi_band_7", "Xiaomi", "Smart Band 7", 192, 490, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("mi_band_7_pro", "Xiaomi", "Smart Band 7 Pro", 280, 456, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("mi_band_8", "Xiaomi", "Smart Band 8", 192, 490, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("mi_band_8_pro", "Xiaomi", "Smart Band 8 Pro", 336, 480, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("mi_band_9", "Xiaomi", "Smart Band 9", 192, 490, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("xiaomi_watch_s1", "Xiaomi", "Watch S1", 466, 466, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("xiaomi_watch_s3", "Xiaomi", "Watch S3", 466, 466, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("xiaomi_watch_2", "Xiaomi", "Watch 2", 466, 466, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("galaxy_watch_4_40", "Samsung", "Galaxy Watch4 40mm", 396, 396, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("galaxy_watch_4_44", "Samsung", "Galaxy Watch4 44mm", 450, 450, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("galaxy_watch_5_44", "Samsung", "Galaxy Watch5 44mm", 450, 450, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("galaxy_watch_6_44", "Samsung", "Galaxy Watch6 44mm", 480, 480, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("galaxy_watch_7_44", "Samsung", "Galaxy Watch7 44mm", 480, 480, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("galaxy_watch_ultra", "Samsung", "Galaxy Watch Ultra", 480, 480, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("galaxy_fit_2", "Samsung", "Galaxy Fit2", 126, 294, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("galaxy_fit_3", "Samsung", "Galaxy Fit3", 256, 402, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("pixel_watch_1", "Google", "Pixel Watch", 384, 384, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("pixel_watch_2", "Google", "Pixel Watch 2", 384, 384, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("pixel_watch_3_45", "Google", "Pixel Watch 3 45mm", 456, 456, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("apple_watch_40", "Apple", "Watch 40mm", 324, 394, WearableKind.SMARTWATCH, WearableShape.SQUIRCLE),
        WearableSpec("apple_watch_41", "Apple", "Watch 41mm", 352, 430, WearableKind.SMARTWATCH, WearableShape.SQUIRCLE),
        WearableSpec("apple_watch_44", "Apple", "Watch 44mm", 368, 448, WearableKind.SMARTWATCH, WearableShape.SQUIRCLE),
        WearableSpec("apple_watch_45", "Apple", "Watch 45mm", 396, 484, WearableKind.SMARTWATCH, WearableShape.SQUIRCLE),
        WearableSpec("apple_watch_49", "Apple", "Watch Ultra 49mm", 410, 502, WearableKind.SMARTWATCH, WearableShape.SQUIRCLE),
        WearableSpec("amazfit_band_7", "Amazfit", "Band 7", 198, 368, WearableKind.SMARTBAND, WearableShape.RECTANGLE),
        WearableSpec("amazfit_gts_4", "Amazfit", "GTS 4", 390, 450, WearableKind.SMARTWATCH, WearableShape.SQUIRCLE),
        WearableSpec("amazfit_gtr_4", "Amazfit", "GTR 4", 466, 466, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("amazfit_balance", "Amazfit", "Balance", 480, 480, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("huawei_watch_gt_4_46", "Huawei", "Watch GT 4 46mm", 466, 466, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("huawei_watch_fit_3", "Huawei", "Watch Fit 3", 347, 480, WearableKind.SMARTWATCH, WearableShape.SQUIRCLE),
        WearableSpec("garmin_venu_3", "Garmin", "Venu 3", 454, 454, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("garmin_forerunner_965", "Garmin", "Forerunner 965", 454, 454, WearableKind.SMARTWATCH, WearableShape.ROUND),
        WearableSpec("garmin_epix_pro", "Garmin", "Epix Pro", 416, 416, WearableKind.SMARTWATCH, WearableShape.ROUND)
    )

    /** Wearable pre-selecionado no editor ("pronto pra uso") — a banda usada nos testes (Mi Band 6). */
    val starter: WearableSpec = items.firstOrNull { it.id == "mi_band_6" } ?: default

    fun find(id: String, width: Int? = null, height: Int? = null, name: String? = null): WearableSpec =
        items.firstOrNull { it.id == id }
            ?: default.copy(
                id = id,
                name = name ?: default.name,
                widthPx = width ?: default.widthPx,
                heightPx = height ?: default.heightPx,
                kind = customKind(id) ?: default.kind,
                shape = customShape(id) ?: default.shape
            )

    /**
     * Id do wearable personalizado. L27: tipo e forma vão no próprio id (o banco e a API só guardam
     * id, nome e tamanho) — antes reabrir voltava sempre para relógio redondo. O padrão (relógio
     * redondo) mantém o id antigo `custom_L_A`, igual ao do portal.
     */
    fun customId(width: Int, height: Int, kind: WearableKind, shape: WearableShape): String =
        if (kind == default.kind && shape == default.shape) "custom_${width}_$height"
        else "custom_${width}_${height}_${kind.name.lowercase()}_${shape.name.lowercase()}"

    private fun customPart(id: String, index: Int): String? =
        id.takeIf { it.startsWith("custom_") }?.split('_')?.getOrNull(index)?.uppercase()

    private fun customKind(id: String): WearableKind? =
        customPart(id, 3)?.let { p -> WearableKind.entries.firstOrNull { it.name == p } }

    private fun customShape(id: String): WearableShape? =
        customPart(id, 4)?.let { p -> WearableShape.entries.firstOrNull { it.name == p } }
}
