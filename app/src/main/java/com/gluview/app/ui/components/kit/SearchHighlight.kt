package com.gluview.app.ui.components.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Busca em Ajustes (22/11/2026): ao tocar num resultado, a busca grava aqui o TÍTULO do item e
 * navega até a tela dele. Os componentes do kit (SwitchRow, SliderRow, NavRow, GluSection…) com o
 * mesmo título rolam até ficar à vista e piscam um fundo — sem chave por item, o título (o mesmo
 * texto de strings.xml que o índice usa) já identifica.
 *
 * O alvo vale por [TTL_MS]: se o item não aparecer na tela (escondido por uma condição), ele não
 * fica "armado" para piscar mais tarde em outro lugar.
 */
object SettingsSearchTarget {
    private const val TTL_MS = 6_000L

    private var title by mutableStateOf<String?>(null)
    private var setAt = 0L

    fun set(t: String) { title = t; setAt = System.currentTimeMillis() }
    fun clear() { title = null }

    /** O alvo atual, se ainda valer. Lido dentro de composição (estado observável). */
    fun current(): String? = title?.takeIf { System.currentTimeMillis() - setAt < TTL_MS }

    fun matches(candidate: String, target: String): Boolean =
        candidate.trim().equals(target.trim(), ignoreCase = true)
}

/** Destaque do item procurado: rola até ele e pisca o fundo duas vezes. No-op no resto do tempo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.searchHighlight(title: String): Modifier {
    val target = SettingsSearchTarget.current()
    if (target == null || !SettingsSearchTarget.matches(title, target)) return this
    val requester = remember { BringIntoViewRequester() }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(250)   // deixa a tela medir e a rolagem existir
        requester.bringIntoView()
        repeat(2) {
            pulse.animateTo(1f, tween(320))
            pulse.animateTo(0f, tween(420))
        }
        SettingsSearchTarget.clear()
    }
    val color = MaterialTheme.colorScheme.primary
    return this
        .bringIntoViewRequester(requester)
        .drawBehind {
            if (pulse.value > 0f) {
                drawRoundRect(color.copy(alpha = 0.22f * pulse.value), cornerRadius = CornerRadius(12.dp.toPx()))
            }
        }
}
