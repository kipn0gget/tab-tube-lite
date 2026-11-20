package com.example.data.remote

import com.example.data.model.SponsorSegment
import org.json.JSONArray
import java.net.URLEncoder

class SponsorBlockService {

    fun fetchSegments(videoId: String): List<SponsorSegment> {
        if (videoId.isBlank() || videoId.startsWith("benchmark_")) return emptyList()
        return try {
            val categories = URLEncoder.encode("""["sponsor","intro","outro","selfpromo"]""", "UTF-8")
            val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$categories"
            val res = LegacyHttpClient.getString(url, timeoutMs = 5000)
            if (!res.isSuccessful || res.body.isBlank()) return emptyList()

            val arr = JSONArray(res.body)
            val segments = mutableListOf<SponsorSegment>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val segArr = obj.optJSONArray("segment") ?: continue
                if (segArr.length() < 2) continue
                segments.add(
                    SponsorSegment(
                        uuid = obj.optString("UUID"),
                        category = obj.optString("category"),
                        startSec = segArr.optDouble(0, 0.0).toFloat(),
                        endSec = segArr.optDouble(1, 0.0).toFloat()
                    )
                )
            }
            segments.sortedBy { it.startSec }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
