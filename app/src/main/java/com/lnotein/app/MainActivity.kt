package com.lnotein.app

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lnotein.app.data.Store
import com.lnotein.app.model.PaperType
import com.lnotein.app.model.Notebook
import com.lnotein.app.ui.EditorScreen
import com.lnotein.app.model.label
import com.lnotein.app.pdf.PdfSupport
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Library : Screen
    data class Editor(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    when (val s = screen) {
        is Screen.Library -> LibraryScreen(onOpen = { screen = Screen.Editor(it) })
        is Screen.Editor -> EditorScreen(notebookId = s.id, onBack = { screen = Screen.Library })
    }
}

private val coverColors = listOf(
    0xFF90CAF9.toInt(), 0xFFA5D6A7.toInt(), 0xFFFFCC80.toInt(),
    0xFFCE93D8.toInt(), 0xFF80CBC4.toInt(), 0xFFF48FB1.toInt()
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var notebooks by remember { mutableStateOf(Store.listNotebooks(context)) }
    var showNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Notebook?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val name = context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
                    ?: "导入的 PDF"
                val nb = Store.newNotebook(name.ifBlank { "导入的 PDF" }, PaperType.A4_BLANK)
                val added = PdfSupport.importPdf(context, uri, nb)
                if (added > 0) {
                    Store.saveNotebook(context, nb)
                    notebooks = Store.listNotebooks(context)
                    onOpen(nb.id)
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(onClick = {
                importLauncher.launch(arrayOf("application/pdf"))
            }) { Text("导入 PDF") }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Lnotein",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showNew = true }) { Text("新建笔记") }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(notebooks, key = { it.id }) { nb ->
                    NotebookCard(
                        notebook = nb,
                        color = Color(coverColors[Math.abs(nb.id.hashCode()) % coverColors.size]),
                        onClick = { onOpen(nb.id) },
                        onLongClick = { deleteTarget = nb }
                    )
                }
            }
        }
    }

    if (showNew) {
        NewNotebookDialog(
            onDismiss = { showNew = false },
            onCreate = { name, paper ->
                val nb = Store.newNotebook(name, paper)
                Store.saveNotebook(context, nb)
                notebooks = Store.listNotebooks(context)
                showNew = false
                onOpen(nb.id)
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除笔记") },
            text = { Text("确定删除「${target.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    Store.deleteNotebook(context, target.id)
                    notebooks = Store.listNotebooks(context)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCard(
    notebook: Notebook,
    color: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Surface(
            color = color,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(120.dp)
        ) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text(
                        notebook.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (notebook.pages.any { it.backgroundImage != null }) "PDF · ${notebook.pages.size} 页"
                        else "${notebook.pages.size} 页",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun NewNotebookDialog(
    onDismiss: () -> Unit,
    onCreate: (String, PaperType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var paper by remember { mutableStateOf(PaperType.A4_LINED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建笔记") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("纸张", style = MaterialTheme.typography.labelLarge)
                PaperType.entries.forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = paper == p,
                            onClick = { paper = p }
                        )
                        Text(p.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, paper) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
