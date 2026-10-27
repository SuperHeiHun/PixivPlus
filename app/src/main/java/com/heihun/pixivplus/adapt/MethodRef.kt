package com.heihun.pixivplus.adapt

import com.heihun.pixivplus.core.util.ReflectUtil
import java.lang.reflect.Method

data class MethodRef(
    val className: String,
    val methodName: String,
    val paramTypeNames: List<String>
) {

    fun signature(): String = "$className.$methodName(${paramTypeNames.joinToString(", ")})"

    fun resolve(classLoader: ClassLoader): Method? {
        val clazz = ReflectUtil.loadClass(classLoader, className) ?: return null
        val types = ArrayList<Class<*>>(paramTypeNames.size)
        for (name in paramTypeNames) {
            val type = ReflectUtil.loadClass(classLoader, name) ?: return null
            types.add(type)
        }
        return ReflectUtil.findMethod(clazz, methodName, *types.toTypedArray())
    }
}
