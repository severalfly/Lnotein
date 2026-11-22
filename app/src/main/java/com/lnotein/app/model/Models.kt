package com.lnotein.app.model

import kotlinx.serialization.Serializable

enum class Tool { PEN, MARKER, LASER, ERASER, PAN }

enum class PaperType { INFINITE, A4_BLANK, A4_LINED, A4_GRID, A4_DOTS }

val PaperType.label: String
    get() = when (this) {
        PaperType.INFINITE -> "无限画布"
        PaperType.A4_BLANK -> "空白"
        PaperType.A4_LINED -> "横线"
        PaperType.A4_GRID -> "方格"
        PaperType.A4_DOTS -> "点阵"
    }

@Serializable
data class StrokePoint(val x: Float, val y: Float, val pressure: Float = 1f)

@Serializable
data class Stroke(
    val tool: Tool,
    val color: Int,
    val width: Float,
    val points: List<StrokePoint>
)

@Serializable
data class Page(
    val id: String,
    var paper: PaperType = PaperType.A4_LINED,
    val width: Float = 595f,
    val height: Float = 842f,
    var backgroundImage: String? = null,
    val strokes: MutableList<Stroke> = mutableListOf()
)

@Serializable
data class Notebook(
    val id: String,
    val name: String,
    val createdAt: Long,
    val pages: MutableList<Page> = mutableListOf()
)
