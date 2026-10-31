package com.gluview.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeMode { LIGHT, DARK, SYSTEM }

internal val DarkColorScheme = darkColorScheme(
    primary              = md_primaryD,
    onPrimary            = md_onPrimaryD,
    primaryContainer     = md_primaryContainerD,
    onPrimaryContainer   = md_onPrimaryContainerD,
    secondary            = md_secondaryD,
    onSecondary          = md_onSecondaryD,
    secondaryContainer   = md_secondaryContainerD,
    onSecondaryContainer = md_onSecondaryContainerD,
    background            = md_backgroundD,
    onBackground          = md_onBackgroundD,
    surface               = md_surfaceD,
    onSurface             = md_onSurfaceD,
    surfaceVariant        = md_surfaceVariantD,
    onSurfaceVariant      = md_onSurfaceVariantD,
    surfaceContainer      = md_surfaceContainerD,
    outline               = md_outlineD,
    outlineVariant        = md_outlineVariantD,
    error                 = md_errorD,
    onError               = md_onErrorD,
    errorContainer        = md_errorContainerD,
    onErrorContainer      = md_onErrorContainerD,
    tertiary              = md_tertiaryD,
    onTertiary            = md_onTertiaryD,
    tertiaryContainer     = md_tertiaryContainerD,
    onTertiaryContainer   = md_onTertiaryContainerD,
    surfaceDim            = md_surfaceDimD,
    surfaceBright         = md_surfaceBrightD,
    surfaceContainerLowest  = md_surfaceContainerLowestD,
    surfaceContainerLow     = md_surfaceContainerLowD,
    surfaceContainerHigh    = md_surfaceContainerHighD,
    surfaceContainerHighest = md_surfaceContainerHighestD,
    inverseSurface        = md_inverseSurfaceD,
    inverseOnSurface      = md_inverseOnSurfaceD,
    inversePrimary        = md_inversePrimaryD
)

internal val LightColorScheme = lightColorScheme(
    primary              = md_primaryL,
    onPrimary            = md_onPrimaryL,
    primaryContainer     = md_primaryContainerL,
    onPrimaryContainer   = md_onPrimaryContainerL,
    secondary            = md_secondaryL,
    onSecondary          = md_onSecondaryL,
    secondaryContainer   = md_secondaryContainerL,
    onSecondaryContainer = md_onSecondaryContainerL,
    background            = md_backgroundL,
    onBackground          = md_onBackgroundL,
    surface               = md_surfaceL,
    onSurface             = md_onSurfaceL,
    surfaceVariant        = md_surfaceVariantL,
    onSurfaceVariant      = md_onSurfaceVariantL,
    surfaceContainer      = md_surfaceContainerL,
    outline               = md_outlineL,
    outlineVariant        = md_outlineVariantL,
    error                 = md_errorL,
    onError               = md_onErrorL,
    errorContainer        = md_errorContainerL,
    onErrorContainer      = md_onErrorContainerL,
    tertiary              = md_tertiaryL,
    onTertiary            = md_onTertiaryL,
    tertiaryContainer     = md_tertiaryContainerL,
    onTertiaryContainer   = md_onTertiaryContainerL,
    surfaceDim            = md_surfaceDimL,
    surfaceBright         = md_surfaceBrightL,
    surfaceContainerLowest  = md_surfaceContainerLowestL,
    surfaceContainerLow     = md_surfaceContainerLowL,
    surfaceContainerHigh    = md_surfaceContainerHighL,
    surfaceContainerHighest = md_surfaceContainerHighestL,
    inverseSurface        = md_inverseSurfaceL,
    inverseOnSurface      = md_inverseOnSurfaceL,
    inversePrimary        = md_inversePrimaryL
)

// Cantos arredondados modernos (cards 16dp, diálogos 28dp)
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun GluViewTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Marca: usa o azul do logo por padrão. Material You (dynamic) só se pedido explicitamente.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK   -> true
        ThemeMode.LIGHT  -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else   -> LightColorScheme
    }

    CompositionLocalProvider(LocalAppDarkTheme provides isDark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = AppTypography,
            shapes      = AppShapes,
            content     = content
        )
    }
}

/**
 * Esquema de cores do conteúdo desenhado sobre um painel de [dark] fixo, independente do tema do
 * app — ex.: o painel do gráfico, que segue o "tema do gráfico" (escuro na Home, claro no seguidor).
 */
fun panelColorScheme(dark: Boolean): ColorScheme = if (dark) DarkColorScheme else LightColorScheme

/**
 * Tema efetivo do APP (não do sistema): true = escuro. Para quem precisa decidir fora das cores —
 * ex.: ícones das barras do sistema numa janela própria (AppModalBottomSheet).
 */
val LocalAppDarkTheme = staticCompositionLocalOf { false }
