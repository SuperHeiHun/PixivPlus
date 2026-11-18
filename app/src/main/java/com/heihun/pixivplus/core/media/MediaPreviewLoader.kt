package com.heihun.pixivplus.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.net.ProxyHttp
import java.net.URL

/** 小图预览走模块代理，限制响应大小并采样解码，不缓存原图到磁盘。 */
object MediaPreviewLoader {
    private val caches = java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<android.util.LruCache<Int, Bitmap>>>()
    fun register(cache: android.util.LruCache<Int, Bitmap>) {
        caches.removeAll { it.get() == null }
        caches.add(java.lang.ref.WeakReference(cache))
    }
    fun clearCaches() { caches.forEach { it.get()?.evictAll() }; caches.removeAll { it.get() == null } }
    fun load(store: ConfigStore, url: String, targetSize: Int): Bitmap? {
        MediaModels.requireImageUrl(url)
        val response = ProxyHttp.getBytes(store, URL(url), mapOf("Referer" to "https://www.pixiv.net/"),
            maxBytes = 8 * 1024 * 1024, readTimeoutMs = 15000) ?: return null
        if (response.code != 200 || response.bytes.isEmpty()) return null
        val bytes = response.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..65535 || bounds.outHeight !in 1..65535) return null
        var sample = 1
        while (bounds.outWidth / sample > targetSize * 2 || bounds.outHeight / sample > targetSize * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
}
