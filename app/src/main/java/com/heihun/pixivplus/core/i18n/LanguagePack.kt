package com.heihun.pixivplus.core.i18n

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.IllformedLocaleException
import java.util.Locale

internal enum class PackProblem { INVALID, SIZE, VERSION, LOCALE, NAME, ENTRY, PLACEHOLDER, EMPTY, BUILTIN, LIMIT }

internal class LanguagePackException(val problem: PackProblem, val key: String? = null) : IllegalArgumentException(problem.name)

internal data class LanguagePack(
    val locale: Locale,
    val name: String,
    val strings: Map<String, String>
) {
    val tag: String get() = locale.toLanguageTag()

    fun toJson(): String = JSONObject()
        .put("version", VERSION)
        .put("locale", tag)
        .put("name", name)
        .put("strings", JSONObject(strings))
        .toString()

    companion object {
        const val VERSION = 1
        const val MAX_BYTES = 256 * 1024
        private const val MAX_NAME_LENGTH = 80
        private const val MAX_STRING_LENGTH = 4096
        private val localeTag = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8}){0,8}")
        private val messageKey = Regex("[a-z][a-z0-9_]{0,31}\\.[a-z][a-z0-9_]{0,95}")

        fun read(input: InputStream, validatePlaceholders: (Map<String, String>) -> Unit = {}): LanguagePack {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - bytes.size()))
                if (count == -1) break
                bytes.write(buffer, 0, count)
                if (bytes.size() > MAX_BYTES) throw LanguagePackException(PackProblem.SIZE)
            }
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
            } catch (_: CharacterCodingException) {
                throw LanguagePackException(PackProblem.INVALID)
            }
            val root = try { StrictJson(text).parseObject() }
                catch (_: Exception) { throw LanguagePackException(PackProblem.INVALID) }
            if (root.keys != setOf("version", "locale", "name", "strings")) throw LanguagePackException(PackProblem.INVALID)
            if (root["version"] != VERSION.toLong()) throw LanguagePackException(PackProblem.VERSION)
            val locale = parseLocale(root["locale"] as? String) ?: throw LanguagePackException(PackProblem.LOCALE)
            val name = (root["name"] as? String)?.trim().orEmpty()
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH || name.any { it.isISOControl() }) {
                throw LanguagePackException(PackProblem.NAME)
            }
            @Suppress("UNCHECKED_CAST")
            val entries = root["strings"] as? Map<String, Any?> ?: throw LanguagePackException(PackProblem.INVALID)
            val strings = entries.entries.associate { (key, raw) ->
                val value = raw as? String
                if (!messageKey.matches(key) || value.isNullOrBlank() || value.length > MAX_STRING_LENGTH || '\u0000' in value) {
                    throw LanguagePackException(PackProblem.ENTRY, key.take(128))
                }
                key to value
            }
            validatePlaceholders(strings)
            return LanguagePack(locale, name, strings)
        }

        fun parseLocale(tag: String?): Locale? {
            if (tag == null || tag.length > 64 || tag.equals("system", ignoreCase = true) || !localeTag.matches(tag)) return null
            return try {
                Locale.Builder().setLanguageTag(tag).build().takeIf { it.language.isNotEmpty() && !it.hasExtensions() }
            } catch (_: IllformedLocaleException) {
                null
            }
        }

    }
}

/** Strict, duplicate-key rejecting JSON object parser; language packs use objects and scalar values only. */
private class StrictJson(private val source: String) {
    private var index = 0

    fun parseObject(): Map<String, Any?> {
        whitespace()
        val root = objectValue(0)
        whitespace()
        require(index == source.length)
        return root
    }

    private fun objectValue(depth: Int): Map<String, Any?> {
        require(depth <= 2)
        expect('{')
        whitespace()
        val result = LinkedHashMap<String, Any?>()
        if (take('}')) return result
        while (true) {
            whitespace()
            val key = stringValue()
            require(!result.containsKey(key)) { "Duplicate JSON key" }
            whitespace(); expect(':'); whitespace()
            result[key] = value(depth + 1)
            whitespace()
            if (take('}')) return result
            expect(',')
        }
    }

    private fun value(depth: Int): Any? {
        require(index < source.length)
        return when (source[index]) {
            '"' -> stringValue()
            '{' -> objectValue(depth)
            '[', '\'', '/' , '#' -> throw IllegalArgumentException("Unsupported JSON value")
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> numberValue()
            else -> throw IllegalArgumentException("Invalid JSON value")
        }
    }

    private fun stringValue(): String {
        expect('"')
        val out = StringBuilder()
        while (index < source.length) {
            val char = source[index++]
            when {
                char == '"' -> {
                    var cursor = 0
                    while (cursor < out.length) {
                        val current = out[cursor]
                        if (Character.isHighSurrogate(current)) {
                            require(cursor + 1 < out.length && Character.isLowSurrogate(out[cursor + 1]))
                            cursor += 2
                        } else {
                            require(!Character.isLowSurrogate(current))
                            cursor++
                        }
                    }
                    return out.toString()
                }
                char == '\\' -> {
                    require(index < source.length)
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(index + 4 <= source.length)
                            val code = source.substring(index, index + 4).toIntOrNull(16)
                                ?: throw IllegalArgumentException("Invalid unicode escape")
                            out.append(code.toChar()); index += 4
                        }
                        else -> throw IllegalArgumentException("Invalid escape")
                    }
                }
                char.code < 0x20 -> throw IllegalArgumentException("Unescaped control character")
                else -> out.append(char)
            }
            require(out.length <= LanguagePack.MAX_BYTES)
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun numberValue(): Long {
        val start = index
        if (take('-')) require(index < source.length)
        if (take('0')) require(index >= source.length || source[index] !in '0'..'9')
        else {
            require(index < source.length && source[index] in '1'..'9')
            while (index < source.length && source[index] in '0'..'9') index++
        }
        require(index >= source.length || source[index] !in ".eE")
        return source.substring(start, index).toLong()
    }

    private fun <T> literal(value: String, result: T): T {
        require(source.startsWith(value, index))
        index += value.length
        return result
    }

    private fun whitespace() {
        while (index < source.length && source[index] in " \t\r\n") index++
    }

    private fun expect(char: Char) { require(take(char)) }
    private fun take(char: Char): Boolean {
        if (index >= source.length || source[index] != char) return false
        index++
        return true
    }
}

internal object TextFormat {
    private val tokens = Regex("%%|%([1-9][0-9]?)\\$([sd])")

    /** 参数仅允许位置编号 + s/d，禁止任意宽度、日期及其他 Formatter 指令。 */
    fun signature(pattern: String, strict: Boolean): List<String> {
        val arguments = mutableListOf<String>()
        var end = 0
        for (match in tokens.findAll(pattern)) {
            if (strict) require(pattern.indexOf('%', end) == match.range.first)
            end = match.range.last + 1
            if (match.value == "%%") continue
            arguments.add(match.value)
        }
        if (strict) require(pattern.indexOf('%', end) == -1)
        return arguments.sorted()
    }
}
