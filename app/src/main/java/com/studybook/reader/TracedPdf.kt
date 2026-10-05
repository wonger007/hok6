package com.studybook.reader

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.ByteArrayOutputStream

/** A copy of a PDF with the tracing drawn into its pages as lines, so it stays sharp when printed or zoomed. */
object TracedPdf {

    /** Returns the PDF with [strokes] (by page, numbered from 0) drawn on; the original file is not changed. */
    fun render(context: Context, source: Uri, strokes: Map<Int, List<Stroke>>): ByteArray {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(source) ?: error("can't read the PDF")
        // Read fully before writing, so the result can replace the original file.
        val doc = input.use { PDDocument.load(it) }
        doc.use {
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            for ((index, page) in doc.pages.withIndex()) {
                val lines = strokes[index].orEmpty()
                if (lines.isEmpty()) continue
                val box = page.cropBox
                val rotation = ((page.rotation % 360) + 360) % 360
                // Stroke points are divided by the width of the page as shown on screen (after rotation).
                val shownWidth = if (rotation == 90 || rotation == 270) box.height else box.width
                fun toPdf(x: Float, y: Float): Pair<Float, Float> {
                    val dx = x * shownWidth
                    val dy = y * shownWidth
                    return when (rotation) {
                        90 -> box.lowerLeftX + dy to box.lowerLeftY + dx
                        180 -> box.upperRightX - dx to box.lowerLeftY + dy
                        270 -> box.upperRightX - dy to box.upperRightY - dx
                        else -> box.lowerLeftX + dx to box.upperRightY - dy
                    }
                }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { out ->
                    out.setLineCapStyle(1)
                    out.setLineJoinStyle(1)
                    for (stroke in lines) {
                        val p = stroke.points
                        if (p.size < 2) continue
                        out.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                            strokingAlphaConstant = Color.alpha(stroke.color) / 255f
                        })
                        out.setStrokingColor(Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color))
                        out.setLineWidth(stroke.width * shownWidth)
                        val (sx, sy) = toPdf(p[0], p[1])
                        out.moveTo(sx, sy)
                        // A single tap is a dot: a zero-length line with round ends.
                        if (p.size < 4) out.lineTo(sx, sy)
                        var i = 2
                        while (i + 1 < p.size) {
                            val (x, y) = toPdf(p[i], p[i + 1])
                            out.lineTo(x, y)
                            i += 2
                        }
                        out.stroke()
                    }
                }
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }
}
