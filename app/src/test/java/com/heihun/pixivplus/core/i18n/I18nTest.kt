package com.heihun.pixivplus.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class I18nTest {
    @Test fun `override takes precedence over host and does not change the process locale`() {
        val original = Locale.getDefault()
        assertEquals(UiLanguage.JAPANESE, UiLanguage.resolve(UiLanguage.JAPANESE, listOf(Locale.CHINA)))
        assertEquals(original, Locale.getDefault())
    }

    @Test fun `follow host picks the first supported locale across regional variants`() {
        assertEquals(UiLanguage.CHINESE, UiLanguage.resolve(UiLanguage.FOLLOW_HOST, listOf(Locale.KOREA, Locale.TAIWAN, Locale.JAPAN)))
        assertEquals(UiLanguage.ENGLISH, UiLanguage.resolve(UiLanguage.FOLLOW_HOST, listOf(Locale.UK, Locale.JAPAN)))
        assertEquals(UiLanguage.JAPANESE, UiLanguage.resolve(UiLanguage.FOLLOW_HOST, listOf(Locale.JAPAN, Locale.ENGLISH)))
    }

    @Test fun `unsupported locales fall back to English and invalid preferences follow the host`() {
        assertEquals(UiLanguage.FOLLOW_HOST, UiLanguage.fromSetting("bogus"))
        assertEquals(UiLanguage.FOLLOW_HOST, UiLanguage.fromSetting(null))
        assertEquals(UiLanguage.CHINESE, UiLanguage.fromSetting("ZH-cn"))
        assertEquals(UiLanguage.ENGLISH, UiLanguage.resolve(UiLanguage.FOLLOW_HOST, listOf(Locale.FRANCE)))
        assertEquals(UiLanguage.ENGLISH, UiLanguage.resolve(UiLanguage.FOLLOW_HOST, emptyList()))
    }

    @Test fun `templates allow reordering and preserve text passed as data`() {
        val template = Text("%1\$s：%2\$d 张", "%2\$d images by %1\$s", "%1\$s：%2\$d 枚")
        assertEquals("3 images by 100% {title}", template.format(UiLanguage.ENGLISH, "100% {title}", 3))
        assertEquals("100% {title}：3 张", template.format(UiLanguage.CHINESE, "100% {title}", 3))
        assertEquals("作者：3 枚", template.format(UiLanguage.JAPANESE, "作者", 3))
    }
}
