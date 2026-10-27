package com.heihun.pixivplus.core.bus

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object EventBus {

    class Token internal constructor(private val handler: (Any) -> Unit) {
        fun close() {
            handlers.remove(handler)
        }
    }

    private val handlers = CopyOnWriteArrayList<(Any) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun post(event: Any) {
        for (handler in handlers) {
            try {
                mainHandler.post { handler(event) }
            } catch (_: Throwable) {
            }
        }
    }

    fun subscribe(handler: (Any) -> Unit): Token {
        handlers.add(handler)
        return Token(handler)
    }
}
