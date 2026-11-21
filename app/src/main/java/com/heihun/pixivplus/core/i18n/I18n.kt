package com.heihun.pixivplus.core.i18n

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigKeys
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal enum class UiLanguage(val tag: String, val nativeName: String) {
    FOLLOW_HOST("system", ""),
    CHINESE("zh-CN", "简体中文"),
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語");

    val locale: Locale = Locale.forLanguageTag(tag)

    companion object {
        fun fromSetting(tag: String?): UiLanguage = entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
            ?: FOLLOW_HOST

        /** 尊重宿主 LocaleList 的优先级；列表无受支持语言时回退英文。 */
        fun resolve(preference: UiLanguage, hostLocales: Iterable<Locale>): UiLanguage {
            if (preference != FOLLOW_HOST) return preference
            for (locale in hostLocales) {
                when (locale.language) {
                    "zh" -> return CHINESE
                    "en" -> return ENGLISH
                    "ja" -> return JAPANESE
                }
            }
            return ENGLISH
        }
    }
}

internal object I18n {
    fun preference(): UiLanguage = UiLanguage.fromSetting(Runtime.configOrNull()?.get(ConfigKeys.UI_LANGUAGE))

    fun language(): UiLanguage {
        val preferred = preference()
        if (preferred != UiLanguage.FOLLOW_HOST) return preferred
        val locales = Host.context()?.resources?.configuration?.locales
        val current = if (locales == null || locales.isEmpty) listOf(Locale.getDefault())
            else List(locales.size()) { locales[it] }
        return UiLanguage.resolve(preferred, current)
    }

    fun dateTime(millis: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.SHORT, language().locale
    ).format(Date(millis))
}

/** 文案跟随模块代码加载，无需将资源注入 Pixiv；带参数的模板使用带序号的格式项。 */
internal class Text(val zh: String, val en: String, val ja: String) {
    fun pattern(language: UiLanguage): String = when (language) {
        UiLanguage.CHINESE -> zh
        UiLanguage.JAPANESE -> ja
        else -> en
    }

    fun format(language: UiLanguage, vararg args: Any?): String =
        if (args.isEmpty()) pattern(language) else String.format(language.locale, pattern(language), *args)

    operator fun invoke(vararg args: Any?): String = format(I18n.language(), *args)
}
