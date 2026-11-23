package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.i18n.NetworkText as N
import com.heihun.pixivplus.core.task.TaskExecution
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

/** 有校验的流式断点下载；状态文件只用于模块自有下载目录。 */
internal object FileDownload {
    data class Response(val code: Int, val location: String? = null, val complete: Boolean = false)
    private fun stateFile(file: File) = File(file.parentFile, "${file.name}.resume.json")
    private fun state(file: File): JSONObject? = try { JSONObject(stateFile(file).readText()) } catch (_: Exception) { null }
    fun prepare(file: File, url: String) {
        if (state(file)?.optString("url") != url) {
            if (file.exists()) file.outputStream().close()
            stateFile(file).delete()
        }
    }
    fun complete(file: File, url: String): Boolean {
        val data = state(file) ?: return false
        return data.optString("url") == url && data.optBoolean("complete") && file.length() > 0 && file.length() == data.optLong("total", -1)
    }
    fun headers(file: File): Map<String, String> = buildMap {
        if (file.length() > 0) {
            put("Range", "bytes=${file.length()}-")
            val data = state(file)
            val validator = data?.optString("etag")?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
                ?: data?.optString("modified")?.takeIf { it.isNotBlank() }
            if (validator != null) put("If-Range", validator)
        }
    }
    fun read(input: InputStream, file: File, url: String, maxBytes: Long, progress: (Long, Long) -> Unit): Response {
        val status = line(input) ?: throw IOException(N.EMPTY_RESPONSE())
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException(N.BAD_STATUS_LINE())
        val headers = LinkedHashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val text = line(input) ?: throw IOException(N.BAD_HEADERS())
            headerBytes += text.length
            if (headerBytes > 65536) throw IOException(N.HEADER_LIMIT())
            if (text.isEmpty()) break
            if (':' in text) headers[text.substringBefore(':').trim().lowercase()] = text.substringAfter(':').trim()
        }
        if (code in 300..399 && code != 304) return Response(code, headers["location"])
        val previous = file.length()
        if (code == 416) {
            val total = Regex("bytes \\*/(\\d+)").matchEntire(headers["content-range"].orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            val saved = state(file)
            if (total != null && total > 0 && total == previous && saved?.optLong("total") == total) {
                saved.put("complete", true); stateFile(file).writeText(saved.toString()); return Response(code, complete = true)
            }
            throw IOException(N.CHECKPOINT_EXPIRED())
        }
        if (code != 200 && code != 206) throw IOException(N.DOWNLOAD_HTTP(code))
        if (headers["content-encoding"]?.let { it != "identity" } == true) throw IOException(N.UNSUPPORTED_ENCODING())
        val length = headers["content-length"]?.toLongOrNull()
        var total = length ?: -1L
        var expectedBody = length
        val append = code == 206
        if (append) {
            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(headers["content-range"].orEmpty())
                ?: throw IOException(N.MISSING_RANGE())
            val start = range.groupValues[1].toLong()
            val end = range.groupValues[2].toLong()
            total = range.groupValues[3].toLong()
            if (start != previous || end < start || end >= total) throw IOException(N.RANGE_MISMATCH())
            expectedBody = end - start + 1
            if (length != null && length != expectedBody) throw IOException(N.RANGE_LENGTH_MISMATCH())
            val old = state(file)
            if (old?.optLong("total", -1)?.let { it > 0 && it != total } == true) throw IOException(N.FILE_CHANGED())
            val oldEtag = old?.optString("etag").orEmpty()
            if (oldEtag.isNotBlank() && headers["etag"] != null && oldEtag != headers["etag"]) throw IOException(N.FILE_CHANGED())
            val modified = old?.optString("modified").orEmpty()
            if (modified.isNotBlank() && headers["last-modified"] != null && modified != headers["last-modified"]) throw IOException(N.FILE_CHANGED())
        }
        if (total > maxBytes || expectedBody?.let { it < 0 || it > maxBytes } == true) throw IOException(N.FILE_LIMIT())
        val base = if (append) previous else 0L // 服务器忽略 Range 返回 200 时必须从头写，不能追加。
        val metadata = JSONObject().put("url", url).put("total", total).put("complete", false)
            .put("etag", headers["etag"] ?: if (append) state(file)?.optString("etag").orEmpty() else "")
            .put("modified", headers["last-modified"] ?: if (append) state(file)?.optString("modified").orEmpty() else "")
        stateFile(file).writeText(metadata.toString())
        var received = 0L
        var lastProgress = 0L
        java.io.FileOutputStream(file, append).use { output ->
            val buffer = ByteArray(8192)
            fun transfer(count: Long?) {
                var remaining = count
                while (remaining == null || remaining > 0) {
                    TaskExecution.check()
                    val size = if (remaining == null) buffer.size else minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, size)
                    if (read < 0) {
                        if (remaining != null && remaining > 0) throw IOException(N.INCOMPLETE_DOWNLOAD())
                        break
                    }
                    if (base + received + read > maxBytes) throw IOException(N.FILE_LIMIT())
                    output.write(buffer, 0, read)
                    received += read
                    if (remaining != null) remaining -= read
                    val now = System.nanoTime()
                    if (now - lastProgress >= 500_000_000) { lastProgress = now; progress(base + received, total) }
                }
            }
            if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
                while (true) {
                    val size = line(input)?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: throw IOException(N.BAD_CHUNK_LENGTH())
                    if (size < 0) throw IOException(N.BAD_CHUNK_LENGTH())
                    if (size == 0L) { while (!line(input).isNullOrEmpty()) { }; break }
                    transfer(size)
                    if (line(input) != "") throw IOException(N.BAD_CHUNK_END())
                }
            } else transfer(expectedBody)
            if (expectedBody != null && received != expectedBody) throw IOException(N.INCOMPLETE_DOWNLOAD())
            output.flush()
        }
        TaskExecution.check()
        if (total < 0) total = file.length()
        if (file.length() <= 0 || file.length() > total) throw IOException(N.BAD_FILE_LENGTH())
        val complete = file.length() == total
        metadata.put("total", total).put("complete", complete)
        stateFile(file).writeText(metadata.toString())
        progress(file.length(), total)
        return Response(code, complete = complete)
    }
    private fun line(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (byte == 10) return out.toString("ISO-8859-1")
            if (byte != 13) out.write(byte)
            if (out.size() > 8192) throw IOException(N.LINE_LIMIT())
        }
    }
}
