package com.gluview.app.ui.theme

import androidx.compose.ui.graphics.Color

// Glucose zone palette
val GlucoseUrgentLow  = Color(0xFFD32F2F)
val GlucoseLow        = Color(0xFFFF6F00)
val GlucoseNormal     = Color(0xFF2E7D32)
val GlucoseHigh       = Color(0xFFE65100)
val GlucoseUrgentHigh = Color(0xFFB71C1C)
val GlucoseQuickDrop  = Color(0xFFFF8F00)
val GlucoseStale      = Color(0xFF757575)

// F2/T13 — destaques distintos e legíveis no darkmode:
//   sensor (conexão/estado) = verde claro · calibração = ciano. Antes ambos usavam o mesmo verde escuro.
val SensorAccent      = Color(0xFF66BB6A)
val CalibrationAccent = Color(0xFF4DD0E1)

// ── Marca (azul do logo #2068C0) — paleta Material 3 tonal, clara e escura ──────────
// Tema central: muda TODAS as telas. O Card padrão do M3 usa surfaceContainerHighest; o GluCard
// do kit usa surfaceContainer com borda (ver ui/components/kit).
val BrandBlue = Color(0xFF2068C0)

// Light
val md_primaryL            = Color(0xFF1F61C2)
val md_onPrimaryL          = Color(0xFFFFFFFF)
val md_primaryContainerL   = Color(0xFFD7E2FF)
val md_onPrimaryContainerL = Color(0xFF001A42)
val md_secondaryL          = Color(0xFF565E71)
val md_onSecondaryL        = Color(0xFFFFFFFF)
val md_secondaryContainerL = Color(0xFFDAE2F9)
val md_onSecondaryContainerL = Color(0xFF131C2B)
val md_backgroundL         = Color(0xFFFAF9FD)
val md_onBackgroundL       = Color(0xFF1A1B1F)
val md_surfaceL            = Color(0xFFFAF9FD)
val md_onSurfaceL          = Color(0xFF1A1B1F)
val md_surfaceVariantL     = Color(0xFFE1E2EC)
val md_onSurfaceVariantL   = Color(0xFF44474F)
val md_surfaceContainerL   = Color(0xFFEEEDF4)
val md_outlineL            = Color(0xFF74777F)
val md_outlineVariantL     = Color(0xFFC4C6D0)
val md_errorL              = Color(0xFFBA1A1A)
// Papéis que o tema não definia e caíam no baseline roxo do Material 3 (Card padrão, diálogos,
// balão, containers de erro). Tons das paletas tonais da MESMA semente (#2068C0): neutro com croma
// 5,8 (reproduz surface/surfaceContainer acima), terciário hue+60/croma 24, erro padrão do M3.
val md_onErrorL                 = Color(0xFFFFFFFF)
val md_errorContainerL          = Color(0xFFFFDAD6)
val md_onErrorContainerL        = Color(0xFF410002)
val md_tertiaryL                = Color(0xFF6F5675)
val md_onTertiaryL              = Color(0xFFFFFFFF)
val md_tertiaryContainerL       = Color(0xFFF9D8FD)
val md_onTertiaryContainerL     = Color(0xFF28132F)
val md_surfaceDimL              = Color(0xFFD9DAE0)
val md_surfaceBrightL           = Color(0xFFFAF9FD)
val md_surfaceContainerLowestL  = Color(0xFFFFFFFF)
val md_surfaceContainerLowL     = Color(0xFFF3F3F9)
val md_surfaceContainerHighL    = Color(0xFFE7E8EE)
val md_surfaceContainerHighestL = Color(0xFFE1E2E8)
val md_inverseSurfaceL          = Color(0xFF2E3035)
val md_inverseOnSurfaceL        = Color(0xFFF0F0F7)
val md_inversePrimaryL          = Color(0xFFACC7FF)

// Dark
val md_primaryD            = Color(0xFFACC7FF)
val md_onPrimaryD          = Color(0xFF002E6A)
val md_primaryContainerD   = Color(0xFF004494)
val md_onPrimaryContainerD = Color(0xFFD7E2FF)
val md_secondaryD          = Color(0xFFBEC6DC)
val md_onSecondaryD        = Color(0xFF283141)
val md_secondaryContainerD = Color(0xFF3E4759)
val md_onSecondaryContainerD = Color(0xFFDAE2F9)
val md_backgroundD         = Color(0xFF111318)
val md_onBackgroundD       = Color(0xFFE3E1E6)
val md_surfaceD            = Color(0xFF111318)
val md_onSurfaceD          = Color(0xFFE3E1E6)
val md_surfaceVariantD     = Color(0xFF44474F)
val md_onSurfaceVariantD   = Color(0xFFC4C6D0)
val md_surfaceContainerD   = Color(0xFF1D2024)
val md_outlineD            = Color(0xFF8E9099)
val md_outlineVariantD     = Color(0xFF44474F)
val md_errorD              = Color(0xFFFFB4AB)
val md_onErrorD                 = Color(0xFF690005)
val md_errorContainerD          = Color(0xFF93000A)
val md_onErrorContainerD        = Color(0xFFFFDAD6)
val md_tertiaryD                = Color(0xFFDCBCE1)
val md_onTertiaryD              = Color(0xFF3E2845)
val md_tertiaryContainerD       = Color(0xFF563E5C)
val md_onTertiaryContainerD     = Color(0xFFF9D8FD)
val md_surfaceDimD              = Color(0xFF111318)
val md_surfaceBrightD           = Color(0xFF37393E)
val md_surfaceContainerLowestD  = Color(0xFF0C0E12)
val md_surfaceContainerLowD     = Color(0xFF191C20)
val md_surfaceContainerHighD    = Color(0xFF282A2F)
val md_surfaceContainerHighestD = Color(0xFF32353A)
val md_inverseSurfaceD          = Color(0xFFE1E2E8)
val md_inverseOnSurfaceD        = Color(0xFF2E3035)
val md_inversePrimaryD          = Color(0xFF1F61C2)
