package com.heihun.pixivplus.feature.history

import android.app.Activity
import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.db.AuthorProfileDao
import com.heihun.pixivplus.core.db.HistoryDao
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.net.ApiHooks
import com.heihun.pixivplus.core.net.PageImageCodec
import com.heihun.pixivplus.core.net.PayloadProcessor
import com.heihun.pixivplus.core.util.CurrentWorkHook
import com.heihun.pixivplus.core.util.DetailActivityTracker
import com.heihun.pixivplus.core.util.DetailPagerResolver
import com.heihun.pixivplus.core.util.EntityReader
import com.heihun.pixivplus.core.util.ReflectUtil
import com.heihun.pixivplus.feature.ConfigStoreRegistry
import com.heihun.pixivplus.feature.ConfigurableFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class HistoryFeature : ConfigurableFeature("history", "本地浏览历史", ConfigKeys.HISTORY_ENABLED) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "plp-history").apply { isDaemon = true }
    }

    private val enriched = ConcurrentHashMap<String, Long>()
    private val recorded = ConcurrentHashMap<String, Long>()
    private val detailDepth = java.util.concurrent.atomic.AtomicInteger(0)

    private companion object {
        const val RECORD_INTERVAL_MS = 30000L
    }

    override fun hook(host: Host) {
        PayloadProcessor.historyEnabled = isEnabled(ConfigStoreRegistry.store)
        PayloadProcessor.detailSink = { item, type -> onDetailPayload(item, type) }
        PayloadProcessor.authorSink = { id, name, account, avatar -> saveAuthorProfile(id, name, account, avatar) }
        ApiHooks.ensureInstalled(host)
        hookDetailActivities(host)
        hookCurrentWork(host)
        executor.execute { prune() }
        ModuleLog.i(displayName, "history recording ready (${if (PayloadProcessor.historyEnabled) "enabled" else "disabled"})")
    }

    override fun onRuntimeReady(host: Host) {
        executor.execute { prune() }
    }

    override fun onConfigChanged(host: Host) {
        val enabled = isEnabled(ConfigStoreRegistry.store)
        PayloadProcessor.historyEnabled = enabled
        ModuleLog.i(displayName, "浏览历史已${if (enabled) "开启" else "暂停"}（即时生效）")
    }

    private fun hookDetailActivities(host: Host) {
        hookDetailActivity(
            host,
            "jp.pxv.android.feature.illustviewer.detail.IllustDetailSingleActivity",
            "ILLUST_ID",
            "ILLUST"
        )
        hookDetailActivity(
            host,
            "jp.pxv.android.feature.novelviewer.noveltext.NovelTextActivity",
            "NOVEL_ID",
            "NOVEL"
        )
        hookDetailActivity(
            host,
            "jp.pxv.android.feature.illustviewer.detail.IllustDetailPagerActivity",
            null,
            "ILLUST"
        )
    }

    private fun hookDetailActivity(host: Host, className: String, extraKey: String?, type: String) {
        val clazz = ReflectUtil.loadClass(host.classLoader, className) ?: return
        val method = ReflectUtil.findMethod(clazz, "onCreate", android.os.Bundle::class.java) ?: return
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (extraKey == null) return
                    val activity = param.thisObject as? Activity ?: return
                    val id = readLongExtra(activity, extraKey)
                    if (id <= 0) return
                    recordMinimal(type, id)
                }
            })
            ModuleLog.i(displayName, "detail hook installed: $className")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "detail hook failed: $className", t)
        }
        hookLifecycle(host)
    }

    private fun hookLifecycle(host: Host) {
        val app = host.application ?: return
        try {
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {
                    if (isDetailActivity(activity)) {
                        detailDepth.incrementAndGet()
                        DetailActivityTracker.set(activity)
                    }
                }
                override fun onActivityPaused(activity: Activity) {
                    if (isDetailActivity(activity)) {
                        DetailActivityTracker.clear()
                        val remaining = detailDepth.updateAndGet { value -> if (value > 0) value - 1 else 0 }
                        if (remaining == 0) com.heihun.pixivplus.core.WorkContext.clear()
                    }
                }
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            ModuleLog.i(displayName, "activity lifecycle tracking enabled")
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "lifecycle tracking failed", t)
        }
    }

    private fun isDetailActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return name == "jp.pxv.android.feature.illustviewer.detail.IllustDetailSingleActivity" ||
            name == "jp.pxv.android.feature.illustviewer.detail.IllustDetailPagerActivity" ||
            name == "jp.pxv.android.feature.novelviewer.noveltext.NovelTextActivity"
    }

    private fun hookCurrentWork(host: Host) {
        val installed = CurrentWorkHook.install(host, displayName) { entity, id, defaultType ->
            recordEntity(entity, id, defaultType)
        }
        ModuleLog.i(displayName, "current work hook installed: $installed")
    }

    private fun recordEntity(entity: Any, id: Long, defaultType: String) {
        if (isPaused()) return
        val work = PayloadProcessor.lookupWork(id)
        val rawType = ReflectUtil.readField(entity, "type") as? String
        val type = when {
            !rawType.isNullOrEmpty() -> normalizeType(rawType)
            work != null -> work.type
            else -> normalizeType(defaultType)
        }
        val mapKey = key(type, id)
        val now = System.currentTimeMillis()
        val last = recorded[mapKey]
        if (last != null && now - last < RECORD_INTERVAL_MS) return
        recorded[mapKey] = now
        val title = EntityReader.title(entity) ?: work?.title
        val caption = EntityReader.caption(entity) ?: work?.caption
        val pageCount = EntityReader.pageCount(entity).takeIf { it > 0 } ?: work?.pageCount ?: 0
        val info = EntityReader.authorInfo(entity)
        val authorName = info?.name ?: EntityReader.authorName(entity) ?: work?.authorName
        val authorId = info?.id ?: EntityReader.authorId(entity) ?: work?.authorId
        val authorAccount = info?.account ?: work?.authorAccount
        val thumbnail = EntityReader.thumbnail(entity) ?: work?.thumbnail
        val tagList = EntityReader.tags(entity).ifEmpty { work?.tags.orEmpty() }
        val tags = tagList.takeIf { it.isNotEmpty() }?.joinToString(",")
        val width = EntityReader.width(entity).takeIf { it > 0 } ?: (work?.width ?: 0)
        val height = EntityReader.height(entity).takeIf { it > 0 } ?: (work?.height ?: 0)
        val originalUrl = EntityReader.originalUrl(entity) ?: work?.originalUrl
        val pageImages = EntityReader.pageImages(entity).ifEmpty { work?.pageImages.orEmpty() }
        com.heihun.pixivplus.core.WorkContext.update(
            com.heihun.pixivplus.core.WorkSnapshot(
                id = id,
                type = type,
                title = title,
                authorId = authorId,
                authorName = authorName,
                authorAccount = EntityReader.authorAccount(entity),
                tags = tagList
            )
        )
        ModuleLog.d(
            displayName,
            "history captured from entity: $type id=$id title=$title tags=${tagList.size}"
        )
        if (authorId != null && authorId > 0L) {
            saveAuthorProfile(authorId, authorName, authorAccount, info?.avatarUrl ?: work?.avatarUrl)
        }
        executor.execute {
            val dao = Runtime.historyDaoOrNull() ?: return@execute
            dao.upsert(
                HistoryDao.HistoryItem(
                    type = type,
                    contentId = id,
                    title = title,
                    authorId = authorId,
                    authorName = authorName,
                    thumbnailUrl = thumbnail,
                    tags = tags,
                    caption = caption,
                    pageCount = pageCount,
                    visitedAt = now,
                    width = width,
                    height = height,
                    originalUrl = originalUrl,
                    pageImages = PageImageCodec.encode(pageImages)
                )
            )
        }
    }

    private fun readLongExtra(activity: Activity, key: String): Long {
        return try {
            activity.intent?.getLongExtra(key, -1L) ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun recordMinimal(type: String, id: Long) {
        if (isPaused()) return
        val key = key(type, id)
        if (enriched.containsKey(key)) return
        val work = PayloadProcessor.lookupWork(id)
        executor.execute {
            val dao = Runtime.historyDaoOrNull() ?: return@execute
            dao.upsert(
                HistoryDao.HistoryItem(
                    type = type,
                    contentId = id,
                    title = work?.title,
                    authorId = work?.authorId,
                    authorName = work?.authorName,
                    thumbnailUrl = work?.thumbnail,
                    tags = work?.tags?.takeIf { it.isNotEmpty() }?.joinToString(","),
                    caption = work?.caption,
                    pageCount = work?.pageCount ?: 0,
                    visitedAt = System.currentTimeMillis(),
                    width = work?.width ?: 0,
                    height = work?.height ?: 0,
                    originalUrl = work?.originalUrl,
                    pageImages = PageImageCodec.encode(work?.pageImages.orEmpty())
                )
            )
        }
    }

    private fun onDetailPayload(item: JSONObject, rawType: String) {
        if (isPaused()) return
        val id = item.optLong("id")
        if (id <= 0) return
        if (!isVisibleWork(id)) {
            ModuleLog.d(displayName, "skip adjacent detail payload id=$id")
            return
        }
        val type = normalizeType(rawType)
        enriched[key(type, id)] = System.currentTimeMillis()
        ModuleLog.i(displayName, "detail payload captured: $type id=$id title=${item.optString("title")}")
        val user = item.optJSONObject("user")
        val authorId = user?.optLong("id")?.takeIf { it > 0L }
        val avatar = user?.optJSONObject("profile_image_urls")?.optString("medium")?.ifEmpty { null }
        val account = user?.optString("account")?.ifEmpty { null }
        if (authorId != null) {
            saveAuthorProfile(authorId, user?.optString("name")?.ifEmpty { null }, account, avatar)
        }
        executor.execute {
            val dao = Runtime.historyDaoOrNull() ?: return@execute
            dao.upsert(buildItem(item, type, id))
        }
    }

    private fun saveAuthorProfile(id: Long, name: String?, account: String?, avatarUrl: String?) {
        if (id <= 0L) return
        if (name == null && account == null && avatarUrl == null) return
        executor.execute {
            val dao = Runtime.authorProfileDaoOrNull() ?: return@execute
            dao.upsert(
                AuthorProfileDao.Profile(
                    authorId = id,
                    name = name,
                    account = account,
                    avatarUrl = avatarUrl
                )
            )
        }
    }

    private fun buildItem(item: JSONObject, type: String, id: Long): HistoryDao.HistoryItem {
        val user = item.optJSONObject("user")
        val imageUrls = item.optJSONObject("image_urls")
        val tags = item.optJSONArray("tags")
        val metaPages = PageImageCodec.fromJson(item.optJSONArray("meta_pages"))
        val originalUrl = item.optJSONObject("meta_single_page")
            ?.let { PageImageCodec.stringOrNull(it, "original_image_url") }
        return HistoryDao.HistoryItem(
            type = type,
            contentId = id,
            title = item.optString("title").ifEmpty { null },
            authorId = user?.optLong("id")?.takeIf { it > 0 },
            authorName = user?.optString("name")?.ifEmpty { null },
            thumbnailUrl = imageUrls?.optString("medium")?.ifEmpty { imageUrls.optString("square_medium") }?.ifEmpty { null },
            tags = joinTags(tags),
            caption = item.optString("caption").ifEmpty { null },
            pageCount = item.optInt("page_count"),
            visitedAt = System.currentTimeMillis(),
            width = item.optInt("width"),
            height = item.optInt("height"),
            originalUrl = originalUrl,
            pageImages = PageImageCodec.encode(metaPages)
        )
    }

    private fun joinTags(array: JSONArray?): String? {
        if (array == null || array.length() == 0) return null
        val names = ArrayList<String>()
        for (index in 0 until array.length()) {
            val tag = array.optJSONObject(index) ?: continue
            val name = tag.optString("name")
            if (name.isNotEmpty()) names.add(name)
        }
        return if (names.isEmpty()) null else names.joinToString(",")
    }

    private fun isVisibleWork(id: Long): Boolean {
        val activity = DetailActivityTracker.get() ?: return true
        return DetailPagerResolver.isCurrentWork(activity, id)
    }

    private fun normalizeType(raw: String): String {
        return when (raw.lowercase()) {
            "manga" -> "MANGA"
            "novel" -> "NOVEL"
            else -> "ILLUST"
        }
    }

    private fun key(type: String, id: Long): String = "$type:$id"

    private fun isPaused(): Boolean {
        return try {
            !isEnabled(ConfigStoreRegistry.store) || ConfigStoreRegistry.store.get(ConfigKeys.HISTORY_PAUSED)
        } catch (_: Throwable) {
            false
        }
    }

    private fun prune() {
        try {
            val dao = Runtime.historyDaoOrNull() ?: return
            val days = ConfigStoreRegistry.store.get(ConfigKeys.HISTORY_RETENTION_DAYS).toIntOrNull() ?: 30
            if (days <= 0) return
            val threshold = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
            val removed = dao.prune(threshold)
            if (removed > 0) {
                ModuleLog.i(displayName, "pruned $removed expired entries (retention=${days}d)")
            }
        } catch (t: Throwable) {
            ModuleLog.w(displayName, "prune failed", t)
        }
    }
}
