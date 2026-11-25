package com.example.data

import android.os.Handler
import android.os.Looper
import com.example.data.local.TabTubeDbHelper
import com.example.data.model.BookmarkEntry
import com.example.data.model.ChannelSubscription
import com.example.data.model.LegacyTuningSettings
import com.example.data.model.ResolvedStream
import com.example.data.model.SponsorSegment
import com.example.data.model.VideoItem
import com.example.data.model.WatchHistoryEntry
import com.example.data.remote.KidsContentFilter
import com.example.data.remote.PipedStreamResolver
import com.example.data.remote.SponsorBlockService
import com.example.data.remote.YouTubeRssService
import java.util.concurrent.Executors

class VideoRepository(
    private val db: TabTubeDbHelper,
    private val rssService: YouTubeRssService = YouTubeRssService(),
    private val streamResolver: PipedStreamResolver = PipedStreamResolver(),
    private val sponsorBlockService: SponsorBlockService = SponsorBlockService()
) {
    private val ioExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun ensureDefaultSubscriptions() {
        if (db.getSubscriptionCount() == 0) {
            DEFAULT_CHANNELS.forEach { db.upsertSubscription(it) }
        }
    }

    fun getSubscriptions(): List<ChannelSubscription> = db.getSubscriptions()

    fun addSubscription(channelId: String, name: String, category: String) {
        val cleanId = channelId.trim()
        if (cleanId.isBlank()) return
        db.upsertSubscription(
            ChannelSubscription(
                channelId = cleanId,
                name = name.ifBlank { cleanId },
                category = category.ifBlank { "Kanaal" },
                addedAt = System.currentTimeMillis()
            )
        )
    }

    fun removeSubscription(channelId: String) {
        db.deleteSubscription(channelId)
    }

    fun getHistory(): List<WatchHistoryEntry> = db.getHistory()

    fun getBookmarks(): List<BookmarkEntry> = db.getBookmarks()

    fun getBookmarkIds(): Set<String> = db.getBookmarkIds()

    fun clearHistory() = db.clearHistory()

    fun getResumePosition(videoId: String): Long = db.getResumePosition(videoId)

    fun savePlaybackProgress(video: VideoItem, positionMs: Long, durationMs: Long) {
        db.upsertHistory(
            WatchHistoryEntry(
                videoId = video.id,
                title = video.title,
                channelTitle = video.channelTitle,
                channelId = video.channelId,
                thumbnailUrl = video.thumbnailUrl,
                directStreamUrl = video.directStreamUrl,
                lastPositionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                watchedAt = System.currentTimeMillis()
            )
        )
    }

    fun savePlaybackProgressAsync(video: VideoItem, positionMs: Long, durationMs: Long) {
        ioExecutor.execute {
            runCatching {
                savePlaybackProgress(video, positionMs, durationMs)
            }
        }
    }

    fun toggleBookmark(video: VideoItem): Boolean {
        val currentlySaved = db.getBookmarkIds().contains(video.id)
        if (currentlySaved) {
            db.deleteBookmark(video.id)
            return false
        } else {
            db.upsertBookmark(
                BookmarkEntry(
                    videoId = video.id,
                    title = video.title,
                    channelTitle = video.channelTitle,
                    channelId = video.channelId,
                    thumbnailUrl = video.thumbnailUrl,
                    directStreamUrl = video.directStreamUrl,
                    savedAt = System.currentTimeMillis()
                )
            )
            return true
        }
    }

    fun loadSubscriptionsFeedAsync(
        settings: LegacyTuningSettings = LegacyTuningSettings(),
        callback: (FeedResult) -> Unit
    ) {
        ioExecutor.execute {
            val result = try {
                ensureDefaultSubscriptions()
                val subs = db.getSubscriptions()
                val videos = mutableListOf<VideoItem>()
                var lastError: String? = null

                for (sub in subs) {
                    try {
                        val entries = rssService.fetchChannelFeed(
                            channelId = sub.channelId,
                            fallbackChannelName = sub.name,
                            category = sub.category
                        )
                        videos.addAll(entries)
                    } catch (e: Throwable) {
                        lastError = e.message
                    }
                }

                val sorted = videos.sortedByDescending { it.publishedLabel }
                val curated = KidsContentFilter.filterAndRankResults(
                    items = sorted,
                    toddlerMode = settings.toddlerKidsMode,
                    isSearch = false
                )
                FeedResult(
                    videos = curated,
                    warningMessage = if (curated.isEmpty()) lastError else null
                )
            } catch (t: Throwable) {
                FeedResult(videos = emptyList(), warningMessage = t.message ?: "Feed laden mislukt")
            }
            mainHandler.post { callback(result) }
        }
    }

    fun searchVideosAsync(
        query: String,
        settings: LegacyTuningSettings,
        callback: (Result<List<VideoItem>>) -> Unit
    ) {
        val directId = PipedStreamResolver.extractVideoId(query)
        if (directId != null) {
            callback(
                Result.success(
                    listOf(
                        VideoItem(
                            id = directId,
                            title = "Directe YouTube Video ($directId)",
                            channelTitle = "Directe URL / Video-ID",
                            channelId = "",
                            category = "Direct",
                            publishedLabel = "Nu",
                            thumbnailUrl = "https://i.ytimg.com/vi/$directId/mqdefault.jpg"
                        )
                    )
                )
            )
            return
        }

        val effectiveQuery = KidsContentFilter.enrichSearchQuery(query, settings.toddlerKidsMode)
        ioExecutor.execute {
            val res = try {
                streamResolver.searchVideos(
                    query = effectiveQuery,
                    baseUrl = settings.pipedApiBaseUrl,
                    hl = settings.appLanguage.code,
                    gl = settings.appLanguage.regionCode
                ).map { list ->
                    KidsContentFilter.filterAndRankResults(
                        items = list,
                        toddlerMode = settings.toddlerKidsMode,
                        isSearch = true
                    )
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
            mainHandler.post { callback(res) }
        }
    }

    fun loadPlayerResourcesAsync(
        video: VideoItem,
        settings: LegacyTuningSettings,
        callback: (PlayerLoadResult) -> Unit
    ) {
        ioExecutor.execute {
            val result = try {
                val resumeMs = runCatching { db.getResumePosition(video.id) }.getOrDefault(0L)
                val segments = if (settings.sponsorBlockEnabled) {
                    runCatching { sponsorBlockService.fetchSegments(video.id) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                val streamResult = if (!video.directStreamUrl.isNullOrBlank()) {
                    Result.success(
                        ResolvedStream(
                            streamUrl = video.directStreamUrl,
                            audioOnlyUrl = null,
                            isHls = video.directStreamUrl.endsWith(".m3u8", ignoreCase = true),
                            qualityLabel = settings.resolutionCap.shortLabel,
                            codecLabel = "H.264 AVC",
                            sourceHost = "Direct MP4"
                        )
                    )
                } else {
                    streamResolver.resolvePlayableStream(video.id, settings)
                }

                PlayerLoadResult(
                    streamResult = streamResult,
                    sponsorSegments = segments,
                    resumePositionMs = resumeMs
                )
            } catch (t: Throwable) {
                PlayerLoadResult(
                    streamResult = Result.failure(t),
                    sponsorSegments = emptyList(),
                    resumePositionMs = 0L
                )
            }

            mainHandler.post { callback(result) }
        }
    }

    data class FeedResult(
        val videos: List<VideoItem>,
        val warningMessage: String?
    )

    data class PlayerLoadResult(
        val streamResult: Result<ResolvedStream>,
        val sponsorSegments: List<SponsorSegment>,
        val resumePositionMs: Long
    )

    companion object {
        val DEFAULT_CHANNELS = listOf(
            ChannelSubscription("UC5I2hjZYiW9gZPVkvzM8_Cw", "Techmoan", "Tech & Retro", 1L),
            ChannelSubscription("UCLx053rWZxCiYWsBETgdKrQ", "LGR", "Tech & Retro", 2L),
            ChannelSubscription("UCsXVk37bltHxD1rDPwtNM8Q", "Kurzgesagt", "Wetenschap", 3L),
            ChannelSubscription("UCHnyfMqiRRG1u-2MsSQLbXA", "Veritasium", "Wetenschap", 4L),
            ChannelSubscription("UCLA_DiR1FfKNvjuUpBHmylQ", "NASA", "Ruimte", 5L),
            ChannelSubscription("UCBJycsmduvYEL83R_U4JriQ", "Marques Brownlee", "Tech & Retro", 6L)
        )

        val HARDWARE_BENCHMARK_VIDEO = VideoItem(
            id = "benchmark_bbb_h264",
            title = "Hardware Benchmark: Big Buck Bunny (H.264 MP4)",
            channelTitle = "Blender Foundation Open Movie",
            channelId = "open_movie",
            category = "Benchmark",
            publishedLabel = "Teststream",
            thumbnailUrl = "https://storage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg",
            directStreamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            durationLabel = "09:56"
        )
    }
}
