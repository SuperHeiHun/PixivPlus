package com.heihun.pixivplus.core.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.heihun.pixivplus.core.i18n.DownloadText as D
import com.heihun.pixivplus.core.i18n.WorkText as W
import com.heihun.pixivplus.core.task.TaskExecution
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/** 写入默认公共下载目录或用户 SAF 目录。只有 commit 后才公开 MediaStore 文件。 */
internal class OutputTransaction(private val context: Context, folder: String) : Closeable {
    private val resolver = context.contentResolver
    private val tree = if (OutputPolicy.isDefault(folder)) null else Uri.parse(folder)
    private val created = ArrayList<Created>()
    private var committed = false
    private data class Created(val uri: Uri?, val directory: Boolean = false, val media: Boolean = false, val file: File? = null)
    data class Directory(val uri: Uri? = null, val name: String = "")

    val root: Directory = Directory(tree?.let {
        DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it))
    })

    fun createDirectory(stem: String): Directory {
        OutputPolicy.requireName(stem)
        TaskExecution.check()
        if (tree != null) {
            val names = childNames(root.uri!!)
            val name = OutputPolicy.uniqueName(stem) { it in names }
            val uri = DocumentsContract.createDocument(resolver, root.uri, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: error(D.CREATE_WORK_FOLDER_FAILED())
            created.add(Created(uri, directory = true))
            return Directory(uri, displayName(uri, name))
        }
        val parent = downloadsRoot()
        val name = OutputPolicy.uniqueName(stem) { candidate ->
            File(parent, candidate).exists() || if (Build.VERSION.SDK_INT >= 29) {
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(OutputPolicy.RELATIVE_ROOT + candidate + "/"), null)
                    ?.use { it.moveToFirst() } ?: false
            } else false
        }
        if (Build.VERSION.SDK_INT < 29) {
            requireLegacyPermission()
            val directory = File(parent, name)
            check(directory.mkdirs()) { D.CREATE_WORK_FOLDER_FAILED() }
            created.add(Created(null, directory = true, file = directory))
        }
        return Directory(name = name)
    }

    fun write(directory: Directory, name: String, mime: String, input: () -> InputStream): SavedFile {
        OutputPolicy.requireName(name)
        TaskExecution.check()
        val uri = when {
            tree != null -> {
                val result = DocumentsContract.createDocument(resolver, directory.uri!!, mime, name)
                    ?: error(W.CREATE_FILE_FAILED())
                created.add(Created(result))
                result
            }
            Build.VERSION.SDK_INT >= 29 -> {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, OutputPolicy.RELATIVE_ROOT +
                        directory.name.takeIf { it.isNotEmpty() }?.let { "$it/" }.orEmpty())
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val result = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error(W.CREATE_FILE_FAILED())
                created.add(Created(result, media = true))
                result
            }
            else -> legacyFile(directory, name, mime)
        }
        (resolver.openOutputStream(uri, "w") ?: error(W.WRITE_FILE_FAILED())).use { output ->
            input().use { TaskExecution.copy(it, output) }
        }
        TaskExecution.check()
        val actual = displayName(uri, name)
        return SavedFile(uri.toString(), if (directory.name.isEmpty()) actual else "${directory.name}/$actual", mime)
    }

    fun commit() {
        TaskExecution.check()
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            for (item in created) {
                TaskExecution.check()
                if (item.media) check(resolver.update(item.uri!!, values, null, null) == 1) { W.WRITE_FILE_FAILED() }
            }
        }
        TaskExecution.check()
        committed = true
    }

    override fun close() {
        if (committed) return
        for (item in created.asReversed()) try {
            if (item.directory) {
                if (item.file != null) { item.file.delete(); continue } // 仅空目录可以删除，不递归。
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(item.uri!!, DocumentsContract.getDocumentId(item.uri))
                val empty = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                    ?.use { !it.moveToFirst() } ?: false
                if (!empty) continue
            }
            item.uri?.let { if (tree != null) DocumentsContract.deleteDocument(resolver, it) else resolver.delete(it, null, null) }
            item.file?.delete()
        } catch (_: Exception) { }
    }

    private fun childNames(parent: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree!!, DocumentsContract.getDocumentId(parent))
        val names = HashSet<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) names.add(it.getString(0))
        }
        return names
    }

    private fun displayName(uri: Uri, fallback: String): String = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: fallback
    } catch (_: Exception) { fallback }

    @Suppress("DEPRECATION")
    private fun downloadsRoot(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PixivPlus")

    private fun requireLegacyPermission() {
        check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            D.LEGACY_STORAGE_PERMISSION()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(directory: Directory, name: String, mime: String): Uri {
        requireLegacyPermission()
        val parent = if (directory.name.isEmpty()) downloadsRoot() else File(downloadsRoot(), directory.name)
        check(parent.mkdirs() || parent.isDirectory) { D.CREATE_WORK_FOLDER_FAILED() }
        var file: File
        var suffix = 1
        while (true) {
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val candidate = if (suffix == 1) name else name.substring(0, dot) + " (${suffix})" + name.substring(dot)
            file = File(parent, candidate)
            try { Files.createFile(file.toPath()); break } catch (_: FileAlreadyExistsException) { suffix++ }
        }
        created.add(Created(null, file = file))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: error(W.CREATE_FILE_FAILED())
        created.add(Created(uri))
        return uri
    }
}
