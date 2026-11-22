package com.lnotein.app.model

import kotlinx.serialization.Serializable

enum class Tool { PEN, MARKER, LASER, ERASER, TEXT, PAN }

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

/** 文本批注：位置为页面坐标，fontSize 为页面单位（pt） */
@Serializable
data class TextNote(
    val id: String,
    var x: Float,
    var y: Float,
    var text: String,
    var color: Int = 0xFF222222.toInt(),
    var fontSize: Float = 16f
)

@Serializable
data class Page(
    val id: String,
    var paper: PaperType = PaperType.A4_LINED,
    val width: Float = 595f,
    val height: Float = 842f,
    var backgroundImage: String? = null,
    val strokes: MutableList<Stroke> = mutableListOf(),
    val texts: MutableList<TextNote> = mutableListOf()
)

@Serializable
data class Notebook(
    val id: String,
    val name: String,
    val createdAt: Long,
    val pages: MutableList<Page> = mutableListOf()
)
