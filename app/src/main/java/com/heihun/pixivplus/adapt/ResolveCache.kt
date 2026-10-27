package com.heihun.pixivplus.adapt

import android.content.Context
import android.content.SharedPreferences

class ResolveCache(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(versionCode: Long, specId: String): ResolveStatus? {
        val raw = try {
            prefs.getString(key(versionCode, specId), null)
        } catch (_: Throwable) {
            null
        } ?: return null
        val separator = raw.indexOf('|')
        if (separator <= 0) return null
        val via = raw.substring(0, separator)
        val signature = raw.substring(separator + 1)
        return if (via == "failed") ResolveStatus.Failed(signature) else ResolveStatus.Resolved(signature, via)
    }

    fun write(versionCode: Long, specId: String, status: ResolveStatus) {
        try {
            val value = when (status) {
                is ResolveStatus.Resolved -> "${status.via}|${status.signature}"
                is ResolveStatus.Failed -> "failed|${status.reason}"
            }
            prefs.edit().putString(key(versionCode, specId), value).apply()
        } catch (_: Throwable) {
        }
    }

    fun clear() {
        try {
            prefs.edit().clear().apply()
        } catch (_: Throwable) {
        }
    }

    private fun key(versionCode: Long, specId: String): String = "v$versionCode#$specId"

    companion object {
        const val PREFS_NAME = "pixiv_local_plus_resolve_cache"
    }
}
