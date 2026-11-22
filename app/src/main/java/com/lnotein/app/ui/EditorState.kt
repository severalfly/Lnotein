package com.lnotein.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.lnotein.app.data.Store
import com.lnotein.app.model.Notebook
import com.lnotein.app.model.Stroke
import com.lnotein.app.model.StrokePoint
import com.lnotein.app.model.Tool
import com.lnotein.app.model.PaperType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class EditorState(val context: Context, val notebook: Notebook) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    var pageIndex by mutableIntStateOf(0)
        private set
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var revision by mutableIntStateOf(0)
        private set
    var tool by mutableStateOf(Tool.PEN)
    var color by mutableStateOf(0xFF222222.toInt())
    var brushWidth by mutableFloatStateOf(4f)
    var fingerDraw by mutableStateOf(true)
    var stylusSeen = false
    var canvasSize by mutableStateOf(IntSize.Zero)

    var showPages by mutableStateOf(false)
    var showSettings by mutableStateOf(false)

    var liveStroke by mutableStateOf<Stroke?>(null)
        private set
    var laserPoints by mutableStateOf<List<StrokePoint>>(emptyList())
        private set
    var lastLaserTime = 0L
        private set

    private val eraseBuffer = mutableListOf<Stroke>()

    private sealed interface Op {
        data class Add(val pageId: String, val stroke: Stroke) : Op
        data class Remove(val pageId: String, val strokes: List<Stroke>) : Op
    }

    private val undoStack = mutableListOf<Op>()
    private val redoStack = mutableListOf<Op>()

    val pageCount: Int get() = notebook.pages.size
    val page: com.lnotein.app.model.Page
        get() = notebook.pages[pageIndex.coerceIn(0, notebook.pages.lastIndex)]

    fun goPage(i: Int) {
        pageIndex = i.coerceIn(0, notebook.pages.lastIndex)
        revision++
    }

    fun nextPage() = goPage(pageIndex + 1)
    fun prevPage() = goPage(pageIndex - 1)

    fun addPage() {
        val newPage = com.lnotein.app.model.Page(
            id = UUID.randomUUID().toString(),
            paper = page.paper
        )
        notebook.pages.add(pageIndex + 1, newPage)
        goPage(pageIndex + 1)
        scheduleSave()
    }

    fun deletePage() {
        if (notebook.pages.size <= 1) return
        notebook.pages.removeAt(pageIndex)
        goPage(pageIndex.coerceAtMost(notebook.pages.lastIndex))
        scheduleSave()
    }

    fun setPaper(p: PaperType) {
        page.paper = p
        scheduleSave()
    }

    fun toCanvas(p: Offset) = Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)

    fun applyTransform(zoom: Float, pan: Offset, centroid: Offset) {
        val s1 = scale
        val s2 = (s1 * zoom).coerceIn(0.2f, 12f)
        val ratio = s2 / s1
        offset = Offset(
            centroid.x - (centroid.x - offset.x) * ratio + pan.x,
            centroid.y - (centroid.y - offset.y) * ratio + pan.y
        )
        scale = s2
    }

    fun fitPage() {
        val cs = canvasSize
        if (cs == IntSize.Zero) return
        val pw = if (page.paper == PaperType.INFINITE) 3000f else page.width
        val ph = if (page.paper == PaperType.INFINITE) 4000f else page.height
        val s = minOf(cs.width / pw, cs.height / ph) * 0.92f
        scale = s
        offset = Offset((cs.width - pw * s) / 2f, (cs.height - ph * s) / 2f)
    }

    fun beginGesture(p: Offset, pressure: Float) {
        when (tool) {
            Tool.ERASER -> eraseBuffer.clear()
            Tool.LASER -> {
                laserPoints = listOf(StrokePoint(p.x, p.y))
                lastLaserTime = System.currentTimeMillis()
            }
            Tool.PEN, Tool.MARKER -> {
                val w = (if (tool == Tool.MARKER) brushWidth * 4f else brushWidth) / scale
                liveStroke = Stroke(tool, color, w, listOf(StrokePoint(p.x, p.y, pressure)))
            }
            else -> {}
        }
    }

    fun moveGesture(p: Offset, pressure: Float) {
        when (tool) {
            Tool.ERASER -> eraseAt(p)
            Tool.LASER -> {
                laserPoints = laserPoints + StrokePoint(p.x, p.y)
                lastLaserTime = System.currentTimeMillis()
            }
            Tool.PEN, Tool.MARKER -> {
                val cur = liveStroke ?: return
                liveStroke = cur.copy(points = cur.points + StrokePoint(p.x, p.y, pressure))
            }
            else -> {}
        }
    }

    fun endGesture() {
        when (tool) {
            Tool.PEN, Tool.MARKER -> {
                val s = liveStroke
                liveStroke = null
                if (s != null && s.points.size >= 2) {
                    page.strokes.add(s)
                    undoStack.add(Op.Add(page.id, s))
                    redoStack.clear()
                    scheduleSave()
                }
            }
            Tool.ERASER -> {
                if (eraseBuffer.isNotEmpty()) {
                    undoStack.add(Op.Remove(page.id, eraseBuffer.toList()))
                    redoStack.clear()
                    scheduleSave()
                }
                eraseBuffer.clear()
            }
            else -> {}
        }
        revision++
    }

    private fun eraseAt(p: Offset) {
        val r = (brushWidth * 2f + 6f) / scale
        val r2 = r * r
        val removed = page.strokes.filter { s ->
            s.points.any { q ->
                val dx = q.x - p.x
                val dy = q.y - p.y
                dx * dx + dy * dy < r2
            }
        }
        if (removed.isNotEmpty()) {
            page.strokes.removeAll(removed.toSet())
            eraseBuffer.addAll(removed)
            revision++
        }
    }

    fun tickLaser() {
        if (laserPoints.isNotEmpty() &&
            System.currentTimeMillis() - lastLaserTime > 900
        ) {
            laserPoints = emptyList()
        }
    }

    fun laserAlpha(): Float {
        val age = System.currentTimeMillis() - lastLaserTime
        return (1f - age / 900f).coerceIn(0f, 1f)
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        revert(op)
        redoStack.add(op)
        revision++
        scheduleSave()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        reapply(op)
        undoStack.add(op)
        revision++
        scheduleSave()
    }

    private fun pageById(id: String) = notebook.pages.firstOrNull { it.id == id }

    private fun revert(op: Op) {
        when (op) {
            is Op.Add -> pageById(op.pageId)?.strokes?.remove(op.stroke)
            is Op.Remove -> pageById(op.pageId)?.strokes?.addAll(op.strokes)
        }
    }

    private fun reapply(op: Op) {
        when (op) {
            is Op.Add -> pageById(op.pageId)?.strokes?.add(op.stroke)
            is Op.Remove -> pageById(op.pageId)?.strokes?.removeAll(op.strokes.toSet())
        }
    }

    fun scheduleSave() {
        revision++
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            Store.saveNotebook(context, notebook)
        }
    }

    fun saveNow() {
        scope.launch { Store.saveNotebook(context, notebook) }
    }
}
