package com.heihun.pixivplus.core.media

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** ZIP 内按作品页码命名，直接复制原图字节，不解码或重新压缩图片。 */
object OriginalArchive {
    fun write(output: OutputStream, id: Long, images: List<OriginalImage>, files: List<File>) {
        require(id > 0 && images.isNotEmpty() && images.size == files.size)
        require(images.map { it.page }.distinct().size == images.size)
        ZipOutputStream(output).use { zip ->
            images.forEachIndexed { index, image ->
                zip.putNextEntry(ZipEntry("${id}_p${image.page - 1}.${image.fileName.substringAfterLast('.')}") )
                files[index].inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
