package com.gluview.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Estilos do M3 de base: os 8 abaixo eram herdados em silêncio do Typography() padrão. Ficam
// explícitos aqui com os MESMOS valores (nenhuma tela muda na Fase 1) para que os 12 estilos usados
// pelo app venham todos deste arquivo; ajuste fino entra pela fase de cada tela.
private val M3Baseline = Typography()

/**
 * Tipografia do app: os 12 estilos que as telas usam (+ displayLarge, o valor de glicemia).
 * Nenhum tamanho literal fora deste arquivo em código novo.
 */
val AppTypography = Typography(
    // Valor atual de glicemia (GluType.GlucoseValue); não usar para outra coisa.
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize   = 72.sp,
        lineHeight = 80.sp,
        letterSpacing = (-1).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 32.sp,
        lineHeight = 40.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize   = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize   = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    headlineMedium = M3Baseline.headlineMedium,
    headlineSmall  = M3Baseline.headlineSmall,
    titleLarge     = M3Baseline.titleLarge,
    titleSmall     = M3Baseline.titleSmall,
    bodyLarge      = M3Baseline.bodyLarge,
    bodySmall      = M3Baseline.bodySmall,
    labelLarge     = M3Baseline.labelLarge,
    labelMedium    = M3Baseline.labelMedium
)

/**
 * Tokens de glicemia, fora da escala do M3 (redesign visual, Fase 1). Substituem os 72/64/48sp
 * literais: Home, seguidor e acompanhamento passam a usar o MESMO valor (hoje 72 × 64) quando
 * cada tela migrar (Fase 3). Os demais textos usam só MaterialTheme.typography.
 */
object GluType {
    /** Valor atual de glicemia (S32: 72sp, legível a 1 m no S23 FE). Um token só: é o displayLarge. */
    val GlucoseValue: TextStyle get() = AppTypography.displayLarge
    /** Seta de tendência ao lado do valor. */
    val GlucoseArrow = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize   = 48.sp,
        lineHeight = 56.sp
    )
}
