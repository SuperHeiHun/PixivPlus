package com.heihun.pixivplus.core.net

import com.heihun.pixivplus.core.log.ModuleLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 通过 HTTP CONNECT 或 SOCKS4/4a/5 代理发起的极简 HTTP(S) 客户端。
 *
 * 不使用 HttpURLConnection 的代理认证，避免修改宿主进程的全局
 * Authenticator；连接、代理认证、TLS 与 HTTP/1.1 请求都在这里完成。
 * 支持直连与任意 HTTP 方法（含 PUT / PROPFIND），满足简介兜底、
 * 代理测试与 WebDAV 备份使用。
 */
internal object TunnelHttpClient {

    private const val TAG = "TunnelHttp"
    private const val MAX_REDIRECTS = 3
    private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun get(
        spec: ProxySpec,
        url: URL,
        headers: Map<String, String>,
        maxBodyChars: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        onError: ((Throwable) -> Unit)? = null
    ): ProxyResponse? {
        return request(
            spec = spec,
            method = "GET",
            url = url,
            headers = headers,
            body = null,
            maxBodyChars = maxBodyChars,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            onError = onError
        )
    }

    fun request(
        spec: ProxySpec,
        method: String,
        url: URL,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBodyChars: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        onError: ((Throwable) -> Unit)? = null
    ): ProxyResponse? {
        var current = url
        var redirects = 0
        while (true) {
            val response = try {
                requestOnce(
                    spec = spec,
                    method = method,
                    url = current,
                    headers = headers,
                    body = body,
                    maxBodyChars = maxBodyChars,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs
                )
            } catch (t: Throwable) {
                ModuleLog.w(
                    TAG,
                    "request failed (${ProxySettings.describe(spec)}): " +
                        "${t.javaClass.simpleName}: ${t.message}"
                )
                onError?.invoke(t)
                return null
            }
            val location = response.location
            if (method.equals("GET", true) &&
                response.code in 300..399 &&
                !location.isNullOrBlank() &&
                redirects < MAX_REDIRECTS
            ) {
                current = try {
                    URL(current, location)
                } catch (_: Throwable) {
                    return ProxyResponse(response.code, response.body)
                }
                redirects++
                continue
            }
            return ProxyResponse(response.code, response.body)
        }
    }

    private fun requestOnce(
        spec: ProxySpec,
        method: String,
        url: URL,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBodyChars: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): RawResponse {
        val isHttps = url.protocol.equals("https", true)
        val targetPort = when {
            url.port != -1 -> url.port
            isHttps -> 443
            else -> 80
        }
        val raw = connectProxy(spec, url.host, targetPort, connectTimeoutMs)
        try {
            raw.soTimeout = readTimeoutMs
            if (spec.mode == ProxyMode.HTTP && isHttps) {
                establishHttpTunnel(raw, url.host, targetPort, spec)
            }
            val stream: Socket = if (isHttps) wrapTls(raw, url.host, targetPort) else raw
            val absoluteForm = spec.mode == ProxyMode.HTTP && !isHttps
            val request = buildRequest(
                method = method,
                url = url,
                headers = headers,
                bodyLength = body?.size,
                absoluteForm = absoluteForm,
                proxyAuthorization = if (absoluteForm) spec.basicAuthHeaderOrNull() else null
            )
            val output = BufferedOutputStream(stream.getOutputStream())
            output.write(request.toByteArray(Charsets.UTF_8))
            if (body != null && body.isNotEmpty()) {
                output.write(body)
            }
            output.flush()
            val input = BufferedInputStream(stream.getInputStream())
            return readResponse(input, maxBodyChars)
        } finally {
            try {
                raw.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun connectProxy(
        spec: ProxySpec,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int
    ): Socket {
        val socket = Socket()
        try {
            if (spec.mode == ProxyMode.DIRECT) {
                socket.connect(InetSocketAddress(targetHost, targetPort), timeoutMs)
                socket.soTimeout = timeoutMs
                return socket
            }
            socket.connect(InetSocketAddress(spec.host, spec.port), timeoutMs)
            socket.soTimeout = timeoutMs
            when (spec.mode) {
                ProxyMode.HTTP -> Unit
                ProxyMode.SOCKS4 -> socks4Handshake(socket, targetHost, targetPort, spec.username)
                ProxyMode.SOCKS5 -> socks5Handshake(socket, targetHost, targetPort, spec.username, spec.password)
                else -> throw IOException("unsupported proxy mode")
            }
            return socket
        } catch (t: Throwable) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    private fun socks4Handshake(socket: Socket, host: String, port: Int, userId: String) {
        val userBytes = userId.toByteArray(Charsets.UTF_8)
        if (userBytes.size > 255) throw IOException("SOCKS4 用户 ID 过长")
        val output = socket.getOutputStream()
        val ipv4 = resolveIpv4(host)
        output.write(0x04)
        output.write(0x01)
        output.write((port shr 8) and 0xFF)
        output.write(port and 0xFF)
        if (ipv4 != null) {
            output.write(ipv4.address)
            output.write(userBytes)
            output.write(0x00)
        } else {
            // SOCKS4a：由代理负责解析域名。
            output.write(byteArrayOf(0, 0, 0, 1))
            output.write(userBytes)
            output.write(0x00)
            output.write(host.toByteArray(Charsets.US_ASCII))
            output.write(0x00)
        }
        output.flush()

        val reply = ByteArray(8)
        readFully(socket.getInputStream(), reply)
        val version = reply[0].toInt() and 0xFF
        val status = reply[1].toInt() and 0xFF
        if (version != 0 && version != 4) {
            throw IOException("SOCKS4 响应版本异常: $version")
        }
        if (status != 0x5A) {
            throw IOException("SOCKS4 代理拒绝连接（code=$status）")
        }
    }

    private fun socks5Handshake(
        socket: Socket,
        host: String,
        port: Int,
        username: String,
        password: String
    ) {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        val hasCredentials = username.isNotEmpty() || password.isNotEmpty()

        if (hasCredentials) {
            output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            output.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        output.flush()
        val greeting = ByteArray(2)
        readFully(input, greeting)
        if ((greeting[0].toInt() and 0xFF) != 0x05) {
            throw IOException("SOCKS5 响应版本异常")
        }
        when (greeting[1].toInt() and 0xFF) {
            0x00 -> Unit
            0x02 -> {
                if (!hasCredentials) throw IOException("SOCKS5 代理要求认证")
                val userBytes = username.toByteArray(Charsets.UTF_8)
                val passBytes = password.toByteArray(Charsets.UTF_8)
                if (userBytes.size > 255 || passBytes.size > 255) {
                    throw IOException("SOCKS5 用户名或密码过长")
                }
                output.write(0x01)
                output.write(userBytes.size)
                output.write(userBytes)
                output.write(passBytes.size)
                output.write(passBytes)
                output.flush()
                val authReply = ByteArray(2)
                readFully(input, authReply)
                if ((authReply[0].toInt() and 0xFF) != 0x01) {
                    throw IOException("SOCKS5 认证响应版本异常")
                }
                if ((authReply[1].toInt() and 0xFF) != 0x00) {
                    throw IOException("SOCKS5 认证失败（code=${authReply[1].toInt() and 0xFF}）")
                }
            }
            else -> throw IOException("SOCKS5 代理不接受当前认证方式（method=${greeting[1].toInt() and 0xFF}）")
        }

        val hostBytes = host.toByteArray(Charsets.UTF_8)
        if (hostBytes.size > 255) {
            throw IOException("目标主机名过长")
        }
        output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
        output.write(hostBytes)
        output.write((port shr 8) and 0xFF)
        output.write(port and 0xFF)
        output.flush()

        val head = ByteArray(4)
        readFully(input, head)
        if ((head[0].toInt() and 0xFF) != 0x05) {
            throw IOException("SOCKS5 响应版本异常")
        }
        val status = head[1].toInt() and 0xFF
        if (status != 0x00) {
            throw IOException("SOCKS5 代理拒绝连接（code=$status）")
        }
        when (head[3].toInt() and 0xFF) {
            0x01 -> readFully(input, ByteArray(6))
            0x03 -> {
                val length = input.read()
                if (length < 0) throw IOException("SOCKS5 响应被截断")
                readFully(input, ByteArray(length + 2))
            }
            0x04 -> readFully(input, ByteArray(18))
            else -> throw IOException("SOCKS5 未知地址类型")
        }
    }

    private fun establishHttpTunnel(socket: Socket, host: String, port: Int, spec: ProxySpec) {
        val authority = if (port == 443) host else "$host:$port"
        val request = buildString {
            append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
            append("Host: ").append(authority).append("\r\n")
            append("Proxy-Connection: keep-alive\r\n")
            spec.basicAuthHeaderOrNull()?.let {
                append("Proxy-Authorization: ").append(it).append("\r\n")
            }
            append("\r\n")
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write(request.toByteArray(Charsets.UTF_8))
        output.flush()

        val input = BufferedInputStream(socket.getInputStream())
        val statusLine = readLine(input) ?: throw IOException("HTTP 代理未返回响应")
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("HTTP 代理返回无效状态行: $statusLine")
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }
        if (code == 407) {
            throw IOException("HTTP 代理认证失败（407）")
        }
        if (code !in 200..299) {
            throw IOException("HTTP 代理 CONNECT 失败（HTTP $code）")
        }
    }

    private fun wrapTls(raw: Socket, host: String, port: Int): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket(raw, host, port, true) as SSLSocket
        val parameters = socket.sslParameters
        parameters.endpointIdentificationAlgorithm = "HTTPS"
        if (!isIpLiteral(host)) {
            try {
                parameters.serverNames = listOf(SNIHostName(host))
            } catch (_: Throwable) {
            }
        }
        socket.sslParameters = parameters
        socket.startHandshake()
        return socket
    }

    private fun buildRequest(
        method: String,
        url: URL,
        headers: Map<String, String>,
        bodyLength: Int?,
        absoluteForm: Boolean,
        proxyAuthorization: String?
    ): String {
        val builder = StringBuilder()
        val target = if (absoluteForm) absoluteUrl(url) else pathOf(url)
        builder.append(method.uppercase(Locale.ROOT)).append(' ').append(target).append(" HTTP/1.1\r\n")
        builder.append("Host: ").append(hostHeader(url)).append("\r\n")
        if (!proxyAuthorization.isNullOrEmpty()) {
            builder.append("Proxy-Authorization: ").append(proxyAuthorization).append("\r\n")
        }
        for ((name, value) in headers) {
            if (name.equals("Host", true) || name.equals("Connection", true) ||
                name.equals("Proxy-Authorization", true)
            ) {
                continue
            }
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        if (bodyLength != null && headers.keys.none { it.equals("Content-Length", true) }) {
            builder.append("Content-Length: ").append(bodyLength).append("\r\n")
        }
        builder.append("Connection: close\r\n\r\n")
        return builder.toString()
    }

    private fun absoluteUrl(url: URL): String {
        val builder = StringBuilder()
        builder.append(url.protocol).append("://").append(url.host)
        val defaultPort = if (url.protocol.equals("https", true)) 443 else 80
        if (url.port != -1 && url.port != defaultPort) {
            builder.append(':').append(url.port)
        }
        builder.append(pathOf(url))
        return builder.toString()
    }

    private fun pathOf(url: URL): String {
        val file = url.file
        return if (file.isNullOrEmpty()) "/" else file
    }

    private fun hostHeader(url: URL): String {
        val defaultPort = if (url.protocol.equals("https", true)) 443 else 80
        return if (url.port != -1 && url.port != defaultPort) {
            "${url.host}:${url.port}"
        } else {
            url.host
        }
    }

    private fun readResponse(input: BufferedInputStream, maxBodyChars: Int): RawResponse {
        val statusLine = readLine(input) ?: throw IOException("空响应")
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("无效状态行: $statusLine")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index <= 0) continue
            val name = line.substring(0, index).trim().lowercase(Locale.ROOT)
            val value = line.substring(index + 1).trim()
            headers[name] = if (headers.containsKey(name)) headers[name] + ", " + value else value
        }
        val maxBytes = maxBodyChars.coerceAtLeast(1024)
        val bodyBytes = when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> {
                readChunked(input, maxBytes)
            }
            else -> {
                val length = headers["content-length"]?.toLongOrNull()
                if (length != null && length >= 0) {
                    readLimited(input, minOf(length, maxBytes.toLong()).toInt())
                } else {
                    readLimited(input, maxBytes)
                }
            }
        }
        val decoded = decode(bodyBytes, headers["content-encoding"], maxBytes)
        return RawResponse(
            code = code,
            body = String(decoded, Charsets.UTF_8),
            location = headers["location"]
        )
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var value = input.read()
        if (value == -1) return null
        while (value != -1 && value != '\n'.code) {
            if (value != '\r'.code) buffer.write(value)
            value = input.read()
        }
        return String(buffer.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private fun readChunked(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        while (output.size() < maxBytes) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("无效分块长度")
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            val take = minOf(size, maxBytes - output.size())
            val chunk = ByteArray(take)
            readFully(input, chunk)
            output.write(chunk)
            if (take < size) break
            readLine(input)
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray, encoding: String?, maxBytes: Int): ByteArray {
        if (bytes.isEmpty() || encoding.isNullOrBlank()) return bytes
        return try {
            val stream: InputStream = when {
                encoding.contains("gzip", true) -> GZIPInputStream(ByteArrayInputStream(bytes))
                encoding.contains("deflate", true) -> InflaterInputStream(ByteArrayInputStream(bytes))
                else -> return bytes
            }
            stream.use { readLimited(it, maxBytes) }
        } catch (_: Throwable) {
            bytes
        }
    }

    private fun resolveIpv4(host: String): Inet4Address? {
        return try {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } as? Inet4Address
        } catch (_: Throwable) {
            null
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        return host.contains(':') || IPV4_LITERAL.matches(host)
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IOException("连接被代理关闭")
            offset += read
        }
    }

    private data class RawResponse(
        val code: Int,
        val body: String,
        val location: String?
    )
}
