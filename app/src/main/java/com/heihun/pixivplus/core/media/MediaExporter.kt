package com.heihun.pixivplus.core.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.i18n.DownloadText as D
import com.heihun.pixivplus.core.task.TaskExecution
import java.io.File
import java.util.zip.ZipFile

object MediaExporter {
    fun saveOriginals(
        context: Context, store: ConfigStore, folder: Uri, stem: String,
        images: List<OriginalImage>, asZip: Boolean, linksText: String?, checkpoint: File
    ): List<SavedFile> {
        require(images.isNotEmpty())
        val temp = scratchDirectory(checkpoint)
        val destination = OutputTransaction(context, folder.toString())
        try {
            var totalBytes = 0L
            val files = images.mapIndexed { index, image ->
                TaskExecution.progress(D.PREPARE_ORIGINALS(index + 1, images.size), index + 1, images.size)
                File(checkpoint, "${image.page}_${image.fileName}").also {
                    MediaRepository.download(store, image.url, it)
                    totalBytes += it.length()
                    require(totalBytes <= 512L * 1024 * 1024) { D.ORIGINALS_SIZE_LIMIT() }
                }
            }
            if (asZip) {
                TaskExecution.progress(D.BUILD_ZIP())
                val zip = File(temp, "$stem.zip")
                OriginalArchive.write(zip.outputStream().buffered(), stem, images, files, linksText)
                val saved = destination.write(destination.root, zip.name, "application/zip") { zip.inputStream() }
                destination.commit()
                return listOf(saved)
            }
            val directory = destination.createDirectory(stem)
            val saved = images.mapIndexed { index, image ->
                TaskExecution.progress(D.SAVE_ORIGINALS(index + 1, images.size), index + 1, images.size)
                destination.write(directory, MediaNames.image(stem, image), image.mime) { files[index].inputStream() }
            }.toMutableList()
            if (linksText != null) {
                val info = File(temp, "作品信息.txt").apply { writeText(linksText, Charsets.UTF_8) }
                saved.add(destination.write(directory, info.name, "text/plain") { info.inputStream() })
            }
            destination.commit()
            return saved
        } catch (t: Throwable) {
            throw t
        } finally { destination.close(); temp.deleteRecursively() } // 只回滚本次文件和随机临时目录。
    }

    fun saveGif(context: Context, store: ConfigStore, folder: Uri, id: Long, stem: String, checkpoint: File): SavedFile {
        val temp = scratchDirectory(checkpoint)
        val destination = OutputTransaction(context, folder.toString())
        try {
            TaskExecution.progress(D.READ_FRAMES())
            val metadata = MediaRepository.ugoira(store, id)
            val archive = File(checkpoint, "frames.zip")
            TaskExecution.progress(D.DOWNLOAD_UGOIRA())
            MediaRepository.download(store, metadata.zipUrl, archive, 128 * 1024 * 1024)
            val gif = File(temp, "$stem.gif")
            ZipFile(archive).use { zip ->
                // 校验所有帧存在且大小受限。不解压路径，不按 ZIP 条目顺序猜测帧顺序。
                var total = 0L
                val entries = metadata.frames.map { frame ->
                    val entry = zip.getEntry(frame.file) ?: error(D.FRAME_NOT_FOUND(frame.file))
                    require(!entry.isDirectory && entry.size in 1..(24L * 1024 * 1024)) { D.BAD_FRAME_SIZE() }
                    total += entry.size
                    require(total <= 512L * 1024 * 1024) { D.ARCHIVE_UNPACK_LIMIT() }
                    entry
                }
                gif.outputStream().buffered().use { output ->
                    var writer: GifWriter? = null
                    var width = 0
                    var height = 0
                    entries.forEachIndexed { index, entry ->
                        TaskExecution.progress(D.CONVERTING_GIF(index + 1, entries.size), index + 1, entries.size)
                        val bytes = zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(8192)
                            val output = java.io.ByteArrayOutputStream()
                            while (true) {
                                TaskExecution.check()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 24 * 1024 * 1024) { D.FRAME_SIZE_LIMIT() }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        require(options.outWidth > 0 && options.outHeight > 0 &&
                            options.outWidth.toLong() * options.outHeight <= 4_000_000) { D.BAD_FRAME_RESOLUTION() }
                        if (writer == null) {
                            width = options.outWidth; height = options.outHeight
                            writer = GifWriter(output, width, height)
                        }
                        require(options.outWidth == width && options.outHeight == height) { D.FRAME_DIMENSIONS_DIFFER() }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error(D.FRAME_DECODE_FAILED())
                        try {
                            val pixels = IntArray(width * height)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            writer!!.addFrame(pixels, metadata.frames[index].delayMs)
                            require(gif.length() <= 512L * 1024 * 1024) { D.GIF_SIZE_LIMIT() }
                        } finally { bitmap.recycle() }
                    }
                    writer!!.finish()
                    require(gif.length() <= 512L * 1024 * 1024) { D.GIF_SIZE_LIMIT() }
                }
            }
            val saved = destination.write(destination.root, gif.name, "image/gif") { gif.inputStream() }
            destination.commit()
            return saved
        } catch (t: Throwable) {
            throw t
        } finally { destination.close(); temp.deleteRecursively() }
    }

    private fun scratchDirectory(checkpoint: File): File =
        File(checkpoint, "export-${java.util.UUID.randomUUID()}").apply {
            check(mkdirs()) { D.CREATE_TEMP_FAILED() }
        }

}
