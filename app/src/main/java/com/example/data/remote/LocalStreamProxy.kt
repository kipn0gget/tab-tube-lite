package com.example.data.remote

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Localhost HTTP-to-HTTPS stream relay for Android 4.1-4.4 MediaPlayer (Stagefright),
 * which lacks TLS 1.2 and modern Root CA certificates when playing HTTPS video URLs.
 */
class LocalStreamProxy {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var targetUrl: String? = null

    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()

    fun startProxyForUrl(httpsUrl: String): String {
        targetUrl = httpsUrl
        val server = serverSocket ?: synchronized(this) {
            serverSocket ?: ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).also { ss ->
                serverSocket = ss
                acceptExecutor.execute { acceptLoop(ss) }
            }
        }
        return "http://127.0.0.1:${server.localPort}/stream"
    }

    fun stop() {
        targetUrl = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                val client = server.accept()
                clientExecutor.execute { handleClient(client) }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            val remoteUrl = targetUrl ?: return
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val isHead = requestLine.startsWith("HEAD", ignoreCase = true)
            val reqHeaders = LinkedHashMap<String, String>()
            reqHeaders["Accept-Encoding"] = "identity"
            if (!rangeHeader.isNullOrBlank()) {
                reqHeaders["Range"] = rangeHeader
            }

            var resp: PureBcHttpsTransport.TransportResponse? = null
            try {
                resp = LegacyHttpClient.openStream(
                    urlString = remoteUrl,
                    method = if (isHead) "HEAD" else "GET",
                    headers = reqHeaders,
                    timeoutMs = 15000
                )
                val code = resp.code
                val contentType = resp.header("content-type") ?: "video/mp4"
                val contentLength = resp.header("content-length")
                val contentRange = resp.header("content-range")
                val statusText = if (code == 206) "Partial Content" else "OK"

                val out = socket.getOutputStream()
                val headers = buildString {
                    append("HTTP/1.1 $code $statusText\r\n")
                    append("Content-Type: $contentType\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (!contentLength.isNullOrBlank()) append("Content-Length: $contentLength\r\n")
                    if (!contentRange.isNullOrBlank()) append("Content-Range: $contentRange\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.US_ASCII))

                if (!isHead && code in 200..299) {
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = resp.bodyStream.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
                out.flush()
            } catch (_: Exception) {
                // Client disconnected on seek/stop
            } finally {
                runCatching { resp?.close() }
            }
        }
    }
}
