package com.heihun.pixivplus.core.i18n

import java.util.Locale

internal data class UiLanguage(val tag: String, val nativeName: String, val builtIn: Boolean) {
    val locale: Locale = Locale.forLanguageTag(tag)
}

/** 不可变的目录快照；读文案时不访问磁盘。 */
internal class LanguageCatalog(
    private val english: LanguagePack,
    private val chinese: LanguagePack,
    val imported: Map<String, LanguagePack> = emptyMap()
) {
    companion object {
        const val FOLLOW_HOST = "system"
        const val ENGLISH = "en"
        const val CHINESE = "zh-CN"
        const val MAX_IMPORTED = 16
    }

    private val builtIns = linkedMapOf(CHINESE to chinese, ENGLISH to english)
    private val packs = builtIns + imported
    val languages: List<UiLanguage> = packs.values.map { UiLanguage(it.tag, it.name, it.tag in builtIns) }
    private val languagesByTag = languages.associateBy { it.tag }

    fun preference(tag: String?): String = LanguagePack.parseLocale(tag)?.toLanguageTag()?.takeIf { it in packs } ?: FOLLOW_HOST

    fun resolve(preference: String?, hostLocales: Iterable<Locale>): UiLanguage {
        val selected = preference(preference)
        if (selected != FOLLOW_HOST) return languagesByTag.getValue(selected)
        for (locale in hostLocales) {
            val tag = locale.stripExtensions().toLanguageTag()
            val language = tag.substringBefore('-')
            val script = locale.script
            val region = locale.country
            val candidates = listOfNotNull(
                tag,
                "$language-$script".takeIf { script.isNotEmpty() },
                "$language-$region".takeIf { region.isNotEmpty() },
                language,
                CHINESE.takeIf { language == "zh" }
            )
            for (candidate in candidates) languagesByTag[candidate]?.let { return it }
        }
        return languagesByTag.getValue(ENGLISH)
    }

    fun format(key: String, language: UiLanguage, vararg args: Any?): String {
        val fallback = english.strings[key] ?: return key
        val pattern = packs[language.tag]?.strings?.get(key) ?: fallback
        if (args.isEmpty()) return pattern.replace("%%", "%")
        return try {
            String.format(language.locale, pattern, *args)
        } catch (_: IllegalArgumentException) {
            // 避免意外的调用参数错误让宿主界面崩溃。
            runCatching { String.format(Locale.ENGLISH, fallback, *args) }.getOrDefault(fallback)
        }
    }

    fun withPack(pack: LanguagePack): LanguageCatalog {
        if (pack.tag in builtIns) throw LanguagePackException(PackProblem.BUILTIN)
        if (pack.tag !in imported && imported.size >= MAX_IMPORTED) throw LanguagePackException(PackProblem.LIMIT)
        val known = pack.strings.filterKeys { it in english.strings }
        if (known.isEmpty()) throw LanguagePackException(PackProblem.EMPTY)
        for ((key, value) in known) {
            val expected = TextFormat.signature(english.strings.getValue(key), strict = true)
            val actual = try { TextFormat.signature(value, strict = expected.isNotEmpty()) }
                catch (_: IllegalArgumentException) { throw LanguagePackException(PackProblem.PLACEHOLDER, key) }
            if (actual != expected) throw LanguagePackException(PackProblem.PLACEHOLDER, key)
        }
        return LanguageCatalog(english, chinese, (imported + (pack.tag to pack)).toSortedMap())
    }

    fun withoutPack(tag: String): LanguageCatalog = LanguageCatalog(english, chinese, imported - tag)

    fun validatePack(strings: Map<String, String>) {
        for ((key, value) in strings) {
            val reference = english.strings[key] ?: continue
            val expected = TextFormat.signature(reference, strict = true)
            val actual = try { TextFormat.signature(value, strict = expected.isNotEmpty()) }
                catch (_: IllegalArgumentException) { throw LanguagePackException(PackProblem.PLACEHOLDER, key) }
            if (actual != expected) throw LanguagePackException(PackProblem.PLACEHOLDER, key)
        }
    }
}
