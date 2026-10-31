package com.gluview.app.ui.components.kit

import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.gluview.app.ui.theme.LocalAppDarkTheme

/*
 * Folha inferior do kit (redesign visual, Fase 1). O ModalBottomSheet do M3 abre uma janela
 * própria que cobre a tela, e nela os ícones das barras do sistema seguiam o tema do SISTEMA:
 * com app escuro e sistema claro (ou o contrário), relógio e bateria sumiam com o sheet aberto
 * — o mesmo defeito que a MainActivity corrige para a janela principal.
 */

/** ModalBottomSheet com os ícones das barras do sistema no contraste do tema do app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState) {
        val view = LocalView.current
        val dark = LocalAppDarkTheme.current
        // O M3 grava a aparência das barras ao criar a janela do sheet; este efeito roda depois e prevalece.
        DisposableEffect(view, dark) {
            view.dialogWindow()?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            onDispose {}
        }
        content()
    }
}

/** Janela do diálogo que contém esta view (a do sheet), subindo pela hierarquia. */
private fun View.dialogWindow(): Window? {
    var v: Any? = this
    while (v is View) {
        if (v is DialogWindowProvider) return v.window
        v = v.parent
    }
    return null
}
