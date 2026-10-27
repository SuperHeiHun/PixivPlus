package com.heihun.pixivplus.adapt

import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.ReflectUtil

class RuntimeFallbackResolver(private val classLoader: ClassLoader) : Resolver {

    override fun name(): String = "runtime"

    override fun resolve(spec: ResolveSpec): ResolveStatus {
        return try {
            when (spec) {
                is ResolveSpec.ClassSpec -> resolveClass(spec)
                is ResolveSpec.MethodSpec -> resolveMethod(spec)
                is ResolveSpec.FieldSpec -> resolveField(spec)
                is ResolveSpec.StringSpec -> ResolveStatus.Failed("runtime resolver cannot locate literal '${spec.literal}'")
                is ResolveSpec.MethodByLiteralSpec -> ResolveStatus.Failed("runtime resolver cannot locate method by literal '${spec.literal}'")
            }
        } catch (t: Throwable) {
            ResolveStatus.Failed("runtime error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun loadClass(className: String): Class<*>? = ReflectUtil.loadClass(classLoader, className)

    fun findFieldBySerializedName(className: String, jsonName: String): String? {
        val clazz = loadClass(className) ?: return null
        return try {
            clazz.declaredFields.firstOrNull { field ->
                val annotation = field.annotations.firstOrNull() ?: return@firstOrNull false
                val value = readAnnotationString(annotation)
                value == jsonName
            }?.name
        } catch (t: Throwable) {
            ModuleLog.w("RuntimeFallbackResolver", "annotation scan failed for $className", t)
            null
        }
    }

    private fun readAnnotationString(annotation: Annotation): String? {
        return try {
            val method = annotation.annotationClass.java.getMethod("value")
            method.invoke(annotation) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveClass(spec: ResolveSpec.ClassSpec): ResolveStatus {
        val clazz = loadClass(spec.className) ?: return ResolveStatus.Failed("runtime: class not found ${spec.className}")
        return ResolveStatus.Resolved(clazz.name, "runtime")
    }

    private fun resolveMethod(spec: ResolveSpec.MethodSpec): ResolveStatus {
        val clazz = loadClass(spec.className) ?: return ResolveStatus.Failed("runtime: class not found ${spec.className}")
        val candidates = clazz.declaredMethods.filter { it.name == spec.methodName }
        if (candidates.isEmpty()) return ResolveStatus.Failed("runtime: method ${spec.methodName} not found in ${spec.className}")
        val match = candidates.firstOrNull { it.parameterTypes.size == spec.parameterTypes.size }
            ?: candidates.first()
        val signature = "${match.name}(${match.parameterTypes.joinToString(", ") { it.simpleName }})"
        return ResolveStatus.Resolved(signature, "runtime")
    }

    private fun resolveField(spec: ResolveSpec.FieldSpec): ResolveStatus {
        val clazz = loadClass(spec.className) ?: return ResolveStatus.Failed("runtime: class not found ${spec.className}")
        val field = ReflectUtil.findField(clazz, spec.fieldName)
            ?: return ResolveStatus.Failed("runtime: field ${spec.fieldName} not found in ${spec.className}")
        return ResolveStatus.Resolved("${field.name}: ${field.type.simpleName}", "runtime")
    }
}
