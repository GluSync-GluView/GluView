package com.gluview.app.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gluview.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.format.FormatStyle
import java.util.Locale

/** Formatadores únicos da Fase 1 do redesign visual (util/UiFormat.kt). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UiFormatTest {

    private val min = 60_000L
    private val now = 1_790_000_000_000L
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `faixas do tempo decorrido`() {
        assertEquals(Elapsed.Now, elapsedBetween(now - 59_999, now))
        assertEquals(Elapsed.Minutes(1), elapsedBetween(now - min, now))
        assertEquals(Elapsed.Minutes(59), elapsedBetween(now - 59 * min - 59_999, now))
        assertEquals(Elapsed.Hours(1), elapsedBetween(now - 60 * min, now))
        assertEquals(Elapsed.Hours(23), elapsedBetween(now - (24 * 60 - 1) * min, now))
        assertEquals(Elapsed.Days(1), elapsedBetween(now - 24 * 60 * min, now))
        assertEquals(Elapsed.Days(3), elapsedBetween(now - 3 * 24 * 60 * min - 5 * min, now))
    }

    @Test fun `relogio adiantado vira agora, nunca negativo`() {
        assertEquals(Elapsed.Now, elapsedBetween(now + 10 * min, now))
    }

    @Test fun `tempo decorrido em portugues com plural`() {
        RuntimeEnvironment.setQualifiers("pt-rBR")
        assertEquals("agora", context.formatElapsed(now, now))
        assertEquals("há 5 min", context.formatElapsed(now - 5 * min, now))
        assertEquals("há 2 h", context.formatElapsed(now - 125 * min, now))
        assertEquals("há 1 dia", context.formatElapsed(now - 24 * 60 * min, now))
        assertEquals("há 2 dias", context.formatElapsed(now - 2 * 24 * 60 * min, now))
    }

    @Test fun `tempo decorrido em ingles com plural`() {
        RuntimeEnvironment.setQualifiers("en-rUS")
        assertEquals("now", context.formatElapsed(now, now))
        assertEquals("1 day ago", context.formatElapsed(now - 24 * 60 * min, now))
        assertEquals("2 days ago", context.formatElapsed(now - 2 * 24 * 60 * min, now))
    }

    @Test fun `email quebra antes do arroba e dos pontos do dominio, sem mudar o texto visivel`() {
        val zw = '\u200B'
        val out = breakableEmail("rafael.teste@exemplo.com.br")
        // Parte local inteira: nada de "rafael." sozinho no fim da linha.
        assertEquals("rafael.teste${zw}@exemplo${zw}.com${zw}.br", out)
        assertEquals("rafael.teste@exemplo.com.br", out.replace(zw.toString(), ""))
        assertEquals("sem-arroba", breakableEmail("sem-arroba"))
    }

    @Test fun `sem leitura (epoch 0) vira traco em todos os formatadores`() {
        assertEquals(Elapsed.Unknown, elapsedBetween(0L, now))
        RuntimeEnvironment.setQualifiers("pt-rBR")
        assertEquals(NO_TIME, context.formatElapsed(0L, now))
        assertEquals(NO_TIME, formatDate(0L))
        assertEquals(NO_TIME, formatDateTime(0L, TimeFormat.H24))
        assertEquals(NO_TIME, formatTime(0L, TimeFormat.H24))
    }

    @Test fun `data e hora saem no mesmo fuso passado`() {
        val saved = java.util.TimeZone.getDefault()
        try {
            // Fuso da JVM diferente do pedido: a hora não pode vir do fuso do sistema.
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"))
            val ms = 1_791_072_000_000L // 2026-10-04T00:00:00Z = 03/10 21:00 em São Paulo
            assertEquals("04/10/2026 00:00",
                formatDateTime(ms, TimeFormat.H24, Locale.forLanguageTag("pt-BR"), ZoneId.of("UTC")))
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test fun `UiText Res compara por conteudo`() {
        assertEquals(UiText.Res(R.string.common_retry), UiText.Res(R.string.common_retry))
        assertEquals(UiText.Res(R.string.common_load_error_detail, "503"), UiText.Res(R.string.common_load_error_detail, "503"))
        assertNotEquals(UiText.Res(R.string.common_load_error_detail, "503"), UiText.Res(R.string.common_load_error_detail, "500"))
        assertEquals(UiText.Res(R.string.common_retry).hashCode(), UiText.Res(R.string.common_retry).hashCode())
    }

    @Test fun `UiText Res sem argumento nao formata (texto com porcento)`() {
        RuntimeEnvironment.setQualifiers("pt-rBR")
        // "Usar sem servidor (100% local)": getString(id, *vazio) quebraria com UnknownFormatConversion.
        val text = UiText.Res(R.string.login_use_local_only).asString(context)
        assertTrue(text.contains("100% local"))
    }

    @Test fun `data segue o idioma, nao um padrao fixo`() {
        val utc = ZoneId.of("UTC")
        val ms = 1_791_072_000_000L // 2026-10-04T00:00:00Z
        val pt = formatDate(ms, Locale.forLanguageTag("pt-BR"), utc, FormatStyle.SHORT)
        val en = formatDate(ms, Locale.US, utc, FormatStyle.SHORT)
        assertEquals("04/10/2026", pt)
        assertEquals("10/4/26", en)
        assertFalse(pt == en)
    }
}
