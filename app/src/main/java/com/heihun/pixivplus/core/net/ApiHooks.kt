package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean

object ApiHooks {

    private const val RESPONSE_BODY_CLASS = "okhttp3.ResponseBody"
    private const val SERVER_INTERCEPTOR_CLASS = "okhttp3.internal.http.CallServerInterceptor"
    private const val SEARCH_PATH = "/v1/search"
    private const val TRACE_LIMIT = 40

    private val installed = AtomicBoolean(false)
    private val charStreamCalls = java.util.concurrent.atomic.AtomicInteger(0)
    private val stringCalls = java.util.concurrent.atomic.AtomicInteger(0)

    fun ensureInstalled(host: Host) {
        if (!installed.compareAndSet(false, true)) return
        val clazz = ReflectUtil.loadClass(host.classLoader, RESPONSE_BODY_CLASS)
        if (clazz == null) {
            ModuleLog.w("ApiHooks", "okhttp3.ResponseBody not found, api payload hooks skipped")
            installed.set(false)
            return
        }
        hookCharStream(clazz)
        hookString(clazz)
        hookBytes(clazz)
        hookSearchTrace(host)
        PopularSearchRewrite.install(host)
        ModuleLog.i("ApiHooks", "response body hooks installed")
    }

    private fun hookSearchTrace(host: Host) {
        val clazz = ReflectUtil.loadClass(host.classLoader, SERVER_INTERCEPTOR_CLASS) ?: return
        val method = ReflectUtil.findMethods(clazz, "intercept")
            .firstOrNull { it.parameterCount == 1 } ?: return
        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val chain = param.args?.firstOrNull() ?: return
                    val request = ReflectUtil.invoke(chain, ReflectUtil.findMethod(chain.javaClass, "request")) ?: return
                    val url = ReflectUtil.invoke(request, ReflectUtil.findMethod(request.javaClass, "url")) ?: return
                    val text = ReflectUtil.invoke(url, ReflectUtil.findMethod(url.javaClass, "toString")) as? String ?: return
                    if (!text.contains(SEARCH_PATH)) return
                    ModuleLog.d("ApiHooks", "search request: $text")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val response = param.result ?: return
                    val code = ReflectUtil.invoke(response, ReflectUtil.findMethod(response.javaClass, "code"))
                    val request = ReflectUtil.invoke(response, ReflectUtil.findMethod(response.javaClass, "request"))
                    val url = request?.let {
                        ReflectUtil.invoke(it, ReflectUtil.findMethod(it.javaClass, "url"))
                    }
                    val text = url?.let {
                        ReflectUtil.invoke(it, ReflectUtil.findMethod(it.javaClass, "toString")) as? String
                    } ?: return
                    if (!text.contains(SEARCH_PATH)) return
                    ModuleLog.d("ApiHooks", "search response: code=$code url=$text")
                }
            })
        } catch (t: Throwable) {
            ModuleLog.w("ApiHooks", "hook search trace failed", t)
        }
    }

    private fun trace(counter: java.util.concurrent.atomic.AtomicInteger, kind: String, text: String) {
        val index = counter.incrementAndGet()
        if (index <= TRACE_LIMIT) {
            ModuleLog.i("ApiHooks", "$kind #$index (${text.length} chars): ${text.take(160)}")
            writeDebugBody(kind, index, text)
        }
    }

    private fun writeDebugBody(kind: String, index: Int, text: String) {
        try {
            val context = Host.context() ?: return
            val dir = java.io.File(context.cacheDir, "plp_bodies")
            dir.mkdirs()
            java.io.File(dir, "${kind}_$index.json").writeText(text)
        } catch (_: Throwable) {
        }
    }

    private fun hookCharStream(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "charStream", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val reader = param.result as? Reader ?: return
                    val text = readAll(reader) ?: return
                    trace(charStreamCalls, "charStream", text)
                    val output = PayloadProcessor.process(text) ?: text
                    param.result = StringReader(output)
                }
            })
        } catch (t: Throwable) {
            ModuleLog.w("ApiHooks", "hook charStream failed", t)
        }
    }

    private fun hookString(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "string", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val original = param.result as? String ?: return
                    trace(stringCalls, "string", original)
                    val output = PayloadProcessor.process(original) ?: return
                    param.result = output
                }
            })
        } catch (t: Throwable) {
            ModuleLog.w("ApiHooks", "hook string failed", t)
        }
    }

    private fun hookBytes(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "bytes", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val bytes = param.result as? ByteArray ?: return
                    val body = param.thisObject
                    val charset = readCharset(body)
                    val text = try {
                        String(bytes, charset)
                    } catch (_: Throwable) {
                        return
                    }
                    if (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("[")) return
                    val output = PayloadProcessor.process(text) ?: return
                    param.result = output.toByteArray(charset)
                }
            })
        } catch (t: Throwable) {
            ModuleLog.w("ApiHooks", "hook bytes failed", t)
        }
    }

    private fun readCharset(body: Any?): java.nio.charset.Charset {
        return try {
            val method = ReflectUtil.findMethod(body?.javaClass ?: Any::class.java, "charset")
            val charset = ReflectUtil.invoke(body, method)
            if (charset is java.nio.charset.Charset) charset else Charsets.UTF_8
        } catch (_: Throwable) {
            Charsets.UTF_8
        }
    }

    private fun readAll(reader: Reader): String? {
        return try {
            val buffer = CharArray(8192)
            val builder = StringBuilder()
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                builder.append(buffer, 0, read)
            }
            builder.toString()
        } catch (t: Throwable) {
            ModuleLog.w("ApiHooks", "read body failed", t)
            null
        }
    }
}
