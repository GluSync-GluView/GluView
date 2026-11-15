package com.gluview.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR code desenhado direto no Canvas (sem bitmap), sempre preto no branco com margem — o padrão
 * que as câmeras leem melhor, também no tema escuro. Plano 21, F2 (pareamento do espelho).
 */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        drawRect(Color.White)
        val n = matrix.width
        val cell = minOf(size.width, size.height) / n
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
