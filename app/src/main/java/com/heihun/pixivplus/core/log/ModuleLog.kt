package com.heihun.pixivplus.core.log

import android.util.Log
import java.util.ArrayDeque

object ModuleLog {

    const val TAG = "PixivPlus"
    private const val CAPACITY = 2000
    private val buffer = ArrayDeque<String>(CAPACITY)

    fun d(tag: String, message: String) = append("D", tag, message, null)

    fun i(tag: String, message: String) = append("I", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = append("W", tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = append("E", tag, message, error)

    fun dump(): List<String> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    private fun append(level: String, tag: String, message: String, error: Throwable?) {
        val line = buildString {
            append(System.currentTimeMillis())
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(message)
            if (error != null) {
                append('\n')
                append(Log.getStackTraceString(error))
            }
        }
        synchronized(buffer) {
            while (buffer.size >= CAPACITY) buffer.pollFirst()
            buffer.addLast(line)
        }
        try {
            when (level) {
                "E" -> Log.e(TAG, "PLP: $tag: $message", error)
                "W" -> Log.w(TAG, "PLP: $tag: $message", error)
                else -> Log.d(TAG, "PLP: $tag: $message")
            }
        } catch (_: Throwable) {
        }
    }
}
