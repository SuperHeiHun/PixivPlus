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

    private fun readRaw(name: String): String? {
        return try {
            prefs.getString(name, null)
        } catch (t: Throwable) {
            ModuleLog.w("Config", "raw read $name failed", t)
            null
        }
    }
}
