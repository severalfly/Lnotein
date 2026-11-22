package com.lnotein.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.lnotein.app.data.Store
import com.lnotein.app.model.Notebook
import com.lnotein.app.model.Page
import com.lnotein.app.model.PaperType
import com.lnotein.app.model.Stroke
import com.lnotein.app.model.StrokePoint
import com.lnotein.app.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object PdfSupport {

    private fun exportRect(page: Page): RectF {
        if (page.paper != PaperType.INFINITE) {
            return RectF(0f, 0f, page.width, page.height)
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in page.strokes) for (p in s.points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        if (minX > maxX) { minX = 0f; minY = 0f; maxX = 595f; maxY = 842f }
        minX -= 32f; minY -= 32f; maxX += 32f; maxY += 32f
        val w = (maxX - minX).coerceAtLeast(595f)
        val h = (maxY - minY).coerceAtLeast(842f)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    private fun drawPaperAndroid(c: android.graphics.Canvas, page: Page) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(189, 189, 189)
            strokeWidth = 1f
        }
        when (page.paper) {
            PaperType.A4_LINED -> {
                var y = 40f
                while (y < page.height) {
                    c.drawLine(0f, y, page.width, y, paint); y += 40f
                }
            }
            PaperType.A4_GRID -> {
                var y = 40f
                while (y < page.height) {
                    c.drawLine(0f, y, page.width, y, paint); y += 40f
                }
                var x = 40f
                while (x < page.width) {
                    c.drawLine(x, 0f, x, page.height, paint); x += 40f
                }
            }
            PaperType.A4_DOTS -> {
                paint.color = android.graphics.Color.rgb(158, 158, 158)
                var y = 40f
                while (y < page.height) {
                    var x = 40f
                    while (x < page.width) {
                        c.drawCircle(x, y, 1.5f, paint); x += 40f
                    }
                    y += 40f
                }
            }
            else -> {}
        }
    }

    private fun buildPath(points: List<StrokePoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
            return path
        }
        var i = 1
        while (i < points.size - 1) {
            val midX = (points[i].x + points[i + 1].x) / 2f
            val midY = (points[i].y + points[i + 1].y) / 2f
            path.quadTo(points[i].x, points[i].y, midX, midY)
            i++
        }
        val last = points[points.size - 1]
        path.lineTo(last.x, last.y)
        return path
    }

    private fun drawStrokeAndroid(c: android.graphics.Canvas, s: Stroke) {
        if (s.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = s.color
            if (s.tool == Tool.MARKER) alpha = 90
        }
        if (s.points.size < 2) {
            val p = s.points[0]
            paint.style = Paint.Style.FILL
            c.drawCircle(p.x, p.y, s.width / 2f * (0.6f + 0.8f * p.pressure), paint)
            return
        }
        val avg = s.points.map { it.pressure }.average().toFloat()
        paint.strokeWidth = s.width * (0.6f + 0.8f * avg)
        c.drawPath(buildPath(s.points), paint)
    }

    /** 导入 PDF：每个 PDF 页生成一个笔记页，返回新增页数 */
    suspend fun importPdf(context: Context, uri: Uri, notebook: Notebook): Int =
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext 0
            var added = 0
            try {
                val renderer = PdfRenderer(pfd)
                for (i in 0 until renderer.pageCount) {
                    val pdfPage = renderer.openPage(i)
                    val scale = 2f
                    val bmp = Bitmap.createBitmap(
                        (pdfPage.width * scale).toInt().coerceAtLeast(1),
                        (pdfPage.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    pdfPage.render(
                        bmp,
                        null,
                        Matrix().apply { postScale(scale, scale) },
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    val newPage = Page(
                        id = UUID.randomUUID().toString(),
                        paper = PaperType.A4_BLANK,
                        width = pdfPage.width.toFloat(),
                        height = pdfPage.height.toFloat()
                    )
                    val fname = "bg_${newPage.id}.png"
                    Store.bgFile(context, notebook.id, fname).outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 90, it)
                    }
                    newPage.backgroundImage = fname
                    notebook.pages.add(newPage)
                    pdfPage.close()
                    bmp.recycle()
                    added++
                }
                renderer.close()
            } catch (_: Exception) {
            } finally {
                pfd.close()
            }
            added
        }

    /** 矢量导出整个笔记本为 PDF */
    suspend fun exportPdf(context: Context, notebook: Notebook, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val doc = PdfDocument()
                notebook.pages.forEachIndexed { idx, page ->
                    val rect = exportRect(page)
                    val info = PdfDocument.PageInfo.Builder(
                        rect.width().toInt().coerceAtLeast(1),
                        rect.height().toInt().coerceAtLeast(1),
                        idx + 1
                    ).create()
                    val pdfPage = doc.startPage(info)
                    val c = pdfPage.canvas
                    c.save()
                    c.translate(-rect.left, -rect.top)
                    val bgFile = page.backgroundImage?.let { Store.bgFile(context, notebook.id, it) }
                    if (bgFile != null && bgFile.exists()) {
                        val bmp = android.graphics.BitmapFactory.decodeFile(bgFile.absolutePath)
                        if (bmp != null) {
                            c.drawBitmap(bmp, null, RectF(0f, 0f, page.width, page.height), null)
                            bmp.recycle()
                        }
                    } else {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            style = Paint.Style.FILL
                        }
                        c.drawRect(0f, 0f, page.width, page.height, paint)
                        drawPaperAndroid(c, page)
                    }
                    page.strokes.forEach { drawStrokeAndroid(c, it) }
                    c.restore()
                    doc.finishPage(pdfPage)
                }
                context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
                doc.close()
                true
            } catch (_: Exception) {
                false
            }
        }
}
