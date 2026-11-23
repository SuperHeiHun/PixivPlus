package com.heihun.pixivplus.core.i18n

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.log.ModuleLog
import java.io.File
import java.io.InputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal object I18n {
    private const val RESOURCE_ROOT = "assets/pixivplus/i18n"
    private val builtIns by lazy {
        LanguageCatalog(loadBuiltIn(LanguageCatalog.ENGLISH), loadBuiltIn(LanguageCatalog.CHINESE))
    }
    private val lock = Any()
    @Volatile private var installed: LanguageCatalog? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val updatePending = AtomicBoolean(false)

    fun bind(store: ConfigStore) {
        store.addListener(ConfigKeys.UI_LANGUAGE) { notifyChanged() }
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    fun loadInstalled(context: Context) {
        val loaded = synchronized(lock) { ensureLoaded(context) }
        if (loaded) notifyChanged()
    }

    private fun ensureLoaded(context: Context): Boolean {
        if (installed != null) return false
        val catalog = store(context).load(builtIns) { error ->
            ModuleLog.w("I18n", "Skipped an unreadable language pack: ${error.javaClass.simpleName}")
        }
        installed = catalog
        return catalog.imported.isNotEmpty()
    }

    /** 在后台读取文档；只复制校验过的 JSON，不保留文档 URI 或授权。 */
    fun importPack(context: Context, input: InputStream): UiLanguage {
        val language = synchronized(lock) {
            ensureLoaded(context)
            val pack = LanguagePack.read(input, catalog()::validatePack)
            val next = catalog().withPack(pack)
            store(context).save(pack)
            installed = next
            next.languages.first { it.tag == pack.tag }
        }
        notifyChanged()
        return language
    }

    fun removePack(context: Context, tag: String) {
        synchronized(lock) {
            ensureLoaded(context)
            if (tag !in catalog().imported) return
            store(context).remove(tag)
            installed = catalog().withoutPack(tag)
        }
        notifyChanged()
    }

    private fun catalog() = installed ?: builtIns

    private fun store(context: Context) = LanguagePackStore(File(context.filesDir, "pixivplus_language_packs"))

    private fun loadBuiltIn(tag: String): LanguagePack {
        // 通过模块 APK classLoader 读取 APK assets，避免错误使用宿主 Pixiv 的 AssetManager。
        return checkNotNull(I18n::class.java.classLoader?.getResourceAsStream("$RESOURCE_ROOT/$tag.json")) {
            "Missing built-in language pack: $tag"
        }.use { LanguagePack.read(it) }.also { check(it.tag == tag) }
    }

    private fun notifyChanged() {
        val looper = Looper.getMainLooper() ?: return
        if (!updatePending.compareAndSet(false, true)) return
        Handler(looper).post {
            updatePending.set(false)
            listeners.forEach { listener ->
                try { listener() } catch (e: Exception) { ModuleLog.w("I18n", "Language refresh failed", e) }
            }
        }
    }

    fun languages(): List<UiLanguage> = catalog().languages

    fun preference(): String = catalog().preference(configuredLanguage())

    fun select(tag: String) {
        Runtime.configOrNull()?.put(ConfigKeys.UI_LANGUAGE, catalog().preference(tag))
    }

    private fun configuredLanguage() = Runtime.configOrNull()?.get(ConfigKeys.UI_LANGUAGE)

    private fun hostLocales(): List<Locale> {
        val locales = Host.context()?.resources?.configuration?.locales
        return if (locales == null || locales.isEmpty) listOf(Locale.getDefault()) else List(locales.size()) { locales[it] }
    }

    fun language(): UiLanguage = catalog().resolve(configuredLanguage(), hostLocales())

    fun format(key: String, vararg args: Any?): String = catalog().let {
        it.format(key, it.resolve(configuredLanguage(), hostLocales()), *args)
    }

    fun dateTime(millis: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.SHORT, language().locale
    ).format(Date(millis))
}

/** 稳定的 JSON 键；界面调用点不保存或比较翻译后的字符串。 */
internal class Text(internal val key: String) {
    operator fun invoke(vararg args: Any?): String = I18n.format(key, *args)
}
