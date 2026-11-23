package com.vexa.ai

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileGenerator {

    data class GeneratedFile(
        val file: File,
        val mimeType: String,
        val displayName: String
    )

    private fun outDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "VexaFiles")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun createFile(ctx: Context, extRaw: String, baseName: String, content: String): GeneratedFile {
        val ext = extRaw.lowercase(Locale.ROOT).trim().removePrefix(".")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeBase = baseName.replace(Regex("[^a-zA-Z0-9_\\-\\u0600-\\u06FF]"), "_")
            .take(28).ifBlank { "Vexa_Doc" }
        val fileName = "${safeBase}_$stamp.$ext"
        val outFile = File(outDir(ctx), fileName)

        if (ext == "pdf") {
            writePdf(outFile, content)
            return GeneratedFile(outFile, "application/pdf", fileName)
        }
        if (ext == "csv") {
            FileOutputStream(outFile).use { fos ->
                fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                fos.write(content.toByteArray(Charsets.UTF_8))
            }
            return GeneratedFile(outFile, "text/csv", fileName)
        }
        outFile.writeText(content, Charsets.UTF_8)
        val mime = when (ext) {
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "md" -> "text/markdown"
            "py", "kt", "java", "js", "ts", "cpp", "c", "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
        return GeneratedFile(outFile, mime, fileName)
    }

    private fun writePdf(file: File, text: String) {
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 44f
        val contentWidth = (pageWidth - margin * 2).toInt()

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10A37F")
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 12.5f
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 9.5f
        }

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, bodyPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1.25f)
            .build()

        var currentLine = 0
        var pageNum = 1
        while (currentLine < layout.lineCount || pageNum == 1) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            val canvas = page.canvas
            var yCursor = margin + 16f
            if (pageNum == 1) {
                canvas.drawText("Vexa AI — Document", margin, yCursor, titlePaint)
                yCursor += 28f
            }
            val maxY = pageHeight - margin - 24f
            canvas.save()
            canvas.translate(margin, yCursor)
            val startTop = if (currentLine < layout.lineCount) layout.getLineTop(currentLine) else 0
            while (currentLine < layout.lineCount) {
                val lineBottom = layout.getLineBottom(currentLine) - startTop
                if (yCursor + lineBottom > maxY) break
                val lineStr = text.substring(layout.getLineStart(currentLine), layout.getLineEnd(currentLine)).trimEnd('\n')
                val baseline = (layout.getLineBaseline(currentLine) - startTop).toFloat()
                canvas.drawText(lineStr, 0f, baseline, bodyPaint)
                currentLine++
            }
            canvas.restore()
            canvas.drawText("Page $pageNum | Vexa AI", margin, pageHeight - 22f, footerPaint)
            doc.finishPage(page)
            pageNum++
            if (currentLine >= layout.lineCount) break
        }
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
    }

    fun openFile(ctx: Context, gf: GeneratedFile): Boolean = try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", gf.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, gf.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "Open ${gf.displayName}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: Exception) {
        false
    }

    fun shareFile(ctx: Context, gf: GeneratedFile): Boolean = try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", gf.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = gf.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share ${gf.displayName}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: Exception) {
        false
    }
}
