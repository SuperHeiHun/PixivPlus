package com.heihun.pixivplus.adapt

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import java.util.concurrent.ConcurrentHashMap

object ResolveRegistry {

    private val statuses = ConcurrentHashMap<String, ResolveStatus>()
    private val descriptions = ConcurrentHashMap<String, String>()

    @Volatile
    private var cache: ResolveCache? = null

    @Volatile
    private var dexKitResolver: DexKitResolver? = null

    @Volatile
    private var runtimeResolver: RuntimeFallbackResolver? = null

    fun initialize(host: Host, resolveCache: ResolveCache) {
        cache = resolveCache
        runtimeResolver = RuntimeFallbackResolver(host.classLoader)
        dexKitResolver = try {
            DexKitResolver(host.apkPath)
        } catch (t: Throwable) {
            ModuleLog.w("ResolveRegistry", "dexkit resolver unavailable", t)
            null
        }
    }

    fun resolveAll(host: Host, specs: List<ResolveSpec>) {
        for (spec in specs) {
            descriptions[spec.id] = spec.description
            val cached = cache?.read(host.versionCode, spec.id)
            if (cached is ResolveStatus.Resolved) {
                statuses[spec.id] = cached
                ModuleLog.i("ResolveRegistry", "${spec.id} -> ${cached.signature} (via=${cached.via}, cached)")
                continue
            }
            val status = resolveWithFallback(spec)
            statuses[spec.id] = status
            if (status is ResolveStatus.Resolved) {
                cache?.write(host.versionCode, spec.id, status)
            }
            ModuleLog.i("ResolveRegistry", "${spec.id} -> ${status.signature} (via=${status.via})")
        }
    }

    fun resolve(spec: ResolveSpec): ResolveStatus {
        descriptions[spec.id] = spec.description
        val status = resolveWithFallback(spec)
        statuses[spec.id] = status
        return status
    }

    fun status(specId: String): ResolveStatus? = statuses[specId]

    fun description(specId: String): String = descriptions[specId] ?: specId

    fun dexKit(): DexKitResolver? = dexKitResolver

    fun report(specId: String, description: String, status: ResolveStatus) {
        descriptions[specId] = description
        statuses[specId] = status
    }

    fun all(): List<Pair<String, ResolveStatus>> = statuses.entries
        .sortedBy { it.key }
        .map { it.key to it.value }

    fun clearCache() {
        cache?.clear()
        statuses.clear()
    }

    private fun resolveWithFallback(spec: ResolveSpec): ResolveStatus {
        val runtime = runtimeResolver
        val dexKit = dexKitResolver
        if (dexKit != null) {
            val status = dexKit.resolve(spec)
            if (status is ResolveStatus.Resolved) return status
            if (runtime != null) {
                val fallback = runtime.resolve(spec)
                if (fallback is ResolveStatus.Resolved) {
                    return ResolveStatus.Resolved(fallback.signature, fallback.via)
                }
                return ResolveStatus.Failed("dexkit: ${(status as ResolveStatus.Failed).reason}; runtime: ${(fallback as ResolveStatus.Failed).reason}")
            }
            return status
        }
        if (runtime != null) return runtime.resolve(spec)
        return ResolveStatus.Failed("no resolver available")
    }
}
