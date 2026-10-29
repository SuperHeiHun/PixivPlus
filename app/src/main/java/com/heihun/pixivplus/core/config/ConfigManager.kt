package com.heihun.pixivplus.core.config

import android.content.SharedPreferences
import com.heihun.pixivplus.core.log.ModuleLog
import java.util.concurrent.CopyOnWriteArrayList

interface ConfigStore {

    fun <T> get(key: ConfigKey<T>): T

    fun <T> put(key: ConfigKey<T>, value: T)

    fun addListener(key: ConfigKey<*>, listener: (Any?) -> Unit)

    fun removeListener(listener: (Any?) -> Unit)
}

class ConfigManager(private val prefs: SharedPreferences) : ConfigStore {

    private val listeners = CopyOnWriteArrayList<Pair<String, (Any?) -> Unit>>()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
        if (changedKey == null) return@OnSharedPreferenceChangeListener
        for ((key, listener) in listeners) {
            if (key == changedKey) {
                try {
                    listener(readRaw(changedKey))
                } catch (t: Throwable) {
                    ModuleLog.w("Config", "listener failed for $changedKey", t)
                }
            }
        }
    }

    init {
        try {
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        } catch (t: Throwable) {
            ModuleLog.w("Config", "register listener failed", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: ConfigKey<T>): T {
        return try {
            val raw = readRaw(key.name)
            if (raw == null) key.defaultValue else key.decode(raw)
        } catch (t: Throwable) {
            ModuleLog.w("Config", "read ${key.name} failed", t)
            key.defaultValue
        }
    }

    override fun <T> put(key: ConfigKey<T>, value: T) {
        try {
            prefs.edit().putString(key.name, key.encode(value)).apply()
        } catch (t: Throwable) {
            ModuleLog.w("Config", "write ${key.name} failed", t)
        }
    }

    override fun addListener(key: ConfigKey<*>, listener: (Any?) -> Unit) {
        listeners.add(key.name to listener)
    }

    override fun removeListener(listener: (Any?) -> Unit) {
        listeners.removeAll { it.second === listener }
    }

    /** 导出所有字符串配置；用于数据备份。 */
    fun snapshotStrings(): Map<String, String> {
        return try {
            prefs.all.entries.mapNotNull { (name, value) ->
                (value as? String)?.let { name to it }
            }.toMap()
        } catch (t: Throwable) {
            ModuleLog.w("Config", "snapshot failed", t)
            emptyMap()
        }
    }

    /** 恢复配置；密码类配置保留本机现有值，避免备份文件携带或清空凭据。 */
    fun restoreStrings(values: Map<String, String>): Int {
        return try {
            val preserved = ConfigKeys.LOCAL_ONLY_NAMES.mapNotNull { name ->
                readRaw(name)?.let { name to it }
            }
            val editor = prefs.edit().clear()
            for ((name, value) in values) {
                if (name in ConfigKeys.LOCAL_ONLY_NAMES) continue
                editor.putString(name, value)
            }
            for ((name, value) in preserved) {
                editor.putString(name, value)
            }
            editor.apply()
            values.size
        } catch (t: Throwable) {
            ModuleLog.w("Config", "restore failed", t)
            0
        }
    }

    private fun readRaw(name: String): String? {
        return try {
            prefs.getString(name, null)
        } catch (t: Throwable) {
            ModuleLog.w("Config", "raw read $name failed", t)
            null
        }
    }
}
