package com.example.data.remote

import android.util.Xml
import com.example.data.model.VideoItem
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

class YouTubeRssService {

    fun fetchChannelFeed(
        channelId: String,
        fallbackChannelName: String,
        category: String
    ): List<VideoItem> {
        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        val res = LegacyHttpClient.getString(url, timeoutMs = 8000)
        if (!res.isSuccessful) {
            throw IllegalStateException("RSS HTTP ${res.code} voor $fallbackChannelName")
        }
        return parseAtomFeed(
            xml = res.body,
            channelId = channelId,
            fallbackChannelName = fallbackChannelName,
            category = category
        )
    }

    internal fun parseAtomFeed(
        xml: String,
        channelId: String,
        fallbackChannelName: String,
        category: String
    ): List<VideoItem> {
        if (xml.isBlank()) return emptyList()
        val items = mutableListOf<VideoItem>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var insideEntry = false
        var insideAuthor = false
        var currentTag = ""
        var videoId = ""
        var title = ""
        var authorName = fallbackChannelName
        var published = ""
        var thumbnail = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.orEmpty()
                    when (currentTag) {
                        "entry" -> {
                            insideEntry = true
                            videoId = ""
                            title = ""
                            authorName = fallbackChannelName
                            published = ""
                            thumbnail = ""
                        }
                        "author" -> insideAuthor = true
                        "media:thumbnail" -> {
                            if (insideEntry) {
                                val thumbUrl = parser.getAttributeValue(null, "url").orEmpty()
                                if (thumbUrl.isNotBlank()) thumbnail = thumbUrl
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.orEmpty().trim()
                    if (insideEntry && text.isNotEmpty()) {
                        when (currentTag) {
                            "yt:videoId" -> videoId = text
                            "title" -> title = text
                            "published" -> published = text.take(10)
                            "name" -> if (insideAuthor) authorName = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val endTag = parser.name.orEmpty()
                    if (endTag == "author") insideAuthor = false
                    if (endTag == "entry" && insideEntry) {
                        if (videoId.isNotBlank() && title.isNotBlank()) {
                            val lightThumb = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
                            items.add(
                                VideoItem(
                                    id = videoId,
                                    title = title,
                                    channelTitle = authorName,
                                    channelId = channelId,
                                    category = category,
                                    publishedLabel = published.ifBlank { "Recent" },
                                    thumbnailUrl = lightThumb.ifBlank { thumbnail }
                                )
                            )
                        }
                        insideEntry = false
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }
        return items.take(8)
    }
}
