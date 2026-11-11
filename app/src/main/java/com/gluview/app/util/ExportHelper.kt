package com.gluview.app.util

import android.content.ClipData
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
    // Plano 17: [diary] = registros do diário do mesmo período, que entram no fim do relatório.
    suspend fun exportPdf(report: SummaryReportDto, diary: List<com.gluview.app.data.local.entity.EventLogEntity> = emptyList(),
                          diaryRange: Pair<java.time.LocalDate, java.time.LocalDate>? = null) = withContext(Dispatchers.IO) {
        val (text, localized) = texts()
        val lines = text.pdfLines(report) + if (diary.isNotEmpty() && diaryRange != null)
            diaryText(localized).lines(diary, diaryRange.first, diaryRange.second, includeTitle = false) else emptyList()
        val file = File(exportDir, "${text.fileName}.pdf")
        writePdf(lines, file)
        share(file, "application/pdf", text.chooserTitle)
    }

    /** PDF só do diário (plano 17, F1.7) — funciona sem conta e sem internet. */
    suspend fun exportDiaryPdf(entries: List<com.gluview.app.data.local.entity.EventLogEntity>,
                               first: java.time.LocalDate, last: java.time.LocalDate) = withContext(Dispatchers.IO) {
        val (_, localized) = texts()
        val file = File(exportDir, localized.getString(com.gluview.app.R.string.diary_pdf_file) + ".pdf")
        writePdf(diaryText(localized).lines(entries, first, last), file)
        share(file, "application/pdf", localized.getString(com.gluview.app.R.string.export_chooser))
    }

    private suspend fun diaryText(localized: Context) = DiaryReportText(
        localized, GlucoseUnit.fromString(preferences.glucoseUnit.first()), TimeFormat.fromString(preferences.timeFormat.first())
    )

    private fun writePdf(lines: List<Pair<String, Boolean>>, file: File) {
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
        lines.forEachIndexed { i, (line, isTitle) ->
            val p = if (isTitle) titlePaint else paint
            if (isTitle && i > 0) y += 14f
            // Nova página antes de passar da margem de baixo (o resumo de vários meses cabia mal).
            // Plano 17: linha longa (registro do diário com observação) quebra em vez de sair da página.
            wrap(line, p, 595f - 80f).forEach { part ->
                if (y + p.textSize > 842 - 40) newPage()
                page!!.canvas.drawText(part, 40f, y, p)
                y += p.textSize + 6f
            }
        }
        page?.let(document::finishPage)
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
    }

    /** Quebra [line] em pedaços que cabem em [maxWidth] (por palavra; recuo inicial mantido). */
    private fun wrap(line: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(line) <= maxWidth) return listOf(line)
        val indent = line.takeWhile { it == ' ' }
        val out = mutableListOf<String>()
        var current = StringBuilder()
        line.trim().split(' ').forEach { word ->
            val candidate = if (current.isEmpty()) indent + word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                out += current.toString()
                current = StringBuilder("$indent    $word")
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
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
            // A tela de compartilhar (Android 10+, roda como sistema) só lê o arquivo para a prévia
            // se a permissão vier pelo ClipData; só com EXTRA_STREAM dava Permission Denial no log.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
