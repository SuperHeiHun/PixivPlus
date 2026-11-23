package com.heihun.pixivplus.core.i18n

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 私有文件目录，不读取缓存或下载目录，不保留导入 URI 的授权。 */
internal class LanguagePackStore(private val directory: File) {
    fun load(base: LanguageCatalog, onError: (Exception) -> Unit = {}): LanguageCatalog {
        var catalog = base
        val files = directory.listFiles().orEmpty().filter {
            it.isFile && it.name.matches(Regex("[A-Za-z0-9-]{1,64}\\.json"))
        }.sortedBy { it.name }
        for (file in files) {
            if (catalog.imported.size >= LanguageCatalog.MAX_IMPORTED) break
            try {
                val pack = file.inputStream().use { LanguagePack.read(it) }
                if (file.name == "${pack.tag}.json") catalog = catalog.withPack(pack)
            } catch (e: Exception) { onError(e) }
        }
        return catalog
    }

    fun save(pack: LanguagePack) {
        val file = packFile(pack.tag)
        val bytes = pack.toJson().toByteArray(Charsets.UTF_8)
        // 保证保存后的 JSON 也可在下次启动时读取。
        if (bytes.size > LanguagePack.MAX_BYTES) throw LanguagePackException(PackProblem.SIZE)
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create the language pack directory")
        val temp = File.createTempFile("pack-", ".tmp", directory)
        try {
            temp.outputStream().use { it.write(bytes) }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    fun remove(tag: String) {
        Files.deleteIfExists(packFile(tag).toPath())
    }

    private fun packFile(tag: String): File {
        require(LanguagePack.parseLocale(tag)?.toLanguageTag() == tag)
        return File(directory, "$tag.json")
    }
}
