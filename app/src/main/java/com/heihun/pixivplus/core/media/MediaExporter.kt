package com.heihun.pixivplus.core.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.task.TaskExecution
import java.io.File
import java.util.zip.ZipFile

object MediaExporter {
    fun saveOriginals(
        context: Context, store: ConfigStore, folder: Uri, stem: String,
        images: List<OriginalImage>, asZip: Boolean, linksText: String?, checkpoint: File, progress: (String) -> Unit
    ): List<String> {
        require(images.isNotEmpty())
        val temp = scratchDirectory(checkpoint)
        val created = ArrayList<Pair<Uri, Boolean>>()
        try {
            var totalBytes = 0L
            val files = images.mapIndexed { index, image ->
                progress("正在下载原图… (${index + 1}/${images.size})")
                File(checkpoint, "${image.page}_${image.fileName}").also {
                    MediaRepository.download(store, image.url, it)
                    totalBytes += it.length()
                    require(totalBytes <= 512L * 1024 * 1024) { "本次原图体积过大（最多 512 MB），请减少选择页数" }
                }
            }
            if (asZip) {
                progress("正在打包 ZIP…")
                val zip = File(temp, "$stem.zip")
                OriginalArchive.write(zip.outputStream().buffered(), stem, images, files, linksText)
                val saved = write(context, parentOf(folder), zip.name, "application/zip", zip, created)
                TaskExecution.check()
                return listOf(saved)
            }
            val parent = parentOf(folder)
            val directoryName = uniqueDirectoryName(context, folder, parent, stem)
            val directory = DocumentsContract.createDocument(context.contentResolver, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, directoryName) ?: error("无法创建作品文件夹")
            created.add(directory to true)
            val saved = images.mapIndexed { index, image ->
                progress("正在保存原图… (${index + 1}/${images.size})")
                "$directoryName/${write(context, directory, MediaNames.image(stem, image), image.mime, files[index], created)}"
            }
            if (linksText != null) {
                val info = File(temp, "作品信息.txt").apply { writeText(linksText, Charsets.UTF_8) }
                write(context, directory, info.name, "text/plain", info, created)
            }
            TaskExecution.check()
            return saved
        } catch (t: Throwable) {
            rollback(context, created)
            throw t
        } finally { temp.deleteRecursively() } // 仅删除本次任务创建的随机临时目录。
    }

    fun saveGif(context: Context, store: ConfigStore, folder: Uri, id: Long, stem: String, checkpoint: File, progress: (String) -> Unit): String {
        val temp = scratchDirectory(checkpoint)
        val created = ArrayList<Pair<Uri, Boolean>>()
        try {
            progress("正在读取动图帧信息…")
            val metadata = MediaRepository.ugoira(store, id)
            val archive = File(checkpoint, "frames.zip")
            progress("正在下载动图…")
            MediaRepository.download(store, metadata.zipUrl, archive, 128 * 1024 * 1024)
            val gif = File(temp, "$stem.gif")
            ZipFile(archive).use { zip ->
                // 校验所有帧存在且大小受限。不解压路径，不按 ZIP 条目顺序猜测帧顺序。
                var total = 0L
                val entries = metadata.frames.map { frame ->
                    val entry = zip.getEntry(frame.file) ?: error("动图帧缺失：${frame.file}")
                    require(!entry.isDirectory && entry.size in 1..(24L * 1024 * 1024)) { "动图帧大小无效" }
                    total += entry.size
                    require(total <= 512L * 1024 * 1024) { "动图解压体积过大（最多 512 MB）" }
                    entry
                }
                gif.outputStream().buffered().use { output ->
                    var writer: GifWriter? = null
                    var width = 0
                    var height = 0
                    entries.forEachIndexed { index, entry ->
                        progress("正在转换 GIF… (${index + 1}/${entries.size})")
                        val bytes = zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(8192)
                            val output = java.io.ByteArrayOutputStream()
                        while (true) {
                                TaskExecution.check()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 24 * 1024 * 1024) { "动图帧过大" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        require(options.outWidth > 0 && options.outHeight > 0 &&
                            options.outWidth.toLong() * options.outHeight <= 4_000_000) { "动图帧无效或分辨率过大" }
                        if (writer == null) {
                            width = options.outWidth; height = options.outHeight
                            writer = GifWriter(output, width, height)
                        }
                        require(options.outWidth == width && options.outHeight == height) { "动图帧尺寸不一致" }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("动图帧解码失败")
                        try {
                            val pixels = IntArray(width * height)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            writer!!.addFrame(pixels, metadata.frames[index].delayMs)
                            require(gif.length() <= 512L * 1024 * 1024) { "GIF 体积过大（最多 512 MB）" }
                        } finally { bitmap.recycle() }
                    }
                    writer!!.finish()
                    require(gif.length() <= 512L * 1024 * 1024) { "GIF 体积过大（最多 512 MB）" }
                }
            }
            val saved = write(context, parentOf(folder), gif.name, "image/gif", gif, created)
            TaskExecution.check()
            return saved
        } catch (t: Throwable) {
            rollback(context, created)
            throw t
        } finally { temp.deleteRecursively() }
    }

    private fun scratchDirectory(checkpoint: File): File =
        File(checkpoint, "export-${java.util.UUID.randomUUID()}").apply {
            check(mkdirs()) { "无法创建下载临时目录" }
        }

    /** 创建新文档，不覆盖同名文件；失败时清理本次创建的半成品。 */
    private fun parentOf(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** 新建独立作品目录，重复保存不复用或覆盖已有目录。 */
    private fun uniqueDirectoryName(context: Context, tree: Uri, parent: Uri, stem: String): String {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val names = HashSet<String>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0))
        }
        var name = stem
        var suffix = 2
        while (name in names) name = "$stem (${suffix++})"
        return name
    }

    private fun write(context: Context, parent: Uri, name: String, mime: String, file: File, created: MutableList<Pair<Uri, Boolean>>): String {
        TaskExecution.check()
        val resolver = context.contentResolver
        val document = DocumentsContract.createDocument(resolver, parent, mime, name) ?: error("无法创建保存文件")
        created.add(document to false)
        try {
            (resolver.openOutputStream(document, "w") ?: error("无法写入保存文件")).use { output ->
                file.inputStream().use { TaskExecution.copy(it, output) }
            }
            return name
        } catch (t: Exception) {
            try { DocumentsContract.deleteDocument(resolver, document) } catch (_: Exception) { }
            throw t
        }
    }

    /** 只回滚本任务创建的文件；目录如被外部添加内容，不递归删除。 */
    private fun rollback(context: Context, created: List<Pair<Uri, Boolean>>) {
        for ((uri, directory) in created.asReversed()) try {
            if (directory) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
                val empty = context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                    ?.use { !it.moveToFirst() } ?: false
                if (!empty) continue
            }
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) { }
    }
}
