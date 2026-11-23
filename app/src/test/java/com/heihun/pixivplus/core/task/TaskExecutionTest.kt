package com.heihun.pixivplus.core.task

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TaskExecutionTest {
    @Test fun `progress counters do not depend on translated wording`() {
        var progress: TaskExecution.Progress? = null
        TaskExecution.withContext(TaskExecution.Context(CancellationToken()) { progress = it }) {
            TaskExecution.progress("全 3 件中 2 件", 2, 3)
        }
        assertEquals(TaskExecution.Progress("全 3 件中 2 件", 2, 3), progress)
    }
    @Test fun `history retains work link and download replay data`() {
        val plan = com.heihun.pixivplus.core.media.DownloadPlan(123, "作品", "content://folder/tree/test",
            com.heihun.pixivplus.core.media.DownloadFormat.IMAGES, listOf(1, 3)).encode()
        val record = TaskRecord("id", "作品", TaskKind.DOWNLOAD, TaskState.CANCELLED, workId = 123, downloadPlan = plan)
        assertEquals(record, TaskRecords.decode(TaskRecords.encode(listOf(record))).single())
    }
    @Test fun `cancel stops copying remaining file data`() {
        val token = CancellationToken()
        val output = java.io.ByteArrayOutputStream()
        val input = object : java.io.ByteArrayInputStream(ByteArray(30000)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                token.cancel()
                return count
            }
        }
        try {
            TaskExecution.withContext(TaskExecution.Context(token, {})) { TaskExecution.copy(input, output) }
            fail()
        } catch (_: TaskCancelledException) { }
        assertTrue(output.size() <= 8192)
        assertFalse(Thread.currentThread().isInterrupted)
    }
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
