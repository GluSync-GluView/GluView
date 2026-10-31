package com.gluview.app.util

/**
 * Resolve se gráfico/widget/notificação devem desenhar em modo escuro — combina a preferência
 * "tema do gráfico" (separada do tema geral do app) com o tema do app (só usado em FOLLOW_APP)
 * e o tema do sistema (só usado quando o app está em SYSTEM). Função pura, sem Context/Compose:
 * chamável de dentro de @Composable (isSystemInDarkTheme() fornece systemDark) e do
 * GluSyncService (Configuration.UI_MODE_NIGHT_MASK fornece systemDark).
 */
object GraphTheme {
    const val LIGHT = "LIGHT"
    const val DARK = "DARK"
    const val FOLLOW_APP = "FOLLOW_APP"

    fun resolveDark(graphThemeMode: String, appThemeMode: String, systemDark: Boolean): Boolean =
        when (graphThemeMode) {
            DARK  -> true
            LIGHT -> false
            else /* FOLLOW_APP */ -> when (appThemeMode) {
                "DARK"  -> true
                "LIGHT" -> false
                else /* SYSTEM */ -> systemDark
            }
        }
}
