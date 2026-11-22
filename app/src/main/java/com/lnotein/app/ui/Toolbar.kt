package com.lnotein.app.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lnotein.app.model.Tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ToolbarAction(val label: String) {
    PEN("笔"),
    MARKER("荧光"),
    LASER("激光"),
    ERASER("擦除"),
    UNDO("撤销"),
    REDO("重做"),
    PAGES("页面"),
    IMPORT_PDF("导入"),
    EXPORT_PDF("导出"),
    SETTINGS("设置");

    val tool: Tool?
        get() = when (this) {
            PEN -> Tool.PEN
            MARKER -> Tool.MARKER
            LASER -> Tool.LASER
            ERASER -> Tool.ERASER
            else -> null
        }

    companion object {
        fun from(name: String) = entries.firstOrNull { it.name == name }
    }
}

@Serializable
data class ToolbarConfig(
    val order: List<String> = ToolbarAction.entries.map { it.name },
    val hidden: List<String> = emptyList(),
    val locked: Boolean = false
)

object ToolbarPrefs {
    private const val KEY = "toolbar_config"
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("lnotein", Context.MODE_PRIVATE)

    fun load(ctx: Context): ToolbarConfig {
        val s = prefs(ctx).getString(KEY, null) ?: return ToolbarConfig()
        return runCatching { json.decodeFromString<ToolbarConfig>(s) }
            .getOrDefault(ToolbarConfig())
    }

    fun save(ctx: Context, cfg: ToolbarConfig) {
        prefs(ctx).edit().putString(KEY, json.encodeToString(cfg)).apply()
    }
}

/** 工具栏定制编辑区（在设置面板里复用） */
@Composable
fun ToolbarCustomizeSection(
    cfg: ToolbarConfig,
    onChange: (ToolbarConfig) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("锁定布局", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Switch(checked = cfg.locked, onCheckedChange = { onChange(cfg.copy(locked = it)) })
    }
    cfg.order.forEachIndexed { i, key ->
        val action = ToolbarAction.from(key) ?: return@forEachIndexed
        val hidden = key in cfg.hidden
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = !hidden,
                onCheckedChange = {
                    onChange(
                        if (it) cfg.copy(hidden = cfg.hidden - key)
                        else cfg.copy(hidden = cfg.hidden + key)
                    )
                }
            )
            Text(action.label, Modifier.weight(1f))
            Text(
                "↑",
                Modifier
                    .padding(horizontal = 8.dp)
                    .clickable(enabled = i > 0) {
                        val newOrder = cfg.order.toMutableList()
                        val item = newOrder.removeAt(i)
                        newOrder.add(i - 1, item)
                        onChange(cfg.copy(order = newOrder))
                    }
            )
            Text(
                "↓",
                Modifier
                    .padding(horizontal = 8.dp)
                    .clickable(enabled = i < cfg.order.lastIndex) {
                        val newOrder = cfg.order.toMutableList()
                        val item = newOrder.removeAt(i)
                        newOrder.add(i + 1, item)
                        onChange(cfg.copy(order = newOrder))
                    }
            )
        }
    }
}

@Composable
fun ToolbarRow(
    state: EditorState,
    cfg: ToolbarConfig,
    onCustomize: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    val visible = cfg.order.mapNotNull { ToolbarAction.from(it) }
        .filter { it.name !in cfg.hidden }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        visible.forEach { action ->
            val selected = action.tool != null && action.tool == state.tool
            androidx.compose.material3.Surface(
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.clickable(
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                    indication = null,
                    onClick = {
                        when (action) {
                            ToolbarAction.PEN, ToolbarAction.MARKER,
                            ToolbarAction.LASER, ToolbarAction.ERASER -> state.tool = action.tool!!
                            ToolbarAction.UNDO -> state.undo()
                            ToolbarAction.REDO -> state.redo()
                            ToolbarAction.PAGES -> state.showPages = true
                            ToolbarAction.IMPORT_PDF -> onImport()
                            ToolbarAction.EXPORT_PDF -> onExport()
                            ToolbarAction.SETTINGS -> state.showSettings = true
                        }
                    }
                )
            ) {
                Text(
                    action.label,
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
