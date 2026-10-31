package com.gluview.app.visualaudit

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.gluview.app.ui.components.CHART_TEST_TAG

data class AuditIssue(val capture: String, val kind: String, val detail: String)

/**
 * Auditoria por captura, sobre a árvore de semântica do Compose (equivalente à auditoria de
 * corte do shoot.cjs do portal):
 *  - texto-cortado: o texto não cabe na caixa que recebeu (re-medido com TextMeasurer);
 *  - reticencias: idem, mas com TextOverflow.Ellipsis (aparece "…");
 *  - palavra-quebrada: a quebra de linha cai no meio de uma palavra ("Conecta/do");
 *  - fora-da-tela: texto ou controle que passa da borda esquerda/direita da tela;
 *  - alvo-pequeno: área de toque real < 48dp, ou controle visível < 32dp;
 *  - sem-rotulo: elemento clicável sem texto nem contentDescription;
 *  - texto-apos-grafico: Text/Icon composto depois do gráfico em bitmap (Image) e na faixa dele
 *    ou abaixo (regra do Moto Z2 Play; o gráfico no Canvas do Compose não entra).
 */
object VisualAudit {

    private const val MIN_TOUCH_DP = 48f
    /** Abaixo disto o controle é difícil de achar mesmo com a área de toque estendida pelo
     *  Compose (IconButton do M3 = 40dp e chip = 32dp passam; o "?" de 28dp e o ícone de 16dp não). */
    private const val MIN_VISIBLE_DP = 32f

    fun audit(compose: ComposeTestRule, captureId: String): List<AuditIssue> {
        val out = mutableListOf<AuditIssue>()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        val rootRight = root.boundsInRoot.right
        val chromeIds = HashSet<Int>()
        val bottomBarIds = HashSet<Int>()

        // Percorre em ordem de composição (profundidade, filhos na ordem).
        val ordered = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode, inChrome: Boolean, inBar: Boolean) {
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            val chrome = inChrome || tag == HARNESS_CHROME_TAG
            val bar = inBar || tag == HARNESS_BOTTOMBAR_TAG
            if (chrome) chromeIds += n.id
            if (bar) bottomBarIds += n.id
            ordered += n
            n.children.forEach { walk(it, chrome, bar) }
        }
        walk(root, false, false)
        val rootBottom = root.boundsInRoot.bottom
        // Fora da área visível (página rolada): sem bounds visíveis, mas com tamanho.
        fun offViewport(n: SemanticsNode): Boolean {
            val top = n.positionInRoot.y
            return top >= rootBottom - 1f || top + n.size.height <= 1f
        }

        fun label(n: SemanticsNode): String {
            val t = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            val cd = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            return (t ?: cd ?: "#${n.id}").replace('\n', ' ').take(60)
        }
        fun insideHorizontalScroll(n: SemanticsNode): Boolean {
            var p = n.parent
            while (p != null) {
                if (p.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null) return true
                p = p.parent
            }
            return false
        }

        for (n in ordered) {
            if (n.id in chromeIds) continue
            val hasText = n.config.getOrNull(SemanticsProperties.Text) != null

            if (hasText) textFit(n)?.let { kind -> out += AuditIssue(captureId, kind, label(n)) }

            val b = n.boundsInRoot
            if ((hasText || n.config.getOrNull(SemanticsActions.OnClick) != null) &&
                b.width > 0 && (b.left < -1f || b.right > rootRight + 1f) && !insideHorizontalScroll(n)
            ) {
                out += AuditIssue(captureId, "fora-da-tela",
                    "${label(n)} (${"%.0f".format(b.left / density)}..${"%.0f".format(b.right / density)}dp)")
            }
        }

        // Clicáveis: área de toque e rótulo (árvore mesclada = o que o TalkBack anuncia).
        compose.onAllNodes(hasClickAction(), useUnmergedTree = false).fetchSemanticsNodes().forEach { n ->
            if (n.id in chromeIds || isInChrome(n) || offViewport(n)) return@forEach
            // Tamanho VISÍVEL do controle: o Compose já estende a área de toque para 48dp quando
            // não há vizinho (touchBoundsInRoot), mas um alvo de 16–28dp continua difícil de achar
            // e de acertar perto de outro controle — é o critério do portal.
            val t = n.boundsInRoot
            val wDp = n.size.width / density; val hDp = n.size.height / density
            val touch = n.touchBoundsInRoot
            val touchW = touch.width / density; val touchH = touch.height / density
            // Abaixo da dobra (passa da borda do contêiner de rolagem vertical): o harness não rola,
            // então só a área de toque — recortada pela borda — fica sem valor e é pulada. O tamanho
            // visível (n.size, sem recorte, pega o espremido a 0dp) e o rótulo valem para todo clicável.
            val beyondFold = beyondVerticalScroll(n)
            if (!beyondFold && (touchW + 0.5f < MIN_TOUCH_DP || touchH + 0.5f < MIN_TOUCH_DP)) {
                out += AuditIssue(captureId, "alvo-pequeno",
                    "${label(n)} (toque ${"%.0f".format(touchW)}×${"%.0f".format(touchH)}dp)")
            } else if (wDp + 0.5f < MIN_VISIBLE_DP || hDp + 0.5f < MIN_VISIBLE_DP) {
                out += AuditIssue(captureId, "alvo-pequeno",
                    "${label(n)} (visível ${"%.0f".format(wDp)}×${"%.0f".format(hDp)}dp)")
            }
            val hasLabel = n.config.getOrNull(SemanticsProperties.Text) != null ||
                n.config.getOrNull(SemanticsProperties.ContentDescription) != null ||
                n.config.getOrNull(SemanticsProperties.EditableText) != null
            if (!hasLabel) out += AuditIssue(captureId, "sem-rotulo", "clicável #${n.id} em ${t.left.toInt()},${t.top.toInt()}")
        }

        // Regra do Moto Z2 Play: nada de Text/Icon composto depois do gráfico em BITMAP com drawText
        // (Image). O gráfico novo desenha direto no Canvas do Compose — caminho que o spike de 03/10
        // validou no Moto — e não entra na regra.
        val chartIdx = ordered.indexOfFirst {
            it.config.getOrNull(SemanticsProperties.TestTag) == CHART_TEST_TAG &&
                it.config.getOrNull(SemanticsProperties.Role) == Role.Image
        }
        if (chartIdx >= 0) {
            // Posição SEM recorte: com a página rolando (tela estreita, fonte grande) o gráfico
            // pode estar fora da área visível e o boundsInRoot recortado vira vazio.
            val chartTop = ordered[chartIdx].positionInRoot.y
            ordered.drop(chartIdx + 1)
                .filter { it.id !in chromeIds && it.id !in bottomBarIds }
                // Só o que fica na faixa do gráfico ou abaixo dele (o Scaffold do M3 põe a TopAppBar
                // depois do conteúdo na árvore, mas ela é desenhada acima — não é o caso do Moto).
                .filter { it.positionInRoot.y + it.size.height > chartTop + 1f }
                .filter {
                    it.config.getOrNull(SemanticsProperties.Text) != null ||
                        it.config.getOrNull(SemanticsProperties.ContentDescription) != null
                }
                .forEach { out += AuditIssue(captureId, "texto-apos-grafico", label(it)) }
        }
        return out
    }

    /**
     * Re-mede o texto com as mesmas entradas do layout (texto, estilo, maxLines, softWrap) na
     * largura que o nó recebeu. O TextLayoutResult que vem da semântica não serve sob o
     * Robolectric (largura do parágrafo = restrição máxima), por isso a medida própria.
     * Retorna "reticencias", "texto-cortado" ou null (cabe).
     */
    private fun textFit(n: SemanticsNode): String? {
        val results = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val li = results.firstOrNull()?.layoutInput ?: return null
        if (li.text.isBlank()) return null
        if (n.size.width <= 0 || n.size.height <= 0) return "texto-cortado" // espremido até sumir
        val measurer = TextMeasurer(li.fontFamilyResolver, li.density, li.layoutDirection)
        val boxed = measurer.measure(
            li.text, li.style, overflow = TextOverflow.Clip, softWrap = li.softWrap,
            maxLines = li.maxLines, constraints = Constraints(maxWidth = n.size.width)
        )
        val cut = boxed.didOverflowWidth || boxed.didOverflowHeight || boxed.size.height > n.size.height + 1
        if (cut) return if (li.overflow == TextOverflow.Ellipsis) "reticencias" else "texto-cortado"
        // Quebra de linha no meio da palavra ("Conecta/do", "Iníci/o"): letra dos dois lados.
        val text = li.text.text
        for (line in 0 until boxed.lineCount - 1) {
            val end = boxed.getLineEnd(line)
            if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
                return "palavra-quebrada"
            }
        }
        return null
    }

    /** O nó passa da borda de cima ou de baixo do contêiner de rolagem vertical mais próximo. */
    private fun beyondVerticalScroll(n: SemanticsNode): Boolean {
        var p = n.parent
        while (p != null) {
            if (p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) {
                val clip = p.boundsInRoot
                val top = n.positionInRoot.y
                return top < clip.top - 1f || top + n.size.height > clip.bottom + 1f
            }
            p = p.parent
        }
        return false
    }

    private fun isInChrome(n: SemanticsNode): Boolean {
        var p: SemanticsNode? = n
        while (p != null) {
            if (p.config.getOrNull(SemanticsProperties.TestTag) == HARNESS_CHROME_TAG) return true
            p = p.parent
        }
        return false
    }
}
