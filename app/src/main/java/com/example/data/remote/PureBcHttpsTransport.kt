package com.example.data.remote

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.NameType
import org.bouncycastle.tls.ServerName
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.Vector
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/**
 * Pure-Java TLS 1.3 / 1.2 + HTTP/1.1 transport backed by BouncyCastle lightweight crypto (BcTlsCrypto).
 *
 * Bypasses Android 4.1-4.4 (API 16-20) OpenSSL limitations and avoids javax.crypto.Cipher.updateAAD()
 * (introduced in API 19) so AES-GCM, ChaCha20-Poly1305, X25519, SNI, and modern CAs work cleanly on
 * legacy Galaxy Tab 2 hardware.
 */
internal object PureBcHttpsTransport {

    private val secureRandom = SecureRandom()

    data class TransportResponse(
        val code: Int,
        val headers: Map<String, String>,
        val bodyStream: InputStream,
        private val closeHandle: Closeable
    ) : Closeable {
        val isSuccessful: Boolean get() = code in 200..299
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]
        override fun close() {
            runCatching { bodyStream.close() }
            runCatching { closeHandle.close() }
        }
    }

    fun execute(
        urlString: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        timeoutMs: Int = 8000,
        trustManager: X509TrustManager,
        bcProvider: BouncyCastleProvider
    ): TransportResponse {
        var curUrl = urlString
        var curMethod = method.uppercase(Locale.US)
        var curBody = bodyBytes

        for (hop in 0 until 6) {
            val url = URL(curUrl)
            if (url.protocol.equals("http", ignoreCase = true)) {
                return executePlainHttp(curUrl, curMethod, headers, curBody, timeoutMs)
            }
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw IOException("Niet-ondersteund protocol: ${url.protocol}")
            }

            val host = url.host
            val port = if (url.port > 0) url.port else 443
            val pathAndQuery = buildPathAndQuery(url)

            val rawSocket = Socket()
            var tlsProtocol: TlsClientProtocol? = null
            var handshaked = false
            try {
                rawSocket.connect(InetSocketAddress(host, port), timeoutMs)
                rawSocket.soTimeout = timeoutMs

                tlsProtocol = TlsClientProtocol(rawSocket.getInputStream(), rawSocket.getOutputStream())
                val tlsClient = object : DefaultTlsClient(BcTlsCrypto(secureRandom)) {
                    override fun getSNIServerNames(): Vector<ServerName>? {
                        if (isNumericIp(host)) return null
                        return Vector<ServerName>().apply {
                            add(ServerName(NameType.host_name, host.toByteArray(Charsets.US_ASCII)))
                        }
                    }

                    override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
                        override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                            val certList = serverCertificate.certificate?.certificateList
                            if (certList.isNullOrEmpty()) {
                                throw TlsFatalAlert(AlertDescription.bad_certificate)
                            }
                            try {
                                val cf = CertificateFactory.getInstance("X.509", bcProvider)
                                val chain = Array(certList.size) { idx ->
                                    cf.generateCertificate(ByteArrayInputStream(certList[idx].encoded)) as X509Certificate
                                }
                                val keyAlg = chain[0].publicKey?.algorithm ?: "RSA"
                                val authType = if (keyAlg.equals("EC", ignoreCase = true)) "ECDHE_ECDSA" else "RSA"
                                trustManager.checkServerTrusted(chain, authType)
                                verifyHostname(host, chain[0])
                            } catch (e: TlsFatalAlert) {
                                throw e
                            } catch (e: Exception) {
                                throw TlsFatalAlert(AlertDescription.bad_certificate, e.message, e)
                            }
                        }

                        override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials? = null
                    }
                }

                tlsProtocol.connect(tlsClient)
                handshaked = true

                val tlsOut = tlsProtocol.outputStream
                val hostHeader = if (port == 443) host else "$host:$port"
                val req = StringBuilder().apply {
                    append("$curMethod $pathAndQuery HTTP/1.1\r\n")
                    append("Host: $hostHeader\r\n")
                    append("Connection: close\r\n")
                    var hasAcceptEncoding = false
                    var hasUserAgent = false
                    var hasAccept = false
                    for ((k, v) in headers) {
                        if (k.equals("Host", ignoreCase = true) || k.equals("Connection", ignoreCase = true)) continue
                        if (k.equals("Accept-Encoding", ignoreCase = true)) hasAcceptEncoding = true
                        if (k.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                        if (k.equals("Accept", ignoreCase = true)) hasAccept = true
                        append("$k: $v\r\n")
                    }
                    if (!hasUserAgent) append("User-Agent: TabTubeLite/1.1 (Legacy-Tablet)\r\n")
                    if (!hasAccept) append("Accept: */*\r\n")
                    if (!hasAcceptEncoding) append("Accept-Encoding: gzip\r\n")
                    if (curBody != null) {
                        if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                            append("Content-Type: application/json; charset=UTF-8\r\n")
                        }
                        append("Content-Length: ${curBody.size}\r\n")
                    }
                    append("\r\n")
                }
                tlsOut.write(req.toString().toByteArray(Charsets.ISO_8859_1))
                if (curBody != null) {
                    tlsOut.write(curBody)
                }
                tlsOut.flush()

                val tlsIn = tlsProtocol.inputStream
                val statusLine = readAsciiLine(tlsIn) ?: throw IOException("Leeg HTTPS-antwoord van $host")
                val statusCode = statusLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull()
                    ?: throw IOException("Ongeldige HTTP-statusregel van $host: $statusLine")

                val respHeaders = LinkedHashMap<String, String>()
                while (true) {
                    val line = readAsciiLine(tlsIn) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim().lowercase(Locale.US)
                        val value = line.substring(colon + 1).trim()
                        respHeaders[key] = value
                    }
                }

                if (statusCode in intArrayOf(301, 302, 303, 307, 308)) {
                    val location = respHeaders["location"]
                    closeQuietly(tlsProtocol, rawSocket)
                    if (location.isNullOrBlank()) {
                        throw IOException("Redirect ($statusCode) zonder Location-header van $host")
                    }
                    curUrl = URL(url, location).toString()
                    if (statusCode != 307 && statusCode != 308 && curMethod == "POST") {
                        curMethod = "GET"
                        curBody = null
                    }
                    continue
                }

                val closeHandle = Closeable { closeQuietly(tlsProtocol, rawSocket) }
                if (curMethod == "HEAD" || statusCode == 204 || statusCode == 304) {
                    closeHandle.close()
                    return TransportResponse(statusCode, respHeaders, ByteArrayInputStream(ByteArray(0)), closeHandle)
                }

                val transferEnc = respHeaders["transfer-encoding"].orEmpty()
                val contentLength = respHeaders["content-length"]?.toLongOrNull() ?: -1L
                val contentEnc = respHeaders["content-encoding"].orEmpty()

                val framedStream: InputStream = when {
                    transferEnc.contains("chunked", ignoreCase = true) -> ChunkedInputStream(tlsIn)
                    contentLength >= 0L -> BoundedInputStream(tlsIn, contentLength)
                    else -> tlsIn
                }
                val decodedStream: InputStream = if (contentEnc.contains("gzip", ignoreCase = true)) {
                    GZIPInputStream(framedStream)
                } else {
                    framedStream
                }

                return TransportResponse(
                    code = statusCode,
                    headers = respHeaders,
                    bodyStream = ManagedCloseInputStream(decodedStream, closeHandle),
                    closeHandle = closeHandle
                )
            } catch (t: Throwable) {
                if (!handshaked) {
                    runCatching { rawSocket.close() }
                } else {
                    closeQuietly(tlsProtocol, rawSocket)
                }
                if (t is IOException) throw t
                throw IOException(t.message ?: "TLS-fout bij verbinden met $host", t)
            }
        }
        throw IOException("Te veel redirects voor $urlString")
    }

    private fun executePlainHttp(
        urlString: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        timeoutMs: Int
    ): TransportResponse {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            for ((k, v) in headers) setRequestProperty(k, v)
            if (bodyBytes != null) {
                doOutput = true
                outputStream.use { it.write(bodyBytes) }
            }
        }
        val code = conn.responseCode
        val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?: ByteArrayInputStream(ByteArray(0))
        val respHeaders = LinkedHashMap<String, String>()
        conn.headerFields.forEach { (k, v) ->
            if (k != null && !v.isNullOrEmpty()) {
                respHeaders[k.lowercase(Locale.US)] = v.last()
            }
        }
        val closer = Closeable { runCatching { conn.disconnect() } }
        return TransportResponse(code, respHeaders, ManagedCloseInputStream(raw, closer), closer)
    }

    private fun closeQuietly(tlsProtocol: TlsClientProtocol?, socket: Socket) {
        runCatching { tlsProtocol?.close() }
        runCatching { socket.close() }
    }

    private fun buildPathAndQuery(url: URL): String {
        val path = url.path.takeIf { it.isNotEmpty() } ?: "/"
        return if (url.query.isNullOrEmpty()) path else "$path?${url.query}"
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    private fun isNumericIp(host: String): Boolean {
        return host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) || host.contains(':')
    }

    internal fun verifyHostname(host: String, cert: X509Certificate) {
        val target = host.lowercase(Locale.US).trimEnd('.')
        val sans = runCatching { cert.subjectAlternativeNames }.getOrNull()
        val isIp = isNumericIp(target)
        var hasDnsSan = false

        if (sans != null) {
            for (entry in sans) {
                if (entry.size < 2) continue
                val type = (entry[0] as? Number)?.toInt() ?: continue
                val value = (entry[1] as? String)?.lowercase(Locale.US)?.trimEnd('.') ?: continue
                if (isIp && type == 7) {
                    if (value == target) return
                } else if (!isIp && type == 2) {
                    hasDnsSan = true
                    if (matchesDnsPattern(target, value)) return
                }
            }
        }

        if (!isIp && !hasDnsSan) {
            val cn = extractCn(cert.subjectX500Principal?.name.orEmpty())
            if (cn != null && matchesDnsPattern(target, cn.lowercase(Locale.US))) return
        }

        throw SSLPeerUnverifiedException("Hostnaam '$host' komt niet overeen met SSL-certificaat")
    }

    private fun matchesDnsPattern(host: String, pattern: String): Boolean {
        if (host == pattern) return true
        if (!pattern.startsWith("*.")) return false
        val suffix = pattern.substring(1) // ".example.com"
        if (!host.endsWith(suffix) || suffix.count { it == '.' } < 2) return false
        val prefixLen = host.length - suffix.length
        if (prefixLen <= 0) return false
        return !host.substring(0, prefixLen).contains('.')
    }

    private fun extractCn(dn: String): String? {
        val match = Regex("(?:^|,)\\s*CN=([^,]+)", RegexOption.IGNORE_CASE).find(dn)
        return match?.groupValues?.getOrNull(1)?.trim()
    }

    private class ManagedCloseInputStream(
        delegate: InputStream,
        private val closeHandle: Closeable
    ) : FilterInputStream(delegate) {
        override fun close() {
            try {
                super.close()
            } finally {
                runCatching { closeHandle.close() }
            }
        }
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = delegate.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = delegate.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }
    }

    private class ChunkedInputStream(private val delegate: InputStream) : InputStream() {
        private var chunkRemaining = 0
        private var eof = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (len == 0) return 0
            if (chunkRemaining == 0) {
                if (!nextChunk()) return -1
            }
            val toRead = minOf(len, chunkRemaining)
            val n = delegate.read(b, off, toRead)
            if (n == -1) {
                eof = true
                return -1
            }
            chunkRemaining -= n
            if (chunkRemaining == 0) {
                consumeCrlf()
            }
            return n
        }

        private fun nextChunk(): Boolean {
            var line: String?
            do {
                line = readAsciiLine(delegate) ?: run {
                    eof = true
                    return false
                }
            } while (line.isEmpty())

            val sizeHex = line.substringBefore(';').trim()
            val size = sizeHex.toIntOrNull(16) ?: throw IOException("Ongeldige chunk-grootte: $sizeHex")
            if (size == 0) {
                while (true) {
                    val trailer = readAsciiLine(delegate) ?: break
                    if (trailer.isEmpty()) break
                }
                eof = true
                return false
            }
            chunkRemaining = size
            return true
        }

        private fun consumeCrlf() {
            val first = delegate.read()
            if (first == '\r'.code) {
                delegate.read()
            }
        }
    }
}
