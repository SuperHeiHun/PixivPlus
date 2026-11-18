package com.heihun.pixivplus.core.media

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** ZIP 内按作品页码命名，直接复制原图字节，不解码或重新压缩图片。 */
object OriginalArchive {
    fun write(output: OutputStream, stem: String, images: List<OriginalImage>, files: List<File>, linksText: String? = null) {
        require(stem.isNotBlank() && images.isNotEmpty() && images.size == files.size)
        require(images.map { it.page }.distinct().size == images.size)
        ZipOutputStream(output).use { zip ->
            images.forEachIndexed { index, image ->
                com.heihun.pixivplus.core.task.TaskExecution.check()
                zip.putNextEntry(ZipEntry(MediaNames.image(stem, image)))
                files[index].inputStream().use { com.heihun.pixivplus.core.task.TaskExecution.copy(it, zip) }
                zip.closeEntry()
            }
            if (linksText != null) {
                zip.putNextEntry(ZipEntry("作品信息.txt"))
                zip.write(linksText.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
