package com.gluview.app.watchface

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class WearableKind(val label: String) {
    SMARTWATCH("SmartWatch"),
    SMARTBAND("SmartBand")
}

enum class WearableShape(val label: String) {
    RECTANGLE("Retangular"),
    ROUND("Redondo"),
    SQUIRCLE("Quadrado arredondado")
}

data class WearableSpec(
    val id: String,
    val brand: String,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val kind: WearableKind,
    val shape: WearableShape
) {
    val label: String get() = "$brand $name (${widthPx}x${heightPx})"
}

// WatchFaceComponentType (enum fechado) foi substituído por ComponentKind/ComponentRegistry
// (ver ComponentKind.kt) — achado P3 do plano 06_watchfaces_v2_arquitetura.md. Os ids de string
// ("time_hms", "glucose_current"...) continuam os mesmos, então nada gravado no Room/JSON precisa
// migrar por causa desta troca.

enum class WatchFaceTextAlign(val value: String) {
    START("start"),
    CENTER("center"),
    END("end");

    companion object {
        fun fromValue(value: String): WatchFaceTextAlign =
            entries.firstOrNull { it.value == value } ?: CENTER
    }
}

data class WatchFaceComponentDefinition(
    val key: String,
    val kind: ComponentKind,
    val variation: Int,
    val name: String,
    val defaultWidth: Float,
    val defaultHeight: Float,
    val defaultColor: Long,
    val defaultFont: String,
    val defaultAlignment: WatchFaceTextAlign,
    val styleTag: String
)

data class WatchFaceComponent(
    val id: String = UUID.randomUUID().toString(),
    val typeId: String,
    val variation: Int,
    val label: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val color: Long,
    val font: String,
    val alpha: Float = 1f,
    val alignment: String = WatchFaceTextAlign.CENTER.value,
    val zIndex: Int = 0,
    val styleTag: String = "classic",
    // Posição/tamanho em FRAÇÃO do canvas (0f..1f), null em componentes ainda não migrados
    // (schemaVersion 1) ou criados antes desta versão. Quando presentes, permitem recalcular
    // x/y/width/height para um wearable de resolução diferente — ver WatchFaceMigrations.kt.
    // x/y/width/height (px absolutos) continuam sendo a fonte usada por editor/renderer/gerador
    // hoje; os campos fracionários são aditivos, não substituem nada nesta etapa.
    val xFrac: Float? = null,
    val yFrac: Float? = null,
    val widthFrac: Float? = null,
    val heightFrac: Float? = null,
    // Texto do usuário para FixedTextKind ("texto fixo") — null em qualquer outro tipo de
    // componente, que ignora este campo.
    val customText: String? = null
) {
    fun right(): Float = x + width * scale
    fun bottom(): Float = y + height * scale
}

data class WatchFaceDesign(
    val id: Long = 0,
    val name: String = "",
    val wearable: WearableSpec,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val components: List<WatchFaceComponent> = emptyList(),
    val schemaVersion: Int = 1,
    // Reservado para a Fase 5 (compatibilidade múltipla, ver plano 06_watchfaces_v2_arquitetura.md)
    // — ids de WearableSpec compatíveis além de `wearable`. Não lido/gravado pelo editor ainda.
    val compatibleWearableIds: List<String> = emptyList()
)

/**
 * Preenche xFrac/yFrac/widthFrac/heightFrac a partir de x/y/width/height ABSOLUTOS e do wearable
 * para o qual o componente foi posicionado. Não altera os campos absolutos.
 */
fun WatchFaceComponent.withFractional(wearable: WearableSpec): WatchFaceComponent {
    val w = wearable.widthPx.toFloat().coerceAtLeast(1f)
    val h = wearable.heightPx.toFloat().coerceAtLeast(1f)
    return copy(
        xFrac = x / w,
        yFrac = y / h,
        widthFrac = width / w,
        heightFrac = height / h
    )
}

/**
 * Recalcula x/y/width/height ABSOLUTOS para `wearable` a partir dos campos fracionários, quando
 * presentes. Componentes sem dado fracionário (ainda não migrados) voltam inalterados — nunca
 * lança nem produz posição incorreta por ausência de dado.
 */
fun WatchFaceComponent.resolvedFor(wearable: WearableSpec): WatchFaceComponent {
    if (xFrac == null || yFrac == null || widthFrac == null || heightFrac == null) return this
    val w = wearable.widthPx.toFloat()
    val h = wearable.heightPx.toFloat()
    return copy(
        x = xFrac * w,
        y = yFrac * h,
        width = widthFrac * w,
        height = heightFrac * h
    )
}

/**
 * Recalcula TODO o design (wearable + componentes) para um novo [target], recompondo fração a
 * partir das coordenadas ABSOLUTAS atuais (não depende de xFrac/yFrac já estarem preenchidos —
 * funciona mesmo em componentes recém-criados). Usado ao trocar o wearable no editor (Fase 5 do
 * plano 06_watchfaces_v2_arquitetura.md — antes o editor trocava só o campo `wearable`, deixando
 * os componentes com posição/tamanho da resolução ANTIGA) e ao aplicar um visor a um wearable
 * compatível diferente do principal.
 */
fun WatchFaceDesign.resolvedFor(target: WearableSpec): WatchFaceDesign = copy(
    wearable = target,
    components = components.map { it.withFractional(wearable).resolvedFor(target) }
)

/** Ids de wearable aos quais este design pode ser enviado: o principal + os compatíveis declarados. */
fun WatchFaceDesign.applicableWearableIds(): List<String> =
    (listOf(wearable.id) + compatibleWearableIds).distinct()

/**
 * [WearableSpec]s de [applicableWearableIds] que HOJE têm compilador real (ver
 * [WearableDefinitionCatalog]) — são os únicos que fazem sentido oferecer no diálogo de "enviar
 * para qual wearable" do gerenciador; os demais declaram compatibilidade de layout, mas não têm
 * como ser compilados/enviados ainda.
 */
fun WatchFaceDesign.applicableWearableTargets(): List<WearableSpec> =
    applicableWearableIds()
        .mapNotNull { id -> WearableCatalog.items.firstOrNull { it.id == id } ?: wearable.takeIf { it.id == id } }
        .filter { WearableDefinitionCatalog.hasRealCompiler(it.id) }

fun WatchFaceComponentDefinition.createComponent(
    wearable: WearableSpec,
    zIndex: Int
): WatchFaceComponent {
    val w = defaultWidth.coerceAtMost(wearable.widthPx * 0.9f)
    val h = defaultHeight.coerceAtMost(wearable.heightPx * 0.5f)
    return WatchFaceComponent(
        typeId = kind.id,
        variation = variation,
        label = name,
        x = ((wearable.widthPx - w) / 2f).coerceAtLeast(0f),
        y = ((wearable.heightPx - h) / 2f).coerceAtLeast(0f),
        width = w,
        height = h,
        color = defaultColor,
        font = defaultFont,
        alignment = defaultAlignment.value,
        zIndex = zIndex,
        styleTag = styleTag
    )
}

object WatchFaceJson {
    fun encodeComponents(components: List<WatchFaceComponent>): String {
        val arr = JSONArray()
        components.sortedBy { it.zIndex }.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("typeId", c.typeId)
                put("variation", c.variation)
                put("label", c.label)
                put("x", c.x)
                put("y", c.y)
                put("width", c.width)
                put("height", c.height)
                put("scale", c.scale)
                put("rotation", c.rotation)
                put("color", c.color)
                put("font", c.font)
                put("alpha", c.alpha)
                put("alignment", c.alignment)
                put("zIndex", c.zIndex)
                put("styleTag", c.styleTag)
                c.xFrac?.let { put("xFrac", it) }
                c.yFrac?.let { put("yFrac", it) }
                c.widthFrac?.let { put("widthFrac", it) }
                c.heightFrac?.let { put("heightFrac", it) }
                c.customText?.let { put("customText", it) }
            })
        }
        return arr.toString()
    }

    fun decodeComponents(json: String): List<WatchFaceComponent> = runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    WatchFaceComponent(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        typeId = o.optString("typeId", TimeHmKind.id),
                        variation = o.optInt("variation", 1),
                        label = o.optString("label", "Componente"),
                        x = o.optDouble("x", 0.0).toFloat(),
                        y = o.optDouble("y", 0.0).toFloat(),
                        width = o.optDouble("width", 120.0).toFloat(),
                        height = o.optDouble("height", 48.0).toFloat(),
                        scale = o.optDouble("scale", 1.0).toFloat(),
                        rotation = o.optDouble("rotation", 0.0).toFloat(),
                        color = o.optLong("color", 0xFFFFFFFF.toLong()),
                        font = o.optString("font", "Default"),
                        alpha = o.optDouble("alpha", 1.0).toFloat(),
                        alignment = o.optString("alignment", WatchFaceTextAlign.CENTER.value),
                        zIndex = o.optInt("zIndex", i),
                        styleTag = o.optString("styleTag", "classic"),
                        xFrac = if (o.has("xFrac")) o.optDouble("xFrac").toFloat() else null,
                        yFrac = if (o.has("yFrac")) o.optDouble("yFrac").toFloat() else null,
                        widthFrac = if (o.has("widthFrac")) o.optDouble("widthFrac").toFloat() else null,
                        heightFrac = if (o.has("heightFrac")) o.optDouble("heightFrac").toFloat() else null,
                        customText = if (o.has("customText")) o.optString("customText") else null
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun encodeIdList(ids: List<String>): String {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        return arr.toString()
    }

    fun decodeIdList(json: String): List<String> = runCatching {
        val arr = JSONArray(json)
        buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
    }.getOrDefault(emptyList())
}
