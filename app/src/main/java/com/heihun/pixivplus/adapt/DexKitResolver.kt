package com.heihun.pixivplus.adapt

import com.heihun.pixivplus.core.Host
import com.heihun.pixivplus.core.log.ModuleLog
import com.heihun.pixivplus.core.util.NativeLibLoader
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.enums.AnnotationEncodeValueType
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.result.AnnotationData

class DexKitResolver(private val apkPath: String) : Resolver {

    private var bridge: DexKitBridge? = null
    private var unavailableReason: String? = null

    override fun name(): String = "dexkit"

    override fun resolve(spec: ResolveSpec): ResolveStatus {
        return try {
            when (spec) {
                is ResolveSpec.ClassSpec -> resolveClass(spec)
                is ResolveSpec.MethodSpec -> resolveMethod(spec)
                is ResolveSpec.FieldSpec -> resolveField(spec)
                is ResolveSpec.StringSpec -> resolveString(spec)
                is ResolveSpec.MethodByLiteralSpec -> resolveMethodByLiteral(spec)
            }
        } catch (t: Throwable) {
            ResolveStatus.Failed("dexkit error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun findClassesUsingString(literal: String, limit: Int = 12): List<String> {
        val result = ArrayList<String>()
        val dexKit = bridge() ?: return result
        try {
            val matcher = ClassMatcher()
            matcher.usingStrings = listOf(literal)
            val data = dexKit.findClass(FindClass().matcher(matcher)).toList()
            for (classData in data) {
                if (result.size >= limit) break
                result.add(classData.name)
            }
        } catch (t: Throwable) {
            ModuleLog.w("DexKitResolver", "findClassesUsingString failed for '$literal'", t)
        }
        return result
    }

    fun findMethodsUsingString(
        literal: String,
        extraLiterals: List<String> = emptyList(),
        methodName: String? = null,
        paramCount: Int? = null,
        limit: Int = 8
    ): List<MethodRef> {
        val result = ArrayList<MethodRef>()
        val dexKit = bridge() ?: return result
        try {
            val matcher = MethodMatcher()
            matcher.usingStrings(*(arrayOf(literal) + extraLiterals.toTypedArray()))
            if (methodName != null) matcher.name = methodName
            if (paramCount != null) matcher.paramCount = paramCount
            val data = dexKit.findMethod(FindMethod().matcher(matcher)).toList()
            for (item in data) {
                if (result.size >= limit) break
                result.add(MethodRef(item.className, item.methodName, item.paramTypeNames))
            }
        } catch (t: Throwable) {
            ModuleLog.w("DexKitResolver", "findMethodsUsingString failed for '$literal'", t)
        }
        return result
    }

    fun findMethodsUsingField(
        className: String,
        fieldName: String,
        limit: Int = 4
    ): List<MethodRef> {
        val result = ArrayList<MethodRef>()
        val dexKit = bridge() ?: return result
        try {
            val matcher = MethodMatcher()
            matcher.declaredClass = className
            val fieldMatcher = FieldMatcher()
            fieldMatcher.name = fieldName
            fieldMatcher.declaredClass = className
            matcher.addUsingField(fieldMatcher, UsingType.Read)
            val data = dexKit.findMethod(FindMethod().matcher(matcher)).toList()
            for (item in data) {
                if (result.size >= limit) break
                val ref = MethodRef(item.className, item.methodName, item.paramTypeNames)
                if (result.none { it.signature() == ref.signature() }) result.add(ref)
            }
        } catch (t: Throwable) {
            ModuleLog.w("DexKitResolver", "findMethodsUsingField failed for '$className.$fieldName'", t)
        }
        if (result.isEmpty()) {
            val descriptor = "L${className.replace('.', '/')};->$fieldName:Z"
            try {
                val matcher = MethodMatcher()
                matcher.declaredClass = className
                matcher.addUsingField(descriptor, UsingType.Read)
                val data = dexKit.findMethod(FindMethod().matcher(matcher)).toList()
                for (item in data) {
                    if (result.size >= limit) break
                    val ref = MethodRef(item.className, item.methodName, item.paramTypeNames)
                    if (result.none { it.signature() == ref.signature() }) result.add(ref)
                }
            } catch (t: Throwable) {
                ModuleLog.w("DexKitResolver", "descriptor based field lookup failed for '$descriptor'", t)
            }
        }
        return result
    }

    fun findMethodsByName(
        methodName: String,
        paramCount: Int? = null,
        classPrefix: String? = null,
        limit: Int = 16
    ): List<MethodRef> {
        val result = ArrayList<MethodRef>()
        val dexKit = bridge() ?: return result
        try {
            val matcher = MethodMatcher()
            matcher.name = methodName
            if (paramCount != null) matcher.paramCount = paramCount
            val data = dexKit.findMethod(FindMethod().matcher(matcher)).toList()
            for (item in data) {
                if (result.size >= limit) break
                if (classPrefix != null && !item.className.startsWith(classPrefix)) continue
                val ref = MethodRef(item.className, item.methodName, item.paramTypeNames)
                if (result.none { it.signature() == ref.signature() }) result.add(ref)
            }
        } catch (t: Throwable) {
            ModuleLog.w("DexKitResolver", "findMethodsByName failed for '$methodName'", t)
        }
        return result
    }

    private fun resolveClass(spec: ResolveSpec.ClassSpec): ResolveStatus {
        val dexKit = bridge() ?: return ResolveStatus.Failed("dexkit unavailable: ${unavailableReason}")
        try {
            val matcher = ClassMatcher()
            matcher.className = spec.className
            val found = dexKit.findClass(FindClass().matcher(matcher)).toList()
            val first = found.firstOrNull() ?: return ResolveStatus.Failed("dexkit: class not found ${spec.className}")
            return ResolveStatus.Resolved(first.name, "dexkit")
        } catch (t: Throwable) {
            return ResolveStatus.Failed("dexkit class lookup failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun resolveMethod(spec: ResolveSpec.MethodSpec): ResolveStatus {
        val dexKit = bridge() ?: return ResolveStatus.Failed("dexkit unavailable: ${unavailableReason}")
        try {
            val matcher = ClassMatcher()
            matcher.className = spec.className
            val found = dexKit.findClass(FindClass().matcher(matcher)).toList().firstOrNull()
                ?: return ResolveStatus.Failed("dexkit: class not found ${spec.className}")
            val methods = found.methods.toList().filter { it.name == spec.methodName }
            val signature = if (methods.isEmpty()) {
                return ResolveStatus.Failed("dexkit: method ${spec.methodName} not found in ${spec.className}")
            } else {
                "${spec.className}.${spec.methodName}(${methods.size} overload(s))"
            }
            return ResolveStatus.Resolved(signature, "dexkit")
        } catch (t: Throwable) {
            return ResolveStatus.Failed("dexkit method lookup failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun resolveField(spec: ResolveSpec.FieldSpec): ResolveStatus {
        val dexKit = bridge() ?: return ResolveStatus.Failed("dexkit unavailable: ${unavailableReason}")
        try {
            val matcher = ClassMatcher()
            matcher.className = spec.className
            val found = dexKit.findClass(FindClass().matcher(matcher)).toList().firstOrNull()
                ?: return ResolveStatus.Failed("dexkit: class not found ${spec.className}")
            val field = found.fields.toList().firstOrNull { it.name == spec.fieldName }
                ?: return ResolveStatus.Failed("dexkit: field ${spec.fieldName} not found in ${spec.className}")
            return ResolveStatus.Resolved("${spec.className}.${spec.fieldName}", "dexkit")
        } catch (t: Throwable) {
            return ResolveStatus.Failed("dexkit field lookup failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun resolveMethodByLiteral(spec: ResolveSpec.MethodByLiteralSpec): ResolveStatus {
        val refs = findMethodsUsingString(
            literal = spec.literal,
            extraLiterals = spec.extraLiterals,
            methodName = spec.methodName,
            paramCount = spec.paramCount
        )
        if (refs.isEmpty()) {
            return ResolveStatus.Failed("dexkit: no method uses literal '${spec.literal}'")
        }
        return ResolveStatus.Resolved(refs.joinToString(" | ") { it.signature() }, "dexkit")
    }

    private fun resolveString(spec: ResolveSpec.StringSpec): ResolveStatus {
        val owners = findClassesUsingString(spec.literal)
        if (owners.isEmpty()) return ResolveStatus.Failed("dexkit: literal not found: ${spec.literal}")
        return ResolveStatus.Resolved("literal '${spec.literal}' used by ${owners.size} class(es): ${owners.take(3).joinToString(", ")}", "dexkit")
    }

    fun findPremiumFlagReaders(
        classNames: List<String>,
        jsonKey: String,
        limit: Int = 12
    ): List<MethodRef> {
        val result = ArrayList<MethodRef>()
        val dexKit = bridge() ?: return result
        for (className in classNames) {
            try {
                val matcher = ClassMatcher()
                matcher.className = className
                val classData = dexKit.findClass(FindClass().matcher(matcher)).toList().firstOrNull()
                    ?: continue
                for (field in classData.fields) {
                    val key = annotationStringValue(field.annotations) ?: continue
                    if (key != jsonKey) continue
                    for (reader in field.readers) {
                        if (result.size >= limit) return result
                        if (reader.paramTypeNames.size != 0) continue
                        if (reader.returnTypeName != "boolean") continue
                        val ref = MethodRef(reader.className, reader.methodName, reader.paramTypeNames)
                        if (result.none { it.signature() == ref.signature() }) result.add(ref)
                    }
                }
            } catch (t: Throwable) {
                ModuleLog.w("DexKitResolver", "premium flag lookup failed for '$className'", t)
            }
        }
        return result
    }

    private fun annotationStringValue(annotations: List<AnnotationData>): String? {
        for (annotation in annotations) {
            for (element in annotation.elements) {
                try {
                    val value = element.value
                    if (value.type == AnnotationEncodeValueType.StringValue) {
                        val text = value.stringValue()
                        if (!text.isNullOrEmpty()) return text
                    }
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    private fun bridge(): DexKitBridge? {
        unavailableReason?.let { return null }
        bridge?.let { return it }
        return try {
            val context = Host.application
            if (context != null && !NativeLibLoader.ensureDexKit(context)) {
                unavailableReason = "native library unavailable"
                return null
            }
            DexKitBridge.create(apkPath).also { bridge = it }
        } catch (t: Throwable) {
            unavailableReason = "${t.javaClass.simpleName}: ${t.message}"
            ModuleLog.w("DexKitResolver", "bridge unavailable, fallback to runtime resolver", t)
            null
        }
    }
}
