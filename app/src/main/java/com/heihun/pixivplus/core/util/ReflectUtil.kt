package com.heihun.pixivplus.core.util

import java.lang.reflect.Field
import java.lang.reflect.Method

object ReflectUtil {

    private val NULL_MARKER = Any()

    private val fieldCache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private val methodCache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun findField(type: Class<*>, name: String): Field? {
        val key = "${type.name}#$name"
        val cached = fieldCache.getOrPut(key) { resolveField(type, name) ?: NULL_MARKER }
        return if (cached === NULL_MARKER) null else cached as Field
    }

    private fun resolveField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: Throwable) {
                current = current.superclass
            }
        }
        return null
    }

    fun readField(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            val field = findField(target.javaClass, name) ?: return null
            field.isAccessible = true
            field.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    fun readStaticField(type: Class<*>, name: String): Any? {
        return try {
            val field = findField(type, name) ?: return null
            field.isAccessible = true
            field.get(null)
        } catch (_: Throwable) {
            null
        }
    }

    fun findMethod(type: Class<*>, name: String, vararg params: Class<*>): Method? {
        val key = buildString {
            append(type.name)
            append('#')
            append(name)
            append('(')
            params.forEachIndexed { index, param ->
                if (index > 0) append(',')
                append(param.name)
            }
            append(')')
        }
        val cached = methodCache.getOrPut(key) { resolveMethod(type, name, params) ?: NULL_MARKER }
        return if (cached === NULL_MARKER) null else cached as Method
    }

    private fun resolveMethod(type: Class<*>, name: String, params: Array<out Class<*>>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *params)
            } catch (_: Throwable) {
                current = current.superclass
            }
        }
        return null
    }

    fun findMethods(type: Class<*>, name: String, paramCount: Int? = null): List<Method> {
        val result = ArrayList<Method>()
        var current: Class<*>? = type
        while (current != null) {
            try {
                for (method in current.declaredMethods) {
                    if (method.name != name) continue
                    if (paramCount != null && method.parameterTypes.size != paramCount) continue
                    result.add(method)
                }
            } catch (_: Throwable) {
            }
            current = current.superclass
        }
        return result
    }

    fun invoke(instance: Any?, method: Method?, vararg args: Any?): Any? {
        if (instance == null || method == null) return null
        return try {
            method.isAccessible = true
            method.invoke(instance, *args)
        } catch (_: Throwable) {
            null
        }
    }

    fun loadClass(classLoader: ClassLoader?, name: String): Class<*>? {
        if (classLoader == null) return null
        return try {
            Class.forName(name, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }
}
