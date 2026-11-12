package com.zcode.android.app.connect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.features.conversation.TranscriptPrinter
import java.io.FileOutputStream

/**
 * G56 (vc52 Phase 13): print to PDF — the desktop exports pages to PDF
 * (printPageToPdf); the phone's share ships text + Markdown only. The print
 * handoff rides the Android Printing framework: the transcript paginates
 * ([TranscriptPrinter.paginate], pure) and the adapter writes each page to the
 * destination PDF. Mono text on A4-ish pages (the app's dark theme never
 * bleeds into the print — the PDF is always print-black-on-white).
 */
object TranscriptPrintJob {

    private const val PAGE_WIDTH_POINTS = 595 // A4 @ 72dpi
    private const val PAGE_HEIGHT_POINTS = 842
    private const val MARGIN = 40f
    private const val LINE_HEIGHT = 12f
    private const val TEXT_SIZE = 9.5f

    fun print(context: Context, jobTitle: String, transcript: String, log: ZLog) {
        val pages = TranscriptPrinter.paginate(transcript)
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            log.warn("print.noManager", "no PRINT_SERVICE — the print handoff dropped")
            return
        }
        val jobName = jobTitle.ifBlank { "ZCode conversation" }
        printManager.print(
            jobName,
            TranscriptPrintAdapter(jobName, pages, log),
            PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .build(),
        )
    }

    private class TranscriptPrintAdapter(
        private val jobName: String,
        private val pages: List<String>,
        private val log: ZLog,
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder("$jobName.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages.size)
                .build()
            callback.onLayoutFinished(info, newAttributes != oldAttributes)
        }

        override fun onWrite(
            pagesToWrite: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                val document = PdfDocument()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = TEXT_SIZE
                }
                for ((index, lines) in pages.withIndex()) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onWriteCancelled()
                        document.close()
                        return
                    }
                    val pageInfo = PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH_POINTS, PAGE_HEIGHT_POINTS, index + 1,
                    ).create()
                    val page = document.startPage(pageInfo)
                    val canvas: Canvas = page.canvas
                    var y = MARGIN + TEXT_SIZE
                    for (line in lines.split("\n")) {
                        if (line.isNotBlank()) canvas.drawText(line.take(110), MARGIN, y, paint)
                        y += LINE_HEIGHT
                        if (y > PAGE_HEIGHT_POINTS - MARGIN) break
                    }
                    document.finishPage(page)
                }
                FileOutputStream(destination.fileDescriptor).use { out ->
                    document.writeTo(out)
                }
                document.close()
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                log.warn("print.writeFault", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
                callback.onWriteFailed(e.message)
            }
        }
    }
}
