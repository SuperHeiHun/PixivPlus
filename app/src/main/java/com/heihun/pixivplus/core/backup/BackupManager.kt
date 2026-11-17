package com.heihun.pixivplus.core.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import com.heihun.pixivplus.BuildConfig
import com.heihun.pixivplus.core.Runtime
import com.heihun.pixivplus.core.config.ConfigKeys
import com.heihun.pixivplus.core.db.LocalDatabase
import com.heihun.pixivplus.core.log.ModuleLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 模块数据备份：配置 + 历史记录 + 作者资料。
 *
 * 备份为 UTF-8 JSON 文件；密码类与设备相关配置不写入备份，恢复时保留本机现有值。
 * 本地备份通过 SAF 写入用户选择的文件夹。
 */
object BackupManager {

    private const val TAG = "Backup"
    const val FORMAT = "pixivplus-backup"
    const val VERSION = 1
    const val ENCRYPTED_FORMAT = "pixivplus-encrypted-backup"
    const val ENCRYPT_VERSION = 1
    const val FILE_PREFIX = "pixiv-backup-"
    const val FILE_SUFFIX = ".json"
    private const val FILE_TIME_PATTERN = "yy-MM-dd_HH-mm"
    private const val MIME_JSON = "application/json"
    private const val KDF_ITERATIONS = 120_000
    private const val KEY_BITS = 256

    /** 屏蔽规则相关的配置键（受「屏蔽规则」备份范围控制）。 */
    private val FILTER_CONFIG_KEYS = setOf(
        ConfigKeys.FILTER_KEYWORDS.name,
        ConfigKeys.FILTER_TAGS.name,
        ConfigKeys.FILTER_AUTHORS.name,
        ConfigKeys.FILTER_CASE_SENSITIVE.name
    )

    data class BackupInfo(
        val createdAt: Long,
        val moduleVersion: String,
        val configCount: Int,
        val historyCount: Int,
        val authorCount: Int
    )

    data class LocalBackup(
        val uri: Uri,
        val name: String,
        val size: Long,
        val modifiedAt: Long
    )

    data class RestoreResult(val ok: Boolean, val message: String)

    /** 统一备份文件名：pixiv-backup-yy-MM-dd_HH-mm.json。 */
    fun fileName(now: Long = System.currentTimeMillis()): String {
        return FILE_PREFIX + SimpleDateFormat(FILE_TIME_PATTERN, Locale.US).format(Date(now)) + FILE_SUFFIX
    }

    fun isBackupName(name: String?): Boolean {
        return name != null &&
            name.startsWith(FILE_PREFIX, ignoreCase = true) &&
            name.endsWith(FILE_SUFFIX, ignoreCase = true)
    }

    // ------------------------------------------------------------------
    // 备份内容（范围 + 加密）
    // ------------------------------------------------------------------

    /** 生成本次备份内容：明文 JSON；设置了备份密码时返回加密信封（创建 / 上传共用）。 */
    fun createPayload(): String {
        val password = Runtime.configOrNull()?.get(ConfigKeys.BACKUP_PASSWORD).orEmpty()
        val json = createJson()
        if (password.isBlank()) return json
        return encrypt(json, password.toCharArray())
    }

    /** 判断备份内容是否为加密信封。 */
    fun isEncryptedJson(json: String): Boolean {
        return try {
            val trimmed = json.trimStart()
            trimmed.startsWith("{") && JSONObject(trimmed).optString("format") == ENCRYPTED_FORMAT
        } catch (_: Throwable) {
            false
        }
    }

    /** 加密：随机 salt/iv，AES-256-GCM，密钥由 PBKDF2WithHmacSHA256 派生；输出信封 JSON。 */
    fun encrypt(json: String, password: CharArray): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt))
        val iv = cipher.iv
        val data = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val envelope = JSONObject()
        envelope.put("format", ENCRYPTED_FORMAT)
        envelope.put("version", ENCRYPT_VERSION)
        envelope.put("kdf", "pbkdf2-sha256")
        envelope.put("iterations", KDF_ITERATIONS)
        envelope.put("salt", Base64.getEncoder().encodeToString(salt))
        envelope.put("iv", Base64.getEncoder().encodeToString(iv))
        envelope.put("data", Base64.getEncoder().encodeToString(data))
        return envelope.toString()
    }

    /** 解密加密信封；密码错误（GCM 校验失败）或内容损坏时抛异常。 */
    fun decrypt(envelopeJson: String, password: CharArray): String {
        val root = JSONObject(envelopeJson)
        if (root.optString("format") != ENCRYPTED_FORMAT) {
            throw IllegalArgumentException("not an encrypted backup")
        }
        val iterations = root.optInt("iterations", KDF_ITERATIONS).coerceIn(10000, 500000)
        val salt = Base64.getDecoder().decode(root.optString("salt"))
        val iv = Base64.getDecoder().decode(root.optString("iv"))
        val data = Base64.getDecoder().decode(root.optString("data"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = KDF_ITERATIONS): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    fun createJson(): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("created_at", System.currentTimeMillis())
        root.put("module_version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        root.put("database_version", LocalDatabase.DATABASE_VERSION)

        // 备份内容范围：设置项 / 屏蔽规则 / 历史记录三类可分别排除；未包含的部分恢复时保留本机现状。
        val config = Runtime.configOrNull()
        val includeSettings = config?.get(ConfigKeys.BACKUP_INCLUDE_SETTINGS) ?: true
        val includeFilters = config?.get(ConfigKeys.BACKUP_INCLUDE_FILTERS) ?: true
        val includeHistory = config?.get(ConfigKeys.BACKUP_INCLUDE_HISTORY) ?: true

        val configJson = JSONObject()
        config?.snapshotStrings()?.forEach { (name, value) ->
            if (name in ConfigKeys.LOCAL_ONLY_NAMES) return@forEach
            if (name in FILTER_CONFIG_KEYS) {
                if (includeFilters) configJson.put(name, value)
            } else if (includeSettings) {
                configJson.put(name, value)
            }
        }
        root.put("config", configJson)

        val database = Runtime.databaseOrNull()
        if (includeHistory) {
            dumpTable(database, LocalDatabase.HISTORY_TABLE)?.let { root.put("history", it) }
            dumpTable(database, LocalDatabase.AUTHOR_PROFILE_TABLE)?.let {
                root.put("author_profile", it)
            }
        }
        return root.toString()
    }

    fun info(json: String): BackupInfo? {
        return try {
            val root = JSONObject(json)
            if (root.optString("format") != FORMAT) return null
            BackupInfo(
                createdAt = root.optLong("created_at", 0L),
                moduleVersion = root.optString("module_version", "未知"),
                configCount = root.optJSONObject("config")?.length() ?: 0,
                historyCount = root.optJSONArray("history")?.length() ?: 0,
                authorCount = root.optJSONArray("author_profile")?.length() ?: 0
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** 读取所选文件夹的显示名；授权失效或查询失败时返回 null。 */
    fun folderName(context: Context, treeUri: Uri): String? {
        return try {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "read folder name failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 在用户选择的文件夹中创建备份；同名文件会被覆盖。 */
    fun createLocal(context: Context, treeUri: Uri): LocalBackup? {
        return try {
            val resolver = context.contentResolver
            val name = fileName()
            val existing = listLocal(context, treeUri).firstOrNull { it.name == name }
            val documentUri = existing?.uri ?: run {
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                DocumentsContract.createDocument(resolver, parentUri, MIME_JSON, name)
            } ?: return null

            val data = createPayload().toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(documentUri, "wt")?.use { output ->
                output.write(data)
                output.flush()
            } ?: return null

            queryDocument(resolver, documentUri, name, data.size.toLong())
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "local backup failed", t)
            null
        }
    }

    /** 列出所选文件夹中的备份，最近修改的排在前面。 */
    fun listLocal(context: Context, treeUri: Uri): List<LocalBackup> {
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val backups = ArrayList<LocalBackup>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (!isBackupName(name)) continue
                    val documentId = cursor.getString(0) ?: continue
                    backups += LocalBackup(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        name = name,
                        size = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                        modifiedAt = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    )
                }
            }
            backups.sortedWith(
                compareByDescending<LocalBackup> { it.modifiedAt }.thenByDescending { it.name }
            )
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "list local backups failed: ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
    }

    fun readLocal(context: Context, documentUri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(documentUri)?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "read local backup failed", t)
            null
        }
    }

    fun restore(json: String): RestoreResult {
        val root = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return RestoreResult(false, "备份文件不是有效的 JSON")
        }
        if (root.optString("format") != FORMAT) {
            return RestoreResult(false, "不是 PixivPlus 备份文件")
        }
        val version = root.optInt("version", 0)
        if (version <= 0 || version > VERSION) {
            return RestoreResult(false, "不支持的备份版本：$version")
        }
        val database = Runtime.databaseOrNull()
            ?: return RestoreResult(false, "数据库尚未初始化")
        val config = Runtime.configOrNull()
            ?: return RestoreResult(false, "配置尚未初始化")

        val counts = try {
            restoreDatabase(database, root)
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "restore database failed", t)
            return RestoreResult(false, "恢复数据库失败：${t.message ?: t.javaClass.simpleName}")
        }

        val configObject = root.optJSONObject("config") ?: JSONObject()
        val configCount = config.restoreStrings(jsonStringMap(configObject))
        ModuleLog.i(
            TAG,
            "restore finished: config=$configCount history=${counts.first} authors=${counts.second}"
        )
        return RestoreResult(
            true,
            "恢复完成：配置 $configCount 项、历史 ${counts.first} 条、作者资料 ${counts.second} 条"
        )
    }

    private fun queryDocument(
        resolver: android.content.ContentResolver,
        documentUri: Uri,
        name: String,
        fallbackSize: Long
    ): LocalBackup {
        return try {
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            resolver.query(documentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    LocalBackup(
                        uri = documentUri,
                        name = name,
                        size = if (cursor.isNull(0)) fallbackSize else cursor.getLong(0),
                        modifiedAt = if (cursor.isNull(1)) System.currentTimeMillis() else cursor.getLong(1)
                    )
                } else {
                    LocalBackup(documentUri, name, fallbackSize, System.currentTimeMillis())
                }
            } ?: LocalBackup(documentUri, name, fallbackSize, System.currentTimeMillis())
        } catch (_: Throwable) {
            LocalBackup(documentUri, name, fallbackSize, System.currentTimeMillis())
        }
    }

    private fun dumpTable(database: LocalDatabase?, table: String): JSONArray? {
        if (database == null) return null
        val array = JSONArray()
        return try {
            database.writable().rawQuery("SELECT * FROM $table", null).use { cursor ->
                val names = cursor.columnNames
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    for (index in names.indices) {
                        val name = names[index]
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                            Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(index))
                            Cursor.FIELD_TYPE_BLOB -> row.put(
                                name,
                                Base64.getEncoder().encodeToString(cursor.getBlob(index))
                            )
                            else -> row.put(name, cursor.getString(index))
                        }
                    }
                    array.put(row)
                }
            }
            array
        } catch (t: Throwable) {
            ModuleLog.e(TAG, "dump $table failed", t)
            null
        }
    }

    private fun restoreDatabase(database: LocalDatabase, root: JSONObject): Pair<Int, Int> {
        val db = database.writable()
        db.beginTransaction()
        try {
            var historyCount = 0
            var authorCount = 0
            if (root.has("history")) {
                historyCount = restoreTable(db, LocalDatabase.HISTORY_TABLE, root.optJSONArray("history"))
            }
            if (root.has("author_profile")) {
                authorCount = restoreTable(
                    db,
                    LocalDatabase.AUTHOR_PROFILE_TABLE,
                    root.optJSONArray("author_profile")
                )
            }
            db.setTransactionSuccessful()
            return historyCount to authorCount
        } finally {
            db.endTransaction()
        }
    }

    private fun restoreTable(db: SQLiteDatabase, table: String, array: JSONArray?): Int {
        val columns = tableColumns(db, table)
        db.delete(table, null, null)
        if (array == null) return 0
        var inserted = 0
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val values = ContentValues()
            for (key in row.keys()) {
                if (!columns.contains(key)) continue
                when (val value = row.get(key)) {
                    JSONObject.NULL -> values.putNull(key)
                    is Int -> values.put(key, value)
                    is Long -> values.put(key, value)
                    is Double -> {
                        if (value % 1.0 == 0.0) {
                            values.put(key, value.toLong())
                        } else {
                            values.put(key, value)
                        }
                    }
                    is Boolean -> values.put(key, if (value) 1 else 0)
                    is String -> values.put(key, value)
                    else -> values.put(key, value.toString())
                }
            }
            if (values.size() == 0) continue
            if (db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0) {
                inserted++
            }
        }
        return inserted
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        val columns = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(1))
            }
        }
        return columns
    }

    private fun jsonStringMap(objectValue: JSONObject): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (key in objectValue.keys()) {
            val value = objectValue.opt(key)
            if (value == null || value == JSONObject.NULL) continue
            values[key] = value.toString()
        }
        return values
    }
}
