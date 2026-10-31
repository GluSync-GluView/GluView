package com.gluview.app.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Imagem que chega pronta do servidor em base64 (o QR do Pix de doação). Fica fora de ui/screen
 * porque lá o GraphArchitectureTest barra asImageBitmap — a regra é sobre gráfico desenhado em
 * bitmap, não sobre imagem recebida. null = base64 inválido (a tela segue com o copia e cola).
 */
@Composable
fun rememberBase64Image(base64: String): ImageBitmap? = remember(base64) {
    runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
