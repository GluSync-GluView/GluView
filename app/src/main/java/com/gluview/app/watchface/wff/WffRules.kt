package com.gluview.app.watchface.wff

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regras que o GluView confere no XML gerado ALÉM do validador oficial (plano 16, W1). Existe
 * porque, no W0, o validador aprovou um mostrador que só quebrou na hora de ativar.
 * Devolve a lista de problemas (vazia = ok).
 */
object WffRules {
    /** Checklist de qualidade do Wear OS (WO-P2): no máximo 8 slots de complication. */
    const val MAX_SLOTS = 8

    fun check(xml: String): List<String> {
        val problems = mutableListOf<String>()
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrElse { return listOf("XML mal formado: ${it.message}") }
        val root = doc.documentElement
        if (root.tagName != "WatchFace") problems += "raiz não é <WatchFace>"
        val w = root.getAttribute("width").toIntOrNull() ?: 0
        val h = root.getAttribute("height").toIntOrNull() ?: 0
        if (w <= 0 || h <= 0) problems += "WatchFace sem width/height"

        val slots = root.getElementsByTagName("ComplicationSlot")
        if (slots.length > MAX_SLOTS) problems += "mais de $MAX_SLOTS slots (${slots.length})"
        val ids = mutableSetOf<String>()
        for (i in 0 until slots.length) {
            val s = slots.item(i) as Element
            val id = s.getAttribute("slotId")
            if (!ids.add(id)) problems += "slotId repetido: $id"
            val supported = s.getAttribute("supportedTypes").split(' ').filter { it.isNotBlank() }.toSet()
            val policy = s.getElementsByTagName("DefaultProviderPolicy").item(0) as? Element
            if (policy == null) problems += "slot $id sem DefaultProviderPolicy"
            else {
                for (attr in listOf("defaultSystemProviderType", "primaryProviderType")) {
                    val t = policy.getAttribute(attr)
                    if (t.isNotEmpty() && t !in supported) problems += "slot $id: $attr=$t fora de supportedTypes $supported"
                }
            }
            val types = s.getElementsByTagName("Complication")
            for (j in 0 until types.length) {
                val t = (types.item(j) as Element).getAttribute("type")
                if (t !in supported) problems += "slot $id: <Complication type=$t> fora de supportedTypes"
            }
        }

        // Todo elemento posicionado direto na cena precisa caber no canvas.
        val scene = root.getElementsByTagName("Scene").item(0) as? Element
        if (scene == null) problems += "sem <Scene>"
        else {
            val children = scene.childNodes
            for (i in 0 until children.length) {
                val e = children.item(i) as? Element ?: continue
                val x = e.getAttribute("x").toIntOrNull() ?: continue
                val y = e.getAttribute("y").toIntOrNull() ?: 0
                val ew = e.getAttribute("width").toIntOrNull() ?: 0
                val eh = e.getAttribute("height").toIntOrNull() ?: 0
                if (x < 0 || y < 0 || x + ew > w || y + eh > h) problems += "<${e.tagName}> fora da tela ($x,$y ${ew}x$eh)"
            }
        }
        return problems
    }
}
