package com.heihun.pixivplus.core.task

import com.heihun.pixivplus.core.media.SavedFile
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class TaskRecordsTest {
    @Test fun `saved documents retain URI display name and MIME after restart`() {
        val files = listOf(SavedFile("content://media/external/downloads/42", "novel.txt", "text/plain"),
            SavedFile("content://provider/document/43", "作品/image.jpg", "image/jpeg"))
        val record = TaskRecord("id", "title", TaskKind.DOWNLOAD, TaskState.SUCCEEDED, files = files)
        assertEquals(record, TaskRecords.decode(TaskRecords.encode(listOf(record))).single())
    }

    @Test fun `old history remains readable without file locations`() {
        val json = JSONArray(TaskRecords.encode(listOf(TaskRecord("id", "title", TaskKind.DOWNLOAD, TaskState.SUCCEEDED))))
        json.getJSONObject(0).remove("files")
        val record = TaskRecords.decode(json.toString()).single()
        assertEquals("title", record.title)
        assertTrue(record.files.isEmpty())
    }

    @Test fun `file opening never accepts raw paths web addresses or arbitrary MIME intents`() {
        for (uri in listOf("file:///sdcard/Download/x.txt", "https://example.com/x", "../private")) {
            assertFalse(SavedFile(uri, "name", "text/plain").isValid())
        }
        assertFalse(SavedFile("content://media/42", "name", "text/plain;evil").isValid())
    }
}
