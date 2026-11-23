package com.heihun.pixivplus.core.i18n

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

class I18nTest {
    private fun builtin(tag: String): LanguagePack {
        val paths = listOf(
            File("src/main/assets/pixivplus/i18n/$tag.json"),
            File("app/src/main/assets/pixivplus/i18n/$tag.json")
        )
        val file = paths.firstOrNull { it.isFile } ?: throw AssertionError("Missing language fixture: $tag")
        return file.inputStream().use { LanguagePack.read(it) }
    }

    private fun catalog() = LanguageCatalog(builtin("en"), builtin("zh-CN"))
    private fun pack(tag: String = "ja", vararg strings: Pair<String, String>) =
        LanguagePack(Locale.forLanguageTag(tag), tag, strings.toMap())

    @Test fun `only English and Simplified Chinese are bundled with all registered UI keys`() {
        val english = builtin("en").strings
        val chinese = builtin("zh-CN").strings
        assertFalse(File("src/main/assets/pixivplus/i18n/ja.json").exists())
        val tables = listOf(BackupText, CommonText, DownloadText, DrawerText, LanguageText, NetworkText,
            PanelText, SettingsText, TaskText, WorkText)
        val keys = tables.flatMap { table -> table.javaClass.declaredFields.filter { it.type == Text::class.java }.map {
            it.isAccessible = true
            (it.get(table) as Text).key
        } }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.toSet(), english.keys)
        assertEquals(english.keys, chinese.keys)
        val catalog = catalog()
        assertEquals(listOf("zh-CN", "en"), I18n.languages().map { it.tag })
        for ((key, value) in english) {
            val expected = TextFormat.signature(value, strict = true)
            assertEquals(key, expected, TextFormat.signature(chinese.getValue(key), strict = expected.isNotEmpty()))
            val arguments = expected.associate { token ->
                token.substring(1, token.indexOf('$')).toInt() to if (token.endsWith("d")) 3 else "100% {title}"
            }
            val args = (1..(arguments.keys.maxOrNull() ?: 0)).map { arguments[it] }.toTypedArray()
            for (language in catalog.languages) {
                val pattern = if (language.tag == "en") value else chinese.getValue(key)
                if (args.isNotEmpty()) assertEquals(key, String.format(language.locale, pattern, *args), catalog.format(key, language, *args))
            }
        }
    }

    @Test fun `override wins and host locale resolves exact regional and language packs`() {
        val original = Locale.getDefault()
        val base = catalog()
        assertEquals("en", base.resolve("EN", listOf(Locale.CHINA)).tag)
        val all = base.withPack(pack("ja", "common.cancel" to "キャンセル"))
            .withPack(pack("zh-TW", "common.cancel" to "取消"))
        assertEquals("ja", all.resolve("system", listOf(Locale.JAPAN, Locale.UK)).tag)
        assertEquals("zh-TW", all.resolve("system", listOf(Locale.TAIWAN)).tag)
        assertEquals("zh-CN", all.resolve("system", listOf(Locale.KOREA, Locale.CHINA)).tag)
        assertEquals("en", all.resolve("obsolete", emptyList()).tag)
        assertEquals(original, Locale.getDefault())
    }

    @Test fun `partial packs fall back to English and placeholders can reorder`() {
        val catalog = catalog().withPack(pack("ja",
            "work.caption_result" to "【翻訳】\n%2\$s\n【原文】\n%1\$s",
            "work.page_number" to "%1\$d ページ",
            "common.save" to "保存 100%"
        ))
        val selected = catalog.resolve("ja", emptyList())
        assertEquals("Cancel", catalog.format("common.cancel", selected))
        assertEquals("保存 100%", catalog.format("common.save", selected))
        assertEquals("3 ページ", catalog.format("work.page_number", selected, 3))
        assertEquals("【翻訳】\nResult\n【原文】\n100% {title}", catalog.format("work.caption_result", selected, "100% {title}", "Result"))
    }

    @Test fun `rejects wrong missing extra type repeated or unbounded formatting arguments`() {
        for (value in listOf("%1\$s", "no argument", "%1\$d %2\$s", "%1\$20000000d", "%1\$d %1\$d", "%1\$d %n")) {
            assertThrows(PackProblem.PLACEHOLDER) { catalog().withPack(pack("fr", "work.page_number" to value)) }
        }
    }

    @Test fun `cannot replace bundled packs or import a pack with no recognized strings`() {
        assertThrows(PackProblem.BUILTIN) { catalog().withPack(pack("zh-CN", "common.cancel" to "x")) }
        assertThrows(PackProblem.BUILTIN) { catalog().withPack(pack("en", "common.cancel" to "x")) }
        assertThrows(PackProblem.EMPTY) { catalog().withPack(pack("fr", "future.unknown" to "x")) }
    }

    @Test fun `strict parser validates version locale schema size nesting and duplicate keys`() {
        val valid = """{"version":1,"locale":"fr-FR","name":"Français","strings":{"common.cancel":"Annuler"}}"""
        assertEquals("fr-FR", ("\uFEFF" + valid).byteInputStream().use { LanguagePack.read(it) }.tag)
        assertEquals("fr-FR", (" \n" + valid + "\t ").byteInputStream().use { LanguagePack.read(it) }.tag)
        assertThrows(PackProblem.INVALID) { LanguagePack.read(byteArrayOf(-128).inputStream()) }
        assertThrows(PackProblem.INVALID) { LanguagePack.read(("{".repeat(100) + "}".repeat(100)).byteInputStream()) }
        assertThrows(PackProblem.VERSION) { LanguagePack.read(valid.replace("\"version\":1", "\"version\":2").byteInputStream()) }
        assertThrows(PackProblem.LOCALE) { LanguagePack.read(valid.replace("fr-FR", "../x").byteInputStream()) }
        assertThrows(PackProblem.ENTRY) { LanguagePack.read(valid.replace("\"Annuler\"", "42").byteInputStream()) }
        assertThrows(PackProblem.INVALID) { LanguagePack.read("""{"version":1,"locale":"fr","name":"x","name":"y","strings":{"common.cancel":"x"}}""".byteInputStream()) }
        assertThrows(PackProblem.INVALID) { LanguagePack.read("""{"version":1,"locale":"fr","name":"x","strings":{"common.cancel":"x"}} garbage""".byteInputStream()) }
        assertThrows(PackProblem.INVALID) { LanguagePack.read("""{"version":1,"locale":"fr","name":"x","strings":{"common.cancel":"x","future.bad":["nested"]}}""".byteInputStream()) }
        assertThrows(PackProblem.INVALID) { LanguagePack.read("""{"version":1,"locale":"fr","name":"x","strings":{"common.cancel":"\uD800"}}""".byteInputStream()) }
        assertThrows(PackProblem.SIZE) { LanguagePack.read(ByteArray(LanguagePack.MAX_BYTES + 1).inputStream()) }
    }

    @Test fun `pack update persists atomically and removal falls back`() {
        val directory = Files.createTempDirectory("plp-language-test").toFile()
        try {
            val store = LanguagePackStore(directory)
            val first = pack("fr", "common.cancel" to "Annuler")
            store.save(first)
            directory.resolve("bad.json").writeText("invalid")
            assertEquals(first, store.load(catalog()).imported["fr"])
            store.save(first.copy(strings = mapOf("common.cancel" to "Abandonner")))
            val reloaded = LanguagePackStore(directory).load(catalog())
            assertEquals("Abandonner", reloaded.format("common.cancel", reloaded.resolve("fr", emptyList())))
            store.remove("fr")
            assertTrue(store.load(catalog()).imported.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `pack limit permits replacing an existing language`() {
        var catalog = catalog()
        for (region in listOf("FR", "BE", "CA", "CH", "LU", "MC", "SN", "CI", "ML", "NE", "TG", "BJ", "GA", "CD", "CM", "HT")) {
            catalog = catalog.withPack(pack("fr-$region", "common.cancel" to "Annuler"))
        }
        assertThrows(PackProblem.LIMIT) { catalog.withPack(pack("ja", "common.cancel" to "キャンセル")) }
        assertEquals(16, catalog.withPack(pack("fr-FR", "common.cancel" to "Abandonner")).imported.size)
    }

    @Test fun `documentation example imports as a partial language pack`() {
        val file = listOf(File("../docs/i18n/example-ja.json"), File("docs/i18n/example-ja.json")).first { it.isFile }
        val catalog = catalog().withPack(file.inputStream().use { LanguagePack.read(it) })
        assertEquals("キャンセル", catalog.format("common.cancel", catalog.resolve("ja", emptyList())))
    }

    private fun assertThrows(problem: PackProblem, block: () -> Unit) {
        try { block(); fail("Expected $problem") }
        catch (e: LanguagePackException) { assertEquals(problem, e.problem) }
    }
}
