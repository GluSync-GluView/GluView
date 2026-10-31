package com.gluview.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Fonte de "agora" das telas (idade da leitura, banner sem sinal, tick e borda direita do gráfico).
 * Em produção é o relógio real; o harness visual fornece um relógio fixo para a captura sair igual
 * em toda execução (ver app/src/test/java/com/gluview/app/visualaudit/).
 */
val LocalClock = staticCompositionLocalOf<() -> Long> { { System.currentTimeMillis() } }
