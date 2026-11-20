package com.example.data.remote

import android.content.Context
import android.os.Build
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object LegacyHttpClient {

    internal val bcProvider: BouncyCastleProvider by lazy { BouncyCastleProvider() }

    @Volatile
    private var legacySocketFactory: SSLSocketFactory? = null

    @Volatile
    private var compositeTrustManager: X509TrustManager? = null

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching {
                val trustManager = buildCompositeTrustManager(context.applicationContext)
                compositeTrustManager = trustManager
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
                legacySocketFactory = CompatTlsSocketFactory(sslContext.socketFactory)
            }
            initialized = true
        }
    }

    private fun getOrBuildTrustManager(): X509TrustManager {
        compositeTrustManager?.let { return it }
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as KeyStore?)
        return systemTmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun buildCompositeTrustManager(context: Context): X509TrustManager {
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as KeyStore?)
        val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        val bundledCerts = mutableListOf<X509Certificate>()
        val bundledTm = runCatching {
            val cf = CertificateFactory.getInstance("X.509", bcProvider)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            context.assets.open("certs/ca-certificates.crt").use { stream ->
                var idx = 0
                val certs = cf.generateCertificates(stream)
                for (cert in certs) {
                    if (cert is X509Certificate) {
                        bundledCerts.add(cert)
                        ks.setCertificateEntry("modern_ca_$idx", cert)
                        idx++
                    }
                }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }.getOrNull()

        val anchors = HashSet<TrustAnchor>()
        systemTm.acceptedIssuers.forEach { anchors.add(TrustAnchor(it, null)) }
        bundledCerts.forEach { anchors.add(TrustAnchor(it, null)) }
        val bcPkixTm = if (anchors.isNotEmpty()) BcPkixTrustManager(anchors, bcProvider) else null

        val delegates = listOfNotNull(systemTm, bundledTm, bcPkixTm)
        return CompositeX509TrustManager(delegates)
    }

    fun openConfiguredConnection(
        urlString: String,
        method: String = "GET",
        timeoutMs: Int = 8000,
        userAgent: String = "TabTubeLite/1.1 (Legacy-Tablet)"
    ): HttpURLConnection {
        val url = URL(urlString)
        return (url.openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection && legacySocketFactory != null) {
                sslSocketFactory = legacySocketFactory
            }
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }
    }

    internal fun openStream(
        urlString: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        timeoutMs: Int = 8000
    ): PureBcHttpsTransport.TransportResponse {
        val tm = getOrBuildTrustManager()
        val isHttps = urlString.startsWith("https://", ignoreCase = true)

        // On legacy Android (< API 21, e.g. Galaxy Tab 2 4.1/4.2), use pure-Java BouncyCastle TLS 1.3/1.2 directly.
        if (isHttps && Build.VERSION.SDK_INT < 21) {
            return PureBcHttpsTransport.execute(
                urlString = urlString,
                method = method,
                headers = headers,
                bodyBytes = bodyBytes,
                timeoutMs = timeoutMs,
                trustManager = tm,
                bcProvider = bcProvider
            )
        }

        return try {
            executePlatformHttp(urlString, method, headers, bodyBytes, timeoutMs)
        } catch (sslErr: SSLException) {
            if (!isHttps) throw sslErr
            PureBcHttpsTransport.execute(
                urlString = urlString,
                method = method,
                headers = headers,
                bodyBytes = bodyBytes,
                timeoutMs = timeoutMs,
                trustManager = tm,
                bcProvider = bcProvider
            )
        }
    }

    private fun executePlatformHttp(
        urlString: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        timeoutMs: Int
    ): PureBcHttpsTransport.TransportResponse {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            ?: "TabTubeLite/1.1 (Legacy-Tablet)"
        val conn = openConfiguredConnection(urlString, method, timeoutMs, ua).apply {
            for ((k, v) in headers) {
                if (!k.equals("User-Agent", ignoreCase = true)) {
                    setRequestProperty(k, v)
                }
            }
            if (bodyBytes != null) {
                doOutput = true
                outputStream.use { out ->
                    out.write(bodyBytes)
                    out.flush()
                }
            }
        }
        val code = conn.responseCode
        val rawStream = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?: ByteArrayInputStream(ByteArray(0))
        val respHeaders = LinkedHashMap<String, String>()
        conn.headerFields.forEach { (k, v) ->
            if (k != null && !v.isNullOrEmpty()) {
                respHeaders[k.lowercase(Locale.US)] = v.last()
            }
        }
        val closer = Closeable { runCatching { conn.disconnect() } }
        return PureBcHttpsTransport.TransportResponse(
            code = code,
            headers = respHeaders,
            bodyStream = rawStream,
            closeHandle = closer
        )
    }

    fun getString(urlString: String, timeoutMs: Int = 8000): HttpResult {
        return openStream(
            urlString = urlString,
            method = "GET",
            timeoutMs = timeoutMs
        ).use { resp ->
            val body = readAllUtf8(resp.bodyStream)
            HttpResult(code = resp.code, body = body)
        }
    }

    fun postJson(
        urlString: String,
        jsonBody: String,
        userAgent: String,
        timeoutMs: Int = 8000
    ): HttpResult {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Content-Type" to "application/json; charset=UTF-8"
        )
        return openStream(
            urlString = urlString,
            method = "POST",
            headers = headers,
            bodyBytes = jsonBody.toByteArray(Charsets.UTF_8),
            timeoutMs = timeoutMs
        ).use { resp ->
            val body = readAllUtf8(resp.bodyStream)
            HttpResult(code = resp.code, body = body)
        }
    }

    fun getBytes(urlString: String, timeoutMs: Int = 8000): ByteArray? {
        return try {
            openStream(urlString = urlString, method = "GET", timeoutMs = timeoutMs).use { resp ->
                if (!resp.isSuccessful) return null
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = resp.bodyStream.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readAllUtf8(input: InputStream): String {
        return input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    data class HttpResult(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private class BcPkixTrustManager(
        private val trustAnchors: Set<TrustAnchor>,
        private val bcProvider: BouncyCastleProvider
    ) : X509TrustManager {
        private val anchorEncodedSet: Set<ByteArrayKey> by lazy {
            trustAnchors.mapNotNull { anchor ->
                anchor.trustedCert?.encoded?.let { ByteArrayKey(it) }
            }.toHashSet()
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Lege certificaatketen")
            val cf = CertificateFactory.getInstance("X.509", bcProvider)
            val reEncoded = chain.map { cf.generateCertificate(ByteArrayInputStream(it.encoded)) as X509Certificate }

            // Direct anchor match if server only presented a trusted root
            if (reEncoded.size == 1 && anchorEncodedSet.contains(ByteArrayKey(reEncoded[0].encoded))) {
                reEncoded[0].checkValidity()
                return
            }

            val params = PKIXParameters(trustAnchors).apply {
                isRevocationEnabled = false
            }
            val validator = CertPathValidator.getInstance("PKIX", bcProvider)
            try {
                validator.validate(cf.generateCertPath(reEncoded), params)
            } catch (primaryErr: Exception) {
                // Some servers include the self-signed Root CA at the end of the chain
                if (reEncoded.size > 1 && anchorEncodedSet.contains(ByteArrayKey(reEncoded.last().encoded))) {
                    validator.validate(cf.generateCertPath(reEncoded.dropLast(1)), params)
                } else {
                    throw primaryErr
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            trustAnchors.mapNotNull { it.trustedCert }.toTypedArray()

        private class ByteArrayKey(private val bytes: ByteArray) {
            override fun equals(other: Any?): Boolean =
                other is ByteArrayKey && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }
    }

    internal class CompositeX509TrustManager(
        private val delegates: List<X509TrustManager>
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var lastError: Exception? = null
            for (tm in delegates) {
                try {
                    tm.checkClientTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw (lastError ?: CertificateException("Client not trusted"))
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var lastError: Exception? = null
            for (tm in delegates) {
                try {
                    tm.checkServerTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw (lastError ?: CertificateException("Server not trusted"))
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
        }
    }

    private class CompatTlsSocketFactory(
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket = patch(delegate.createSocket())

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            patch(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            patch(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            patch(delegate.createSocket(address, port, localAddress, localPort))

        private fun patch(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val supportedProtocols = socket.supportedProtocols.toSet()
                val desiredProtocols = listOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")
                    .filter { it in supportedProtocols }
                    .toTypedArray()
                if (desiredProtocols.isNotEmpty()) {
                    socket.enabledProtocols = desiredProtocols
                }
            }
            return socket
        }
    }
}
