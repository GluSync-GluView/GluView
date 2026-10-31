package com.gluview.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.dto.SummaryReportDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences
) {

    private val exportDir: File get() {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Texto no idioma escolhido em Ajustes (não o do sistema: no Android < 13 o contexto da
     * aplicação não segue o AppCompatDelegate), na unidade e no formato de hora do usuário.
     */
    private suspend fun texts(): Pair<ReportExportText, Context> {
        val lang = preferences.appLanguage.first()
        val locale = Locale.forLanguageTag(lang)
        val localized = context.withAppLanguage(lang)
        val text = ReportExportText(
            localized, locale,
            GlucoseUnit.fromString(preferences.glucoseUnit.first()),
            TimeFormat.fromString(preferences.timeFormat.first())
        )
        return text to localized
    }

    // S24 — PDF simples (texto). I/O no dispatcher IO para não bloquear a Main.
    suspend fun exportPdf(report: SummaryReportDto) = withContext(Dispatchers.IO) {
        val (text, _) = texts()
        val document = PdfDocument()
        val paint      = Paint().apply { textSize = 14f; color = Color.BLACK }
        val titlePaint = Paint().apply { textSize = 18f; color = Color.BLACK; isFakeBoldText = true }

        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let(document::finishPage)
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create()) // A4
            y = 50f
        }
        newPage()
        text.pdfLines(report).forEachIndexed { i, (line, isTitle) ->
            val p = if (isTitle) titlePaint else paint
            if (isTitle && i > 0) y += 14f
            // Nova página antes de passar da margem de baixo (o resumo de vários meses cabia mal).
            if (y + p.textSize > 842 - 40) newPage()
            page!!.canvas.drawText(line, 40f, y, p)
            y += p.textSize + 6f
        }
        page?.let(document::finishPage)

        val file = File(exportDir, "${text.fileName}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()

        share(file, "application/pdf", text.chooserTitle)
    }

    // S24 — CSV com o resumo e o pivô. I/O no dispatcher IO para não bloquear a Main.
    suspend fun exportCsv(report: SummaryReportDto) = withContext(Dispatchers.IO) {
        val (text, _) = texts()
        val file = File(exportDir, "${text.fileName}.csv")
        // BOM: o Excel só reconhece UTF-8 (acentos) com ele.
        OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8).use { w -> w.write("\uFEFF" + text.csv(report)) }
        share(file, "text/csv", text.chooserTitle)
    }

    private fun share(file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
