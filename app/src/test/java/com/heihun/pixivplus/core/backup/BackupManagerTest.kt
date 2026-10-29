package com.heihun.pixivplus.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManagerTest {

    @Test
    fun `empty runtime produces valid backup`() {
        val json = BackupManager.createJson()
        val root = org.json.JSONObject(json)
        assertEquals(BackupManager.FORMAT, root.getString("format"))
        assertEquals(BackupManager.VERSION, root.getInt("version"))
        assertTrue(root.has("config"))
        assertFalse(root.has("history"))
        assertFalse(root.has("author_profile"))

        val info = BackupManager.info(json)
        assertEquals(0, info?.configCount)
        assertEquals(0, info?.historyCount)
        assertEquals(0, info?.authorCount)
    }

    @Test
    fun `info parses backup counts`() {
        val root = org.json.JSONObject()
        root.put("format", BackupManager.FORMAT)
        root.put("version", BackupManager.VERSION)
        root.put("created_at", 1700000000000L)
        root.put("module_version", "1.0.2 (3)")
        root.put("config", org.json.JSONObject().put("filter_tags", "原创"))
        root.put("history", org.json.JSONArray().put(org.json.JSONObject().put("type", "illust")))
        root.put(
            "author_profile",
            org.json.JSONArray()
                .put(org.json.JSONObject().put("author_id", 1))
                .put(org.json.JSONObject().put("author_id", 2))
        )

        val info = BackupManager.info(root.toString())
        assertEquals(1700000000000L, info?.createdAt)
        assertEquals("1.0.2 (3)", info?.moduleVersion)
        assertEquals(1, info?.configCount)
        assertEquals(1, info?.historyCount)
        assertEquals(2, info?.authorCount)
    }

    @Test
    fun `info rejects foreign file`() {
        assertEquals(null, BackupManager.info("{}"))
        assertEquals(null, BackupManager.info("not json"))
    }

    @Test
    fun `backup file name uses fixed timestamp format`() {
        val now = 1790000000000L
        val stamp = SimpleDateFormat("yy-MM-dd_HH-mm", Locale.US).format(Date(now))
        val name = BackupManager.fileName(now)
        assertEquals("pixiv-backup-$stamp.json", name)
        assertTrue(BackupManager.isBackupName(name))
        assertTrue(BackupManager.isBackupName("pixiv-backup-26-10-29_14-05.json"))
        assertFalse(BackupManager.isBackupName("pixivplus-backup.json"))
        assertFalse(BackupManager.isBackupName("pixiv-backup-26-10-29_14-05.txt"))
        assertFalse(BackupManager.isBackupName(null))
    }

    @Test
    fun `restore rejects foreign and unsupported versions`() {
        assertFalse(BackupManager.restore("{}").ok)
        assertFalse(
            BackupManager.restore(
                org.json.JSONObject()
                    .put("format", BackupManager.FORMAT)
                    .put("version", BackupManager.VERSION + 1)
                    .toString()
            ).ok
        )
        assertFalse(
            BackupManager.restore(
                org.json.JSONObject()
                    .put("format", BackupManager.FORMAT)
                    .put("version", 0)
                    .toString()
            ).ok
        )
    }
}
