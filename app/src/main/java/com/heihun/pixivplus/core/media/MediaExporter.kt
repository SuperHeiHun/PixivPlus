package com.heihun.pixivplus.core.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.heihun.pixivplus.core.config.ConfigStore
import java.io.File
import java.util.zip.ZipFile

object MediaExporter {
    fun saveOriginals(
        context: Context, store: ConfigStore, folder: Uri, id: Long,
        images: List<OriginalImage>, asZip: Boolean, progress: (String) -> Unit
    ): List<String> {
        require(images.isNotEmpty())
        val temp = taskDirectory(context)
        try {
            var totalBytes = 0L
            val files = images.mapIndexed { index, image ->
                progress("正在下载原图… (${index + 1}/${images.size})")
                File(temp, "${image.page}_${image.fileName}").also {
                    MediaRepository.download(store, image.url, it)
                    totalBytes += it.length()
                    require(totalBytes <= 512L * 1024 * 1024) { "本次原图体积过大（最多 512 MB），请减少选择页数" }
                }
            }
            if (asZip) {
                progress("正在打包 ZIP…")
                val zip = File(temp, "${id}_原图.zip")
                OriginalArchive.write(zip.outputStream().buffered(), id, images, files)
                return listOf(write(context, folder, zip.name, "application/zip", zip))
            }
            return images.mapIndexed { index, image ->
                progress("正在保存原图… (${index + 1}/${images.size})")
                write(context, folder, "${id}_p${image.page - 1}.${image.fileName.substringAfterLast('.')}", image.mime, files[index])
            }
        } finally { temp.deleteRecursively() } // 仅删除本次任务创建的随机临时目录。
    }

    fun saveGif(context: Context, store: ConfigStore, folder: Uri, id: Long, progress: (String) -> Unit): String {
        val temp = taskDirectory(context)
        try {
            progress("正在读取动图帧信息…")
            val metadata = MediaRepository.ugoira(store, id)
            val archive = File(temp, "frames.zip")
            progress("正在下载动图…")
            MediaRepository.download(store, metadata.zipUrl, archive, 128 * 1024 * 1024)
            val gif = File(temp, "$id.gif")
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
            return write(context, folder, gif.name, "image/gif", gif)
        } finally { temp.deleteRecursively() }
    }

    private fun taskDirectory(context: Context): File =
        File(context.cacheDir, "plp-media-${java.util.UUID.randomUUID()}").apply {
            check(mkdir()) { "无法创建下载临时目录" }
        }

    /** 创建新文档，不覆盖同名文件；失败时清理本次创建的半成品。 */
    private fun write(context: Context, tree: Uri, name: String, mime: String, file: File): String {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val document = DocumentsContract.createDocument(resolver, parent, mime, name) ?: error("无法创建保存文件")
        try {
            (resolver.openOutputStream(document, "w") ?: error("无法写入保存文件")).use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            return name
        } catch (t: Exception) {
            try { DocumentsContract.deleteDocument(resolver, document) } catch (_: Exception) { }
            throw t
        }
    }
}
