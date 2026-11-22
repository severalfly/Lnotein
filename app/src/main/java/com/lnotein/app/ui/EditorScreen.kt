package com.lnotein.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lnotein.app.data.Store
import com.lnotein.app.model.Page
import com.lnotein.app.model.PaperType
import com.lnotein.app.model.TextNote
import com.lnotein.app.model.Tool
import com.lnotein.app.model.label
import com.lnotein.app.pdf.PdfSupport
import kotlinx.coroutines.launch

private val presetColors = listOf(
    0xFF222222.toInt(), 0xFFE53935.toInt(), 0xFF1E88E5.toInt(),
    0xFF43A047.toInt(), 0xFFFB8C00.toInt(), 0xFF8E24AA.toInt()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(notebookId: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val notebook = remember { Store.loadNotebook(context, notebookId) }
    if (notebook == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val state = remember { EditorState(context, notebook) }
    val cfg = remember { mutableStateOf(ToolbarPrefs.load(context)) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { state.saveNow() } }
    BackHandler(onBack = onBack)

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val added = PdfSupport.importPdf(context, uri, notebook)
                    if (added > 0) {
                        state.goPage(state.pageCount - added)
                        state.scheduleSave()
                        android.widget.Toast.makeText(
                            context, "已导入 $added 页", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context, "导入失败：${e.message ?: e.javaClass.simpleName}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            scope.launch { PdfSupport.exportPdf(context, notebook, uri) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text(
                notebook.name,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
        }
        ToolbarRow(
            state = state,
            cfg = cfg.value,
            onCustomize = { state.showSettings = true },
            onImport = { importLauncher.launch(arrayOf("application/pdf")) },
            onExport = { exportLauncher.launch("${notebook.name}.pdf") }
        )
        if (state.tool == Tool.PEN || state.tool == Tool.MARKER || state.tool == Tool.TEXT) {
            BrushOptionsRow(state)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            NoteCanvas(state)
        }
        BottomPageBar(state)
    }

    // 打开 PDF 页时自动适配页面
    LaunchedEffect(state.pageIndex) {
        if (state.page.backgroundImage != null) state.fitPage()
    }

    if (state.showPages) {
        PagesDialog(state) { state.showPages = false }
    }
    if (state.showTextDialog) {
        TextNoteDialog(state)
    }
    if (state.showSettings) {
        SettingsSheet(state, cfg, context)
    }
}

@Composable
private fun TextNoteDialog(state: EditorState) {
    val editing = state.editingText
    var text by remember(state.showTextDialog, editing?.id) {
        mutableStateOf(editing?.text ?: "")
    }
    AlertDialog(
        onDismissRequest = { state.cancelTextDialog() },
        title = { Text(if (editing != null) "编辑文本批注" else "添加文本批注") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("批注内容（可换行）") },
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetColors.forEach { c ->
                        val selected = state.color == c
                        Box(
                            Modifier
                                .size(26.dp)
                                .background(Color(c), CircleShape)
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    CircleShape
                                )
                                .clickable { state.color = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.saveText(text, state.color) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (editing != null) {
                    TextButton(onClick = { state.deleteText(editing) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = { state.cancelTextDialog() }) { Text("取消") }
            }
        }
    )
}

@Composable
private fun BrushOptionsRow(state: EditorState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presetColors.forEach { c ->
            val selected = state.color == c
            Box(
                Modifier
                    .size(28.dp)
                    .background(Color(c), CircleShape)
                    .border(
                        if (selected) 3.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                        CircleShape
                    )
                    .clickable { state.color = c }
            )
        }
        Spacer(Modifier.width(8.dp))
        Slider(
            value = state.brushWidth,
            onValueChange = { state.brushWidth = it },
            valueRange = 1f..20f,
            modifier = Modifier.weight(1f)
        )
        Text("${state.brushWidth.toInt()}")
    }
}

@Composable
private fun BottomPageBar(state: EditorState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { state.prevPage() }) { Text("◀") }
        Text(
            "${state.pageIndex + 1} / ${state.pageCount}",
            Modifier.clickable { state.showPages = true },
            style = MaterialTheme.typography.bodyLarge
        )
        TextButton(onClick = { state.nextPage() }) { Text("▶") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { state.fitPage() }) { Text("适配页面") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageThumbCanvas(
    notebookId: String,
    page: Page,
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val bmp = rememberPageBitmap(notebookId, page, sample = 4)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(
            Modifier
                .width(90.dp)
                .height(127.dp)
                .border(1.dp, Color.Gray)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            val s = size.width / page.width
            scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                clipRect(0f, 0f, page.width, page.height) {
                    drawPageContent(page, bmp)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagesDialog(state: EditorState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("页面（长按缩略图删除页）") },
        text = {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems(state.notebook.pages) { page ->
                    val index = state.notebook.pages.indexOf(page)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PageThumbCanvas(
                            notebookId = state.notebook.id,
                            page = page,
                            label = "#${index + 1}",
                            onClick = { state.goPage(index); onDismiss() },
                            onLongClick = {
                                if (state.pageCount > 1) {
                                    if (index == state.pageIndex) state.deletePage()
                                    else {
                                        state.notebook.pages.removeAt(index)
                                        state.goPage(0)
                                        state.scheduleSave()
                                    }
                                    onDismiss()
                                }
                            }
                        )
                    }
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .width(90.dp)
                                .height(127.dp)
                                .border(1.dp, Color.Gray)
                                .clickable {
                                    state.addPage()
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) { Text("+") }
                        Text("加页", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    state: EditorState,
    cfg: MutableState<ToolbarConfig>,
    context: android.content.Context
) {
    ModalBottomSheet(onDismissRequest = { state.showSettings = false }) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("当前页纸张", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PaperType.entries.forEach { p ->
                    val selected = state.page.paper == p
                    Text(
                        p.label,
                        Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clickable {
                                state.setPaper(p)
                            },
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手指绘制（无笔时使用）")
                Spacer(Modifier.weight(1f))
                Switch(checked = state.fingerDraw, onCheckedChange = { state.fingerDraw = it })
            }
            Spacer(Modifier.height(16.dp))
            Text("工具栏定制", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            ToolbarCustomizeSection(cfg.value) { new ->
                cfg.value = new
                ToolbarPrefs.save(context, new)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Lnotein v0.1 — 开源手写笔记（工具栏回归简洁）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
