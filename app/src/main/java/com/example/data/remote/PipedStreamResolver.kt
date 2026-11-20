package com.example.data.remote

import com.example.data.model.LegacyTuningSettings
import com.example.data.model.ResolvedStream
import com.example.data.model.VideoItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class PipedStreamResolver {

    private val pipedFallbackInstances = listOf(
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.kavin.rocks"
    )

    private val invidiousFallbackInstances = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be"
    )

    fun resolvePlayableStream(
        videoId: String,
        settings: LegacyTuningSettings
    ): Result<ResolvedStream> {
        var lastError: Throwable? = null

        // Tier 1: Direct YouTube InnerTube API (no external proxy needed, returns direct H.264 MP4/HLS)
        resolveViaInnerTube(videoId, settings).fold(
            onSuccess = { return Result.success(it) },
            onFailure = { lastError = it }
        )

        // Tier 2: Invidious formatStreams (direct muxed H.264 MP4)
        for (host in invidiousFallbackInstances) {
            resolveViaInvidious(videoId, settings, host).fold(
                onSuccess = { return Result.success(it) },
                onFailure = { lastError = it }
            )
        }

        // Tier 3: Piped API instances
        val pipedCandidates = (listOf(settings.pipedApiBaseUrl.trimEnd('/')) + pipedFallbackInstances)
            .filter { it.startsWith("https://") }
            .distinct()

        for (baseUrl in pipedCandidates) {
            try {
                val res = LegacyHttpClient.getString("$baseUrl/streams/$videoId", timeoutMs = 7000)
                if (!res.isSuccessful) {
                    lastError = IllegalStateException("HTTP ${res.code} van $baseUrl")
                    continue
                }
                if (res.body.isBlank() || res.body.trimStart().startsWith("<")) {
                    lastError = IllegalStateException("Geen geldige JSON van $baseUrl")
                    continue
                }
                val json = JSONObject(res.body)
                val resolved = extractBestLegacyStream(json, settings, baseUrl)
                if (resolved != null) {
                    return Result.success(resolved)
                } else {
                    lastError = IllegalStateException("Geen compatibele stream bij $baseUrl")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Kon geen directe MP4/HLS-stream ophalen"))
    }

    private fun resolveViaInnerTube(
        videoId: String,
        settings: LegacyTuningSettings
    ): Result<ResolvedStream> {
        data class ClientSpec(
            val clientName: String,
            val clientVersion: String,
            val userAgent: String,
            val extraContext: String
        )

        val clients = listOf(
            ClientSpec(
                clientName = "ANDROID_VR",
                clientVersion = "1.60.19",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                extraContext = """"deviceMake":"Oculus","deviceModel":"Quest 3","osName":"Android","osVersion":"12L""""
            ),
            ClientSpec(
                clientName = "IOS",
                clientVersion = "20.10.4",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                extraContext = """"deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22D82""""
            ),
            ClientSpec(
                clientName = "ANDROID",
                clientVersion = "20.10.38",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
                extraContext = """"androidSdkVersion":30,"osName":"Android","osVersion":"11""""
            )
        )

        var lastError: Throwable? = null
        for (spec in clients) {
            try {
                val payload = """
                    {
                      "videoId": "$videoId",
                      "context": {
                        "client": {
                          "clientName": "${spec.clientName}",
                          "clientVersion": "${spec.clientVersion}",
                          ${spec.extraContext},
                          "hl": "nl",
                          "gl": "NL"
                        }
                      },
                      "contentCheckOk": true,
                      "racyCheckOk": true
                    }
                """.trimIndent()

                val res = LegacyHttpClient.postJson(
                    urlString = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    jsonBody = payload,
                    userAgent = spec.userAgent,
                    timeoutMs = 7000
                )
                if (!res.isSuccessful || res.body.isBlank()) {
                    lastError = IllegalStateException("InnerTube ${spec.clientName} HTTP ${res.code}")
                    continue
                }

                val root = JSONObject(res.body)
                val streamingData = root.optJSONObject("streamingData") ?: continue

                val audioUrl = pickInnerTubeAudioUrl(streamingData.optJSONArray("adaptiveFormats"))
                if (settings.audioOnlyMode && !audioUrl.isNullOrBlank()) {
                    return Result.success(
                        ResolvedStream(
                            streamUrl = audioUrl,
                            audioOnlyUrl = audioUrl,
                            isHls = false,
                            qualityLabel = "Audio Only",
                            codecLabel = "AAC/M4A",
                            sourceHost = "YouTube Direct (${spec.clientName})"
                        )
                    )
                }

                val muxed = pickInnerTubeMuxedFormat(streamingData.optJSONArray("formats"), settings)
                if (muxed != null) {
                    return Result.success(
                        ResolvedStream(
                            streamUrl = muxed.url,
                            audioOnlyUrl = audioUrl,
                            isHls = false,
                            qualityLabel = muxed.quality,
                            codecLabel = muxed.codec,
                            sourceHost = "YouTube Direct (${spec.clientName})"
                        )
                    )
                }

                val hls = streamingData.optString("hlsManifestUrl")
                if (hls.isNotBlank()) {
                    return Result.success(
                        ResolvedStream(
                            streamUrl = hls,
                            audioOnlyUrl = audioUrl,
                            isHls = true,
                            qualityLabel = "HLS (${settings.resolutionCap.shortLabel})",
                            codecLabel = "H.264 / HLS",
                            sourceHost = "YouTube Direct (${spec.clientName})"
                        )
                    )
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Geen InnerTube stream"))
    }

    private data class MuxedPick(val url: String, val quality: String, val codec: String)

    private fun pickInnerTubeAudioUrl(adaptiveFormats: JSONArray?): String? {
        if (adaptiveFormats == null) return null
        for (i in 0 until adaptiveFormats.length()) {
            val obj = adaptiveFormats.optJSONObject(i) ?: continue
            val url = obj.optString("url")
            val mime = obj.optString("mimeType")
            if (url.isNotBlank() && mime.startsWith("audio/mp4", ignoreCase = true)) {
                return url
            }
        }
        return null
    }

    private fun pickInnerTubeMuxedFormat(
        formats: JSONArray?,
        settings: LegacyTuningSettings
    ): MuxedPick? {
        if (formats == null) return null
        val maxH = settings.resolutionCap.maxHeight
        data class Candidate(val url: String, val height: Int, val quality: String, val codec: String, val score: Int)
        val candidates = mutableListOf<Candidate>()

        for (i in 0 until formats.length()) {
            val obj = formats.optJSONObject(i) ?: continue
            val url = obj.optString("url")
            if (url.isBlank()) continue
            val mime = obj.optString("mimeType")
            val height = obj.optInt("height", 360)
            val isH264 = mime.contains("avc1", ignoreCase = true) || mime.contains("mp4", ignoreCase = true)
            if (settings.forceH264Avc && !isH264) continue

            val heightPenalty = if (height <= maxH) (maxH - height) else (height - maxH + 1000)
            candidates.add(
                Candidate(
                    url = url,
                    height = height,
                    quality = obj.optString("qualityLabel").ifBlank { "${height}p" },
                    codec = if (isH264) "H.264 AVC MP4" else "MP4",
                    score = heightPenalty
                )
            )
        }
        val best = candidates.minByOrNull { it.score } ?: return null
        return MuxedPick(best.url, best.quality, best.codec)
    }

    private fun resolveViaInvidious(
        videoId: String,
        settings: LegacyTuningSettings,
        host: String
    ): Result<ResolvedStream> {
        return try {
            val res = LegacyHttpClient.getString("$host/api/v1/videos/$videoId", timeoutMs = 6000)
            if (!res.isSuccessful || res.body.isBlank() || res.body.trimStart().startsWith("<")) {
                return Result.failure(IllegalStateException("Invidious HTTP ${res.code}"))
            }
            val json = JSONObject(res.body)
            val formatStreams = json.optJSONArray("formatStreams")
            val maxH = settings.resolutionCap.maxHeight
            var bestUrl: String? = null
            var bestQuality = "360p"
            var bestScore = Int.MAX_VALUE

            if (formatStreams != null) {
                for (i in 0 until formatStreams.length()) {
                    val item = formatStreams.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    val type = item.optString("type")
                    val qualityLabel = item.optString("qualityLabel")
                    val height = Regex("(\\d+)p").find(qualityLabel)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 360
                    if (url.isBlank() || !type.contains("mp4", ignoreCase = true)) continue
                    val score = if (height <= maxH) (maxH - height) else (height - maxH + 1000)
                    if (score < bestScore) {
                        bestScore = score
                        bestUrl = url
                        bestQuality = qualityLabel.ifBlank { "${height}p" }
                    }
                }
            }

            if (!bestUrl.isNullOrBlank()) {
                Result.success(
                    ResolvedStream(
                        streamUrl = bestUrl,
                        audioOnlyUrl = null,
                        isHls = false,
                        qualityLabel = bestQuality,
                        codecLabel = "H.264 AVC MP4",
                        sourceHost = host.removePrefix("https://")
                    )
                )
            } else {
                Result.failure(IllegalStateException("Geen MP4 bij $host"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun searchVideos(query: String, baseUrl: String): Result<List<VideoItem>> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return Result.success(emptyList())

        var lastError: Throwable? = null

        // Tier 1: Direct YouTube InnerTube Search (fast, no external Piped proxy required)
        searchViaInnerTube(trimmed).fold(
            onSuccess = { list -> if (list.isNotEmpty()) return Result.success(list) },
            onFailure = { lastError = it }
        )

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val candidates = (listOf(baseUrl.trimEnd('/')) + pipedFallbackInstances)
            .filter { it.startsWith("https://") }
            .distinct()

        for (host in candidates) {
            try {
                val res = LegacyHttpClient.getString("$host/search?q=$encoded&filter=videos", timeoutMs = 8000)
                if (!res.isSuccessful) {
                    lastError = IllegalStateException("Zoeken mislukt: HTTP ${res.code}")
                    continue
                }
                if (res.body.isBlank() || res.body.trimStart().startsWith("<")) {
                    lastError = IllegalStateException("Ongeldig zoekantwoord van $host")
                    continue
                }
                val json = JSONObject(res.body)
                val items = json.optJSONArray("items") ?: JSONArray()
                val results = mutableListOf<VideoItem>()
                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    val urlPath = obj.optString("url")
                    val id = extractVideoId(urlPath) ?: continue
                    val title = obj.optString("title").ifBlank { "YouTube Video ($id)" }
                    val uploader = obj.optString("uploaderName").ifBlank { "YouTube" }
                    val channelPath = obj.optString("uploaderUrl").substringAfterLast("/")
                    val durationSec = obj.optLong("duration", -1L)
                    results.add(
                        VideoItem(
                            id = id,
                            title = title,
                            channelTitle = uploader,
                            channelId = channelPath,
                            category = "Zoekresultaat",
                            publishedLabel = obj.optString("uploadedDate").ifBlank { "Zoekresultaat" },
                            thumbnailUrl = "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                            durationLabel = if (durationSec > 0) formatDuration(durationSec) else null
                        )
                    )
                }
                return Result.success(results)
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Invidious search fallback (still pure JSON, no WebView)
        for (host in invidiousFallbackInstances) {
            try {
                val res = LegacyHttpClient.getString("$host/api/v1/search?q=$encoded&type=video", timeoutMs = 7000)
                if (!res.isSuccessful || res.body.isBlank() || res.body.trimStart().startsWith("<")) continue
                val arr = JSONArray(res.body)
                val results = mutableListOf<VideoItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("videoId")
                    if (id.isBlank()) continue
                    val dur = obj.optLong("lengthSeconds", -1L)
                    results.add(
                        VideoItem(
                            id = id,
                            title = obj.optString("title").ifBlank { "YouTube Video ($id)" },
                            channelTitle = obj.optString("author").ifBlank { "YouTube" },
                            channelId = obj.optString("authorId"),
                            category = "Zoekresultaat",
                            publishedLabel = obj.optString("publishedText").ifBlank { "Zoekresultaat" },
                            thumbnailUrl = "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                            durationLabel = if (dur > 0) formatDuration(dur) else null
                        )
                    )
                }
                return Result.success(results)
            } catch (e: Exception) {
                lastError = e
            }
        }

        return Result.failure(lastError ?: IllegalStateException("Zoeken niet beschikbaar"))
    }

    private fun searchViaInnerTube(query: String): Result<List<VideoItem>> {
        return try {
            val escapedQuery = JSONObject.quote(query)
            val payload = """
                {
                  "context": {
                    "client": {
                      "clientName": "WEB",
                      "clientVersion": "2.20241115.01.00",
                      "hl": "nl",
                      "gl": "NL"
                    }
                  },
                  "query": $escapedQuery
                }
            """.trimIndent()

            val res = LegacyHttpClient.postJson(
                urlString = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
                jsonBody = payload,
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                timeoutMs = 9000
            )
            if (!res.isSuccessful || res.body.isBlank()) {
                return Result.failure(IllegalStateException("InnerTube Search HTTP ${res.code}"))
            }

            Result.success(parseInnerTubeSearchResponse(res.body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun parseInnerTubeSearchResponse(jsonBody: String): List<VideoItem> {
        val root = JSONObject(jsonBody)
        val results = mutableListOf<VideoItem>()
        collectVideoRenderers(root, results)
        return results.distinctBy { it.id }.take(24)
    }

    private fun collectVideoRenderers(node: Any?, out: MutableList<VideoItem>) {
        if (out.size >= 30 || node == null) return
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("videoRenderer")
                    ?: node.optJSONObject("compactVideoRenderer")
                    ?: node.optJSONObject("videoWithContextRenderer")
                    ?: node.optJSONObject("gridVideoRenderer")
                if (renderer != null) {
                    parseRenderer(renderer)?.let { out.add(it) }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "videoRenderer" &&
                        key != "compactVideoRenderer" &&
                        key != "videoWithContextRenderer" &&
                        key != "gridVideoRenderer"
                    ) {
                        collectVideoRenderers(node.opt(key), out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideoRenderers(node.opt(i), out)
                }
            }
        }
    }

    private fun parseRenderer(obj: JSONObject): VideoItem? {
        val videoId = obj.optString("videoId")
        if (videoId.isBlank()) return null

        val title = readTextField(obj.optJSONObject("title") ?: obj.optJSONObject("headline"))
            .ifBlank { "YouTube Video ($videoId)" }
        val ownerObj = obj.optJSONObject("ownerText")
            ?: obj.optJSONObject("shortBylineText")
            ?: obj.optJSONObject("longBylineText")
        val owner = readTextField(ownerObj).ifBlank { "YouTube" }
        val channelId = ownerObj?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            .orEmpty()

        val published = readTextField(obj.optJSONObject("publishedTimeText")).ifBlank { "YouTube" }
        val length = readTextField(obj.optJSONObject("lengthText")).ifBlank { null }

        return VideoItem(
            id = videoId,
            title = title,
            channelTitle = owner,
            channelId = channelId,
            category = "Zoekresultaat",
            publishedLabel = published,
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            durationLabel = length
        )
    }

    private fun readTextField(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().trim()
    }

    private fun extractBestLegacyStream(
        json: JSONObject,
        settings: LegacyTuningSettings,
        host: String
    ): ResolvedStream? {
        val maxH = settings.resolutionCap.maxHeight

        val audioStreams = json.optJSONArray("audioStreams")
        var bestAudioUrl: String? = null
        if (audioStreams != null) {
            for (i in 0 until audioStreams.length()) {
                val item = audioStreams.optJSONObject(i) ?: continue
                val url = item.optString("url")
                val mime = item.optString("mimeType")
                if (url.isNotBlank() && (mime.contains("mp4", true) || bestAudioUrl == null)) {
                    bestAudioUrl = url
                    if (mime.contains("mp4", true)) break
                }
            }
        }

        if (settings.audioOnlyMode && !bestAudioUrl.isNullOrBlank()) {
            return ResolvedStream(
                streamUrl = bestAudioUrl,
                audioOnlyUrl = bestAudioUrl,
                isHls = false,
                qualityLabel = "Audio Only",
                codecLabel = "AAC/M4A",
                sourceHost = host.removePrefix("https://")
            )
        }

        val videoStreams = json.optJSONArray("videoStreams")
        data class Candidate(val url: String, val height: Int, val quality: String, val codec: String, val score: Int)
        val candidates = mutableListOf<Candidate>()

        if (videoStreams != null) {
            for (i in 0 until videoStreams.length()) {
                val item = videoStreams.optJSONObject(i) ?: continue
                val videoOnly = item.optBoolean("videoOnly", true)
                val url = item.optString("url")
                if (videoOnly || url.isBlank()) continue

                val height = item.optInt("height", 360)
                val codec = item.optString("codec").ifBlank { item.optString("format") }
                val mime = item.optString("mimeType")
                val isH264 = codec.contains("avc", true) || mime.contains("mp4", true)
                if (settings.forceH264Avc && !isH264) continue

                val heightPenalty = if (height <= maxH) (maxH - height) else (height - maxH + 1000)
                val codecBonus = if (isH264) 0 else 500
                candidates.add(
                    Candidate(
                        url = url,
                        height = height,
                        quality = item.optString("quality").ifBlank { "${height}p" },
                        codec = if (isH264) "H.264 AVC" else codec,
                        score = heightPenalty + codecBonus
                    )
                )
            }
        }

        val bestMuxed = candidates.minByOrNull { it.score }
        if (bestMuxed != null) {
            return ResolvedStream(
                streamUrl = bestMuxed.url,
                audioOnlyUrl = bestAudioUrl,
                isHls = false,
                qualityLabel = bestMuxed.quality,
                codecLabel = bestMuxed.codec,
                sourceHost = host.removePrefix("https://")
            )
        }

        val hls = json.optString("hls")
        if (hls.isNotBlank()) {
            return ResolvedStream(
                streamUrl = hls,
                audioOnlyUrl = bestAudioUrl,
                isHls = true,
                qualityLabel = "Max ${settings.resolutionCap.shortLabel}",
                codecLabel = if (settings.forceH264Avc) "H.264 Lock" else "Auto",
                sourceHost = host.removePrefix("https://")
            )
        }

        return null
    }

    companion object {
        private val YOUTUBE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

        fun extractVideoId(input: String): String? {
            val trimmed = input.trim()
            if (YOUTUBE_ID_REGEX.matches(trimmed)) return trimmed
            val vParam = Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(trimmed)?.groupValues?.getOrNull(1)
            if (vParam != null) return vParam
            return Regex("(?:youtu\\.be/|/shorts/|/embed/|/watch\\?v=)([a-zA-Z0-9_-]{11})")
                .find(trimmed)?.groupValues?.getOrNull(1)
        }

        fun formatDuration(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
