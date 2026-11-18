package com.heihun.pixivplus.core.task

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TaskExecutionTest {
    @Test fun `cancel closes resources once including resources registered later`() {
        val token = CancellationToken()
        val count = AtomicInteger()
        token.register(Closeable { count.incrementAndGet() })
        token.cancel(); token.cancel()
        assertEquals(1, count.get())
        token.register(Closeable { count.incrementAndGet() })
        assertEquals(2, count.get())
    }
    @Test fun `cancel interrupts workers and does not leak interruption to reused threads`() {
        val token = CancellationToken()
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        var interruptedAfter = true
        val thread = Thread {
            try {
                TaskExecution.withContext(TaskExecution.Context(token, {})) {
                    ready.countDown()
                    CountDownLatch(1).await()
                }
            } catch (_: InterruptedException) { }
            finally { interruptedAfter = Thread.currentThread().isInterrupted; done.countDown() }
        }
        thread.start()
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        token.cancel()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertFalse(interruptedAfter)
    }
    @Test fun `cancelled queued task does not run work`() {
        val token = CancellationToken().apply { cancel() }
        var ran = false
        try { TaskExecution.withContext(TaskExecution.Context(token, {})) { ran = true }; fail() }
        catch (_: TaskCancelledException) { }
        assertFalse(ran)
        assertFalse(Thread.currentThread().isInterrupted)
    }
    @Test fun `download history survives roundtrip and interrupted jobs are recovered`() {
        val data = listOf(TaskRecord("a", "作者_标题", TaskKind.DOWNLOAD, TaskState.SUCCEEDED, "完成", finishedAt = 10),
            TaskRecord("b", "未完成", TaskKind.DOWNLOAD, TaskState.RUNNING))
        val decoded = TaskRecords.decode(TaskRecords.encode(data))
        assertEquals(data[0], decoded[0])
        assertEquals(TaskState.INTERRUPTED, decoded[1].state)
        assertEquals(TaskRecords.HISTORY_LIMIT, TaskRecords.decode(TaskRecords.encode(List(200) { data[0] })).size)
        assertTrue(TaskRecords.decode("broken").isEmpty())
    }
}
