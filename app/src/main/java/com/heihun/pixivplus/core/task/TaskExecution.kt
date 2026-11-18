package com.heihun.pixivplus.core.task

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

class TaskCancelledException : CancellationException("任务已取消")

/** 合作式取消：中断任务线程，同时关闭登记的连接，打断阻塞网络读取。 */
class CancellationToken {
    private val cancelled = AtomicBoolean(false)
    private val resources = CopyOnWriteArraySet<Closeable>()
    private val threads = CopyOnWriteArraySet<Thread>()
    val isCancelled: Boolean get() = cancelled.get()
    fun check() { if (isCancelled || Thread.currentThread().isInterrupted) throw TaskCancelledException() }
    fun register(resource: Closeable) {
        resources.add(resource)
        if (isCancelled) { resources.remove(resource); try { resource.close() } catch (_: Exception) {} }
    }
    fun unregister(resource: Closeable) { resources.remove(resource) }
    fun attach(thread: Thread) { threads.add(thread); if (isCancelled) thread.interrupt() }
    fun detach(thread: Thread) { threads.remove(thread) }
    fun cancel(closeAsync: Boolean = false) {
        if (!cancelled.compareAndSet(false, true)) return
        threads.forEach { it.interrupt() }
        val closing = resources.toList()
        resources.clear()
        val close = { closing.forEach { try { it.close() } catch (_: Exception) {} } }
        if (closeAsync) Thread({ close() }, "plp-task-cancel").apply { isDaemon = true }.start() else close()
    }
}

object TaskExecution {
    data class Context(val token: CancellationToken, val progress: (String) -> Unit)
    private val local = ThreadLocal<Context?>()
    fun current(): Context? = local.get()
    fun check() { local.get()?.token?.check() }
    fun register(resource: Closeable) { local.get()?.token?.register(resource); check() }
    fun unregister(resource: Closeable) { local.get()?.token?.unregister(resource) }
    fun progress(message: String) { check(); local.get()?.progress?.invoke(message) }
    fun <T> withContext(context: Context?, work: () -> T): T {
        if (context == null) return work()
        val previous = local.get()
        val thread = Thread.currentThread()
        local.set(context)
        context.token.attach(thread)
        try { check(); return work().also { check() } }
        finally {
            context.token.detach(thread)
            local.set(previous)
            if (context.token.isCancelled) Thread.interrupted() // 不污染复用的执行器线程。
        }
    }
    fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        check()
    }
}
