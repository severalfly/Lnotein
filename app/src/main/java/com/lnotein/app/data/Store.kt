package com.lnotein.app.data

import android.content.Context
import com.lnotein.app.model.Notebook
import com.lnotein.app.model.Page
import com.lnotein.app.model.PaperType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

object Store {
    private val json = Json { ignoreUnknownKeys = true }

    fun notebooksDir(ctx: Context) = File(ctx.filesDir, "notebooks").apply { mkdirs() }

    fun assetsDir(ctx: Context, nbId: String) =
        File(File(ctx.filesDir, "assets"), nbId).apply { mkdirs() }

    fun bgFile(ctx: Context, nbId: String, name: String) = File(assetsDir(ctx, nbId), name)

    fun listNotebooks(ctx: Context): List<Notebook> =
        notebooksDir(ctx).listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { f -> runCatching { json.decodeFromString<Notebook>(f.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun loadNotebook(ctx: Context, id: String): Notebook? {
        val f = File(notebooksDir(ctx), "$id.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<Notebook>(f.readText()) }.getOrNull()
    }

    fun saveNotebook(ctx: Context, nb: Notebook) {
        val dir = notebooksDir(ctx)
        val tmp = File(dir, "${nb.id}.tmp")
        val f = File(dir, "${nb.id}.json")
        tmp.writeText(json.encodeToString(nb))
        if (f.exists()) f.delete()
        tmp.renameTo(f)
    }

    fun deleteNotebook(ctx: Context, id: String) {
        File(notebooksDir(ctx), "$id.json").delete()
        File(File(ctx.filesDir, "assets"), id).deleteRecursively()
    }

    fun newNotebook(name: String, paper: PaperType): Notebook {
        val page = Page(id = UUID.randomUUID().toString(), paper = paper)
        return Notebook(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "未命名笔记" },
            createdAt = System.currentTimeMillis(),
            pages = mutableListOf(page)
        )
    }
}
