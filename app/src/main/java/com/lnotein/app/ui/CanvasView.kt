package com.lnotein.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DStrokeStyle
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lnotein.app.data.Store
import com.lnotein.app.model.Page
import com.lnotein.app.model.PaperType
import com.lnotein.app.model.Stroke
import com.lnotein.app.model.StrokePoint
import com.lnotein.app.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------- 页面渲染 ----------

fun buildSmoothPath(points: List<StrokePoint>): Path {
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
        path.quadraticBezierTo(points[i].x, points[i].y, midX, midY)
        i++
    }
    val last = points[points.size - 1]
    path.lineTo(last.x, last.y)
    return path
}

fun DrawScope.drawStroke(s: Stroke) {
    if (s.points.isEmpty()) return
    if (s.points.size < 2) {
        val p = s.points[0]
        drawCircle(
            Color(s.color),
            radius = s.width / 2f * (0.6f + 0.8f * p.pressure),
            center = Offset(p.x, p.y)
        )
        return
    }
    val path = buildSmoothPath(s.points)
    val avg = s.points.map { it.pressure }.average().toFloat()
    val w = s.width * (0.6f + 0.8f * avg)
    val style = DStrokeStyle(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (s.tool == Tool.MARKER) {
        drawPath(path, Color(s.color), alpha = 0.35f, style = style)
    } else {
        drawPath(path, Color(s.color), style = style)
    }
}

fun DrawScope.drawPaperPattern(page: Page, pw: Float, ph: Float) {
    val lineColor = Color(0xFFBDBDBD)
    when (page.paper) {
        PaperType.A4_LINED -> {
            val step = 40f
            var y = step
            while (y < ph) {
                drawLine(lineColor, Offset(0f, y), Offset(pw, y), strokeWidth = 1f)
                y += step
            }
        }
        PaperType.A4_GRID -> {
            val step = 40f
            var y = step
            while (y < ph) {
                drawLine(lineColor, Offset(0f, y), Offset(pw, y), strokeWidth = 1f)
                y += step
            }
            var x = step
            while (x < pw) {
                drawLine(lineColor, Offset(x, 0f), Offset(x, ph), strokeWidth = 1f)
                x += step
            }
        }
        PaperType.A4_DOTS -> {
            val step = 40f
            var y = step
            while (y < ph) {
                var x = step
                while (x < pw) {
                    drawCircle(Color(0xFF9E9E9E), radius = 1.5f, center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        else -> {}
    }
}

/** 页面有效尺寸（无限画布给一个固定的大画布底板） */
fun pageSheetSize(page: Page): Pair<Float, Float> =
    if (page.paper == PaperType.INFINITE) 3000f to 4000f else page.width to page.height

fun DrawScope.drawPageContent(page: Page, bmp: ImageBitmap?) {
    val (pw, ph) = pageSheetSize(page)
    clipRect(0f, 0f, pw, ph, clipOp = ClipOp.Intersect) {
        drawRect(Color.White, size = Size(pw, ph))
        if (bmp != null) {
            drawImage(
                bmp,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(pw.toInt(), ph.toInt()),
                filterQuality = FilterQuality.Low
            )
        }
        drawPaperPattern(page, pw, ph)
        page.strokes.forEach { drawStroke(it) }
    }
}

@Composable
fun rememberPageBitmap(notebookId: String, page: Page): ImageBitmap? {
    val context = LocalContext.current
    val file = page.backgroundImage?.let { Store.bgFile(context, notebookId, it) }
    if (file == null || !file.exists()) return null
    val state = produceState<ImageBitmap?>(initialValue = null, key1 = file) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    }
    return state.value
}

// ---------- 手势 ----------

private fun Modifier.canvasGestures(state: EditorState): Modifier = pointerInput(state.tool) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val isStylus = down.type == PointerType.Stylus
        val eraserStylus = down.type == PointerType.Eraser
        val touch = down.type == PointerType.Touch
        if (isStylus || eraserStylus) state.stylusSeen = true

        val t = if (eraserStylus) Tool.ERASER else state.tool
        var drawing = false
        if (t == Tool.PEN || t == Tool.MARKER || t == Tool.LASER || t == Tool.ERASER) {
            drawing = isStylus || eraserStylus ||
                (touch && state.fingerDraw && !state.stylusSeen)
        }
        var transform = !drawing && state.tool == Tool.PAN

        if (drawing) state.beginGesture(state.toCanvas(down.position), down.pressure)

        while (true) {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount >= 2) {
                if (drawing) {
                    state.endGesture()
                    drawing = false
                }
                transform = true
            }
            if (transform) {
                state.applyTransform(
                    event.calculateZoom(),
                    event.calculatePan(),
                    event.calculateCentroid(useCurrent = true)
                )
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (drawing) {
                val change = event.changes.firstOrNull { it.pressed }
                if (change != null) {
                    state.moveGesture(state.toCanvas(change.position), change.pressure)
                    change.consume()
                }
            }
            if (event.changes.all { !it.pressed }) break
        }
        if (drawing) state.endGesture()
    }
}

// ---------- 画布 ----------

@Composable
fun NoteCanvas(state: EditorState, modifier: Modifier = Modifier) {
    // 激光笔轨迹淡出 & 持续重绘
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            state.tickLaser()
        }
    }

    val bmp = rememberPageBitmap(state.notebook.id, state.page)

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF35363B))
            .onSizeChanged { state.canvasSize = it }
            .canvasGestures(state)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            translate(state.offset.x, state.offset.y) {
                scale(state.scale, state.scale, pivot = Offset.Zero) {
                    drawPageContent(state.page, bmp)
                    state.liveStroke?.let { drawStroke(it) }
                    if (state.laserPoints.size >= 2) {
                        drawPath(
                            buildSmoothPath(state.laserPoints),
                            Color(0xFFE53935),
                            alpha = state.laserAlpha().coerceAtLeast(0.15f),
                            style = DStrokeStyle(
                                width = 5f / state.scale,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }
        }
    }
}
