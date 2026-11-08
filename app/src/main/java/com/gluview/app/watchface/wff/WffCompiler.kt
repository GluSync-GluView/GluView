package com.gluview.app.watchface.wff

import com.gluview.app.watchface.WatchFaceComponent
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.watchface.WatchFaceTextAlign
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tradutor do construtor de visor → Watch Face Format (plano 16, W1). Segundo destino do MESMO
 * desenho: o da Mi Band (MiBandCanvasCompiler → .bin) não é tocado.
 *
 * No WFF o relógio executa o mostrador sozinho; por isso cada componente vira uma de três coisas:
 *  - **nativo** (hora/data): elementos do próprio WFF, atualizados pelo relógio;
 *  - **complication** (glicemia, variação, hora da medição, gráfico): um slot cujo provedor padrão é
 *    a complication do GluView do relógio — a regra "dado velho vira --" vem pronta dela;
 *  - **texto fixo** (unidade, faixa-alvo, texto do usuário): compilado com o valor de agora.
 * O resto ainda não existe no Wear OS v1 e vai em [WffResult.unsupported] (o editor avisa — W4).
 *
 * Achado do W0: o validador oficial NÃO pega tipo padrão fora de `supportedTypes` (só falha ao
 * ativar), então este tradutor só gera combinações conferidas em [WffRules].
 */
object WffCompiler {

    /** Provedor padrão dos slots: a complication do GluView do relógio (mesmo pacote do celular). */
    fun gluviewProvider(appPackage: String) = "$appPackage/com.gluview.wear.complication.GlucoseComplicationService"

    data class Options(
        /** Pacote do app GluView (com.gluview.app ou com.gluview.app.demo) — dono da complication. */
        val appPackage: String,
        val unitLabel: String = "mg/dL",
        val targetLow: Int = 70,
        val targetHigh: Int = 180,
    )

    data class WffResult(val xml: String, val unsupported: List<WatchFaceComponent>, val slotCount: Int)

    /** Como cada tipo do construtor existe no WFF. */
    sealed interface Mapping {
        data class Clock(val format: String) : Mapping
        data class DateText(val template: String, val params: List<String>) : Mapping
        data class Slot(val type: String, val field: String) : Mapping
        data class Static(val text: (WatchFaceComponent, Options) -> String) : Mapping
        data object Unsupported : Mapping
    }

    fun mappingFor(typeId: String): Mapping = when (typeId) {
        // "hh" segue a preferência 12/24 h do próprio relógio (conferido no W0: 16:38 → "04:38" em 12 h).
        "time_hm" -> Mapping.Clock("hh:mm")
        "time_hms" -> Mapping.Clock("hh:mm:ss")
        "seconds" -> Mapping.Clock("ss")
        "date_dm" -> Mapping.DateText("%s/%s", listOf("DAY_Z", "MONTH_Z"))
        "date_dmy" -> Mapping.DateText("%s/%s/%s", listOf("DAY_Z", "MONTH_Z", "YEAR"))
        "date_ymd" -> Mapping.DateText("%s-%s-%s", listOf("YEAR", "MONTH_Z", "DAY_Z"))
        "date_md" -> Mapping.DateText("%s/%s", listOf("MONTH_Z", "DAY_Z"))
        "month" -> Mapping.DateText("%s", listOf("MONTH_S"))
        "year" -> Mapping.DateText("%s", listOf("YEAR"))
        "weekday" -> Mapping.DateText("%s", listOf("DAY_OF_WEEK_S"))
        // Complication do GluView: SHORT_TEXT = "125→" (texto) + "-2" (título); LONG_TEXT título = "às 16:01".
        "glucose_current" -> Mapping.Slot("SHORT_TEXT", "TEXT")
        "glucose_delta" -> Mapping.Slot("SHORT_TEXT", "TITLE")
        "last_reading_time" -> Mapping.Slot("LONG_TEXT", "TITLE")
        "glucose_chart" -> Mapping.Slot("SMALL_IMAGE", "SMALL_IMAGE")
        "glucose_unit" -> Mapping.Static { _, o -> o.unitLabel }
        "target_range" -> Mapping.Static { _, o -> "${o.targetLow}-${o.targetHigh}" }
        "fixed_text" -> Mapping.Static { c, _ -> c.customText.orEmpty() }
        else -> Mapping.Unsupported
    }

    fun isSupported(typeId: String): Boolean = mappingFor(typeId) !is Mapping.Unsupported

    fun compile(design: WatchFaceDesign, options: Options): WffResult {
        val w = design.wearable.widthPx
        val h = design.wearable.heightPx
        val unsupported = mutableListOf<WatchFaceComponent>()
        val body = StringBuilder()
        var slotId = 0

        for (c in design.components.sortedBy { it.zIndex }) {
            val box = Box.of(c, w, h)
            when (val m = mappingFor(c.typeId)) {
                is Mapping.Clock -> body.append(clock(c, box, m.format))
                is Mapping.DateText -> body.append(partText(c, box, template(m.template, m.params.map { "[$it]" }), sample = "00/00/0000"))
                is Mapping.Static -> {
                    val text = m.text(c, options)
                    if (text.isNotBlank()) body.append(partText(c, box, xmlEscape(text), sample = text))
                }
                is Mapping.Slot -> {
                    if (slotId >= WffRules.MAX_SLOTS) { unsupported += c; continue }
                    body.append(slot(c, box, slotId++, m, options))
                }
                Mapping.Unsupported -> unsupported += c
            }
        }

        val xml = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
            append("""<WatchFace width="$w" height="$h">""").append('\n')
            append("""  <Metadata key="CLOCK_TYPE" value="DIGITAL" />""").append('\n')
            append("""  <Metadata key="PREVIEW_TIME" value="10:08:32" />""").append('\n')
            append("""  <Scene backgroundColor="#ff000000">""").append('\n')
            append(body)
            append("  </Scene>\n")
            append("</WatchFace>\n")
        }
        return WffResult(xml, unsupported, slotId)
    }

    /** Retângulo do componente no canvas do relógio, já com escala e preso dentro da tela. */
    internal data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
        companion object {
            fun of(c: WatchFaceComponent, canvasW: Int, canvasH: Int): Box {
                val cw = (c.width * c.scale).roundToInt().coerceIn(1, canvasW)
                val ch = (c.height * c.scale).roundToInt().coerceIn(1, canvasH)
                val x = c.x.roundToInt().coerceIn(0, canvasW - cw)
                val y = c.y.roundToInt().coerceIn(0, canvasH - ch)
                return Box(x, y, cw, ch)
            }
        }
    }

    /** Mesmo critério de tamanho de fonte do editor (WatchFaceRenderer): limitado por altura e largura. */
    internal fun fontSize(c: WatchFaceComponent, box: Box, sample: String): Int {
        val heightFactor = when (c.styleTag) {
            "compact", "stacked" -> 0.40f
            "bold" -> 0.62f
            "accent" -> 0.58f
            else -> 0.50f
        }
        val byHeight = box.h * heightFactor * 1.6f
        val byWidth = box.w / (max(sample.length, 1) * 0.60f)
        return min(byHeight, byWidth).roundToInt().coerceIn(8, 240)
    }

    private fun clock(c: WatchFaceComponent, b: Box, format: String) = buildString {
        val sample = format.replace('h', '0').replace('m', '0').replace('s', '0')
        append("""    <DigitalClock x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}"${alpha(c)}>""").append('\n')
        append("""      <TimeText format="$format" align="${align(c)}" x="0" y="0" width="${b.w}" height="${b.h}">""").append('\n')
        append("""        <Font family="SYNC_TO_DEVICE" size="${fontSize(c, b, sample)}" color="${color(c.color)}"${weight(c)} />""").append('\n')
        append("      </TimeText>\n")
        append("    </DigitalClock>\n")
    }

    private fun partText(c: WatchFaceComponent, b: Box, content: String, sample: String) = buildString {
        append("""    <PartText x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}"${angle(c)}${alpha(c)}>""").append('\n')
        append("""      <Text align="${align(c)}">""").append('\n')
        append("""        <Font family="SYNC_TO_DEVICE" size="${fontSize(c, b, sample)}" color="${color(c.color)}"${weight(c)}>$content</Font>""").append('\n')
        append("      </Text>\n")
        append("    </PartText>\n")
    }

    private fun slot(c: WatchFaceComponent, b: Box, id: Int, m: Mapping.Slot, o: Options) = buildString {
        // Achado do W0: o tipo padrão PRECISA estar em supportedTypes (ver WffRules.checkSlots).
        append("""    <ComplicationSlot slotId="$id" x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" supportedTypes="${m.type}" isCustomizable="TRUE">""").append('\n')
        append("""      <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="${m.type}" primaryProvider="${gluviewProvider(o.appPackage)}" primaryProviderType="${m.type}" />""").append('\n')
        append("""      <BoundingBox x="0" y="0" width="${b.w}" height="${b.h}" />""").append('\n')
        append("""      <Complication type="${m.type}">""").append('\n')
        if (m.type == "SMALL_IMAGE") {
            append("""        <PartImage x="0" y="0" width="${b.w}" height="${b.h}">""").append('\n')
            append("""          <Image resource="[COMPLICATION.SMALL_IMAGE]" />""").append('\n')
            // Modo ambiente: some o gráfico (poucos pixels acesos, plano 05 §5 B1).
            append("""          <Variant mode="AMBIENT" target="alpha" value="0" />""").append('\n')
            append("        </PartImage>\n")
        } else {
            val sample = if (m.field == "TEXT") "000→" else "às 00:00"
            append("""        <PartText x="0" y="0" width="${b.w}" height="${b.h}"${angle(c)}${alpha(c)}>""").append('\n')
            append("""          <Text align="${align(c)}">""").append('\n')
            append("""            <Font family="SYNC_TO_DEVICE" size="${fontSize(c, b, sample)}" color="${color(c.color)}"${weight(c)}>${template("%s", listOf("[COMPLICATION.${m.field}]"))}</Font>""").append('\n')
            append("          </Text>\n")
            append("        </PartText>\n")
        }
        append("      </Complication>\n")
        append("    </ComplicationSlot>\n")
    }

    private fun template(fmt: String, params: List<String>) =
        "<Template>$fmt${params.joinToString("") { """<Parameter expression="$it" />""" }}</Template>"

    private fun align(c: WatchFaceComponent) = when (WatchFaceTextAlign.fromValue(c.alignment)) {
        WatchFaceTextAlign.START -> "START"
        WatchFaceTextAlign.CENTER -> "CENTER"
        WatchFaceTextAlign.END -> "END"
    }

    private fun weight(c: WatchFaceComponent) =
        if (c.styleTag in setOf("bold", "accent")) """ weight="BOLD"""" else ""

    private fun alpha(c: WatchFaceComponent): String {
        val a = (c.alpha.coerceIn(0f, 1f) * 255).roundToInt()
        return if (a >= 255) "" else """ alpha="$a""""
    }

    private fun angle(c: WatchFaceComponent): String {
        val r = c.rotation % 360f
        return if (r == 0f) "" else """ angle="${r.roundToInt()}""""
    }

    /** ARGB do construtor (Long 0xAARRGGBB) → "#aarrggbb" do WFF. */
    internal fun color(argb: Long): String = "#%08x".format(argb and 0xFFFFFFFFL)

    internal fun xmlEscape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
