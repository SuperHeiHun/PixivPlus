package com.heihun.pixivplus.core.backup

import com.heihun.pixivplus.core.config.ConfigStore
import com.heihun.pixivplus.core.net.ProxyHttp
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 通过 WebDAV 上传 / 下载备份文件。
 *
 * 备份固定放在服务器根目录下的 /PixivPlus/，文件名为
 * pixiv-backup-yy-MM-dd_HH-mm.json；网络请求沿用模块的代理设置。
 */
object WebDavBackup {

    const val FOLDER_NAME = "PixivPlus"
    private const val MAX_DOWNLOAD_CHARS = 32 * 1024 * 1024
    private const val MAX_PROPFIND_CHARS = 4 * 1024 * 1024
    private const val PROPFIND_BODY =
        """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:getlastmodified/>
    <d:getcontentlength/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>"""

    data class RemoteBackup(
        val url: URL,
        val name: String,
        val lastModified: Long,
        val size: Long
    )

    data class WebDavResult(
        val ok: Boolean,
        val message: String,
        val backup: RemoteBackup? = null,
        val body: String? = null
    )

    fun normalizeHost(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        for (prefix in listOf("https://", "http://")) {
            if (value.startsWith(prefix, ignoreCase = true)) {
                value = value.substring(prefix.length)
                break
            }
        }
        value = value.trimEnd('/')
        val slash = value.indexOf('/')
        if (slash >= 0) value = value.substring(0, slash)
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null
        return value
    }

    fun folderPath(): String = "/$FOLDER_NAME/"

    fun folderUrl(host: String, port: Int, https: Boolean): URL? {
        val normalized = normalizeHost(host) ?: return null
        if (port !in 1..65535) return null
        return try {
            URL("${if (https) "https" else "http"}://$normalized:$port${folderPath()}")
        } catch (_: Throwable) {
            null
        }
    }

    fun fileUrl(host: String, port: Int, https: Boolean, fileName: String): URL? {
        val folder = folderUrl(host, port, https) ?: return null
        return try {
            URL(folder, fileName)
        } catch (_: Throwable) {
            null
        }
    }

    fun upload(
        store: ConfigStore,
        host: String,
        port: Int,
        https: Boolean,
        username: String,
        password: String,
        fileName: String,
        data: ByteArray
    ): WebDavResult {
        val folder = folderUrl(host, port, https)
            ?: return WebDavResult(false, "WebDAV 地址或端口无效")
        ensureFolder(store, folder, username, password).takeIf { !it.ok }?.let { return it }

        val file = try {
            URL(folder, fileName)
        } catch (_: Throwable) {
            return WebDavResult(false, "WebDAV 备份文件名无效")
        }
        val headers = authHeaders(username, password).apply {
            put("Accept", "application/json")
            put("Content-Type", "application/json; charset=utf-8")
        }
        val response = ProxyHttp.request(
            store = store,
            url = file,
            method = "PUT",
            headers = headers,
            body = data,
            maxBodyChars = 4096,
            connectTimeoutMs = 15000,
            readTimeoutMs = 30000
        ) ?: return WebDavResult(false, "上传失败：${failureReason()}")

        return when {
            response.code in 200..299 -> WebDavResult(
                true,
                "已上传到 /$FOLDER_NAME/$fileName",
                backup = RemoteBackup(file, fileName, System.currentTimeMillis(), data.size.toLong())
            )
            response.code == 401 || response.code == 403 ->
                WebDavResult(false, "WebDAV 认证失败（HTTP ${response.code}）")
            response.code == 507 -> WebDavResult(false, "WebDAV 服务器空间不足（HTTP 507）")
            else -> WebDavResult(false, "上传失败：HTTP ${response.code}")
        }
    }

    /** 查找 /PixivPlus/ 中最近一次备份。 */
    fun latest(
        store: ConfigStore,
        host: String,
        port: Int,
        https: Boolean,
        username: String,
        password: String
    ): WebDavResult {
        val folder = folderUrl(host, port, https)
            ?: return WebDavResult(false, "WebDAV 地址或端口无效")
        val headers = authHeaders(username, password).apply {
            put("Depth", "1")
            put("Content-Type", "application/xml; charset=utf-8")
        }
        val response = ProxyHttp.request(
            store = store,
            url = folder,
            method = "PROPFIND",
            headers = headers,
            body = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
            maxBodyChars = MAX_PROPFIND_CHARS,
            connectTimeoutMs = 15000,
            readTimeoutMs = 30000
        ) ?: return WebDavResult(false, "无法连接 WebDAV：${failureReason()}")

        if (response.code == 401 || response.code == 403) {
            return WebDavResult(false, "WebDAV 认证失败（HTTP ${response.code}）")
        }
        if (response.code == 404) {
            return WebDavResult(false, "WebDAV 目录 /$FOLDER_NAME/ 不存在，请先上传一次备份")
        }
        if (response.code == 405) {
            return WebDavResult(false, "WebDAV 服务器不支持 PROPFIND（HTTP 405）")
        }
        if (response.code != 200 && response.code != 207) {
            return WebDavResult(false, "读取 WebDAV 目录失败：HTTP ${response.code}")
        }
        val file = parseLatest(response.body, folder)
            ?: return WebDavResult(false, "WebDAV 目录中没有 pixiv-backup-*.json 备份")
        return WebDavResult(true, "找到备份 ${file.name}", backup = file)
    }

    fun download(
        store: ConfigStore,
        remote: RemoteBackup,
        username: String,
        password: String
    ): WebDavResult {
        val headers = authHeaders(username, password).apply {
            put("Accept", "application/json")
        }
        val response = ProxyHttp.get(
            store = store,
            url = remote.url,
            headers = headers,
            maxBodyChars = MAX_DOWNLOAD_CHARS,
            connectTimeoutMs = 15000,
            readTimeoutMs = 60000
        ) ?: return WebDavResult(false, "下载失败：${failureReason()}")
        return when {
            response.code == 200 -> WebDavResult(
                true,
                "已下载 ${remote.name}",
                backup = remote,
                body = response.body
            )
            response.code == 401 || response.code == 403 ->
                WebDavResult(false, "WebDAV 认证失败（HTTP ${response.code}）")
            response.code == 404 -> WebDavResult(false, "WebDAV 备份文件不存在（HTTP 404）")
            else -> WebDavResult(false, "下载失败：HTTP ${response.code}")
        }
    }

    /** 解析 PROPFIND 响应，选择最后修改时间最新的备份文件。 */
    fun parseLatest(xml: String, folderUrl: URL): RemoteBackup? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val responses = document.getElementsByTagNameNS("*", "response")
            var best: RemoteBackup? = null
            for (index in 0 until responses.length) {
                val element = responses.item(index) as? Element ?: continue
                val href = firstText(element, "href") ?: continue
                val rawName = href.trimEnd('/').substringAfterLast('/')
                if (rawName.isEmpty()) continue
                val name = decodeName(rawName)
                if (!BackupManager.isBackupName(name)) continue
                val url = try {
                    URL(folderUrl, href)
                } catch (_: Throwable) {
                    continue
                }
                val modified = parseHttpDate(firstText(element, "getlastmodified"))
                    .takeIf { it > 0 } ?: timestampFromName(name)
                val size = firstText(element, "getcontentlength")?.toLongOrNull() ?: 0L
                val candidate = RemoteBackup(url, name, modified, size)
                if (best == null || candidate.lastModified > best.lastModified ||
                    (candidate.lastModified == best.lastModified && candidate.name > best.name)
                ) {
                    best = candidate
                }
            }
            best
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureFolder(
        store: ConfigStore,
        folder: URL,
        username: String,
        password: String
    ): WebDavResult {
        val headers = authHeaders(username, password).apply {
            put("Content-Length", "0")
        }
        val response = ProxyHttp.request(
            store = store,
            url = folder,
            method = "MKCOL",
            headers = headers,
            body = null,
            maxBodyChars = 4096,
            connectTimeoutMs = 15000,
            readTimeoutMs = 30000
        ) ?: return WebDavResult(false, "无法连接 WebDAV：${failureReason()}")
        return when {
            response.code in 200..299 -> WebDavResult(true, "备份目录已就绪")
            response.code == 405 -> WebDavResult(true, "备份目录已存在")
            response.code == 401 || response.code == 403 ->
                WebDavResult(false, "WebDAV 认证失败（HTTP ${response.code}）")
            response.code == 409 ->
                WebDavResult(false, "WebDAV 服务器路径无效或父目录不存在（HTTP 409）")
            else -> WebDavResult(false, "创建 /$FOLDER_NAME/ 目录失败：HTTP ${response.code}")
        }
    }

    private fun firstText(element: Element, localName: String): String? {
        val nodes = element.getElementsByTagNameNS("*", localName)
        if (nodes.length == 0) return null
        val value = nodes.item(0)?.textContent?.trim().orEmpty()
        return value.ifEmpty { null }
    }

    private fun decodeName(raw: String): String {
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Throwable) {
            raw
        }
    }

    private fun parseHttpDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val patterns = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )
        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                return format.parse(value)?.time ?: 0L
            } catch (_: Throwable) {
            }
        }
        return 0L
    }

    private fun timestampFromName(name: String): Long {
        return try {
            val stamp = name.removePrefix(BackupManager.FILE_PREFIX)
                .removeSuffix(BackupManager.FILE_SUFFIX)
            val format = SimpleDateFormat("yy-MM-dd_HH-mm", Locale.US).apply {
                isLenient = false
            }
            format.parse(stamp)?.time ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun authHeaders(username: String, password: String): LinkedHashMap<String, String> {
        val headers = linkedMapOf<String, String>()
        if (username.isNotEmpty() || password.isNotEmpty()) {
            val raw = "$username:$password".toByteArray(Charsets.UTF_8)
            headers["Authorization"] = "Basic " + Base64.getEncoder().encodeToString(raw)
        }
        return headers
    }

    private fun failureReason(): String {
        return ProxyHttp.lastFailureReason() ?: "网络错误"
    }
}
