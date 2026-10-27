package com.heihun.pixivplus.adapt

sealed class ResolveSpec {

    abstract val id: String
    abstract val description: String

    data class ClassSpec(
        override val id: String,
        override val description: String,
        val className: String
    ) : ResolveSpec()

    data class MethodSpec(
        override val id: String,
        override val description: String,
        val className: String,
        val methodName: String,
        val parameterTypes: List<String>
    ) : ResolveSpec()

    data class FieldSpec(
        override val id: String,
        override val description: String,
        val className: String,
        val fieldName: String
    ) : ResolveSpec()

    data class StringSpec(
        override val id: String,
        override val description: String,
        val literal: String
    ) : ResolveSpec()

    data class MethodByLiteralSpec(
        override val id: String,
        override val description: String,
        val literal: String,
        val extraLiterals: List<String> = emptyList(),
        val methodName: String? = null,
        val paramCount: Int? = null
    ) : ResolveSpec()
}

sealed class ResolveStatus {

    abstract val signature: String
    abstract val via: String

    data class Resolved(override val signature: String, override val via: String) : ResolveStatus()

    data class Failed(val reason: String) : ResolveStatus() {
        override val signature: String = "-"
        override val via: String = "failed"
    }
}
