package com.example.data.model

enum class AppLanguage(
    val code: String,
    val regionCode: String,
    val shortLabel: String,
    val displayName: String
) {
    DUTCH("nl", "NL", "NL", "Nederlands"),
    ENGLISH("en", "US", "EN", "English")
}

enum class ResolutionCap(
    val maxWidth: Int,
    val maxHeight: Int,
    val shortLabel: String,
    val description: String
) {
    SD_360P(640, 360, "360p", "Ultra-licht voor dual-core CPU"),
    SD_480P(854, 480, "480p", "Aanbevolen voor Galaxy Tab 2"),
    HD_720P(1280, 720, "720p", "Alleen indien H.264 HW ondersteund");

    fun localizedDescription(lang: AppLanguage): String = when (lang) {
        AppLanguage.DUTCH -> description
        AppLanguage.ENGLISH -> when (this) {
            SD_360P -> "Ultra-light for dual-core CPU"
            SD_480P -> "Recommended for Galaxy Tab 2"
            HD_720P -> "Only if H.264 HW supported"
        }
    }
}

enum class RamBufferProfile(
    val cacheMb: Int,
    val shortLabel: String,
    val description: String
) {
    TAB2_4MB(4, "4 MB (Tab 2)", "Minimaal geheugen voor 1 GB RAM"),
    BALANCED_8MB(8, "8 MB", "Gebalanceerd voor oudere tablets"),
    STANDARD_16MB(16, "16 MB", "Standaard thumbnail- & netwerkcache");

    fun localizedDescription(lang: AppLanguage): String = when (lang) {
        AppLanguage.DUTCH -> description
        AppLanguage.ENGLISH -> when (this) {
            TAB2_4MB -> "Minimal memory for 1 GB RAM"
            BALANCED_8MB -> "Balanced for older tablets"
            STANDARD_16MB -> "Standard thumbnail & network cache"
        }
    }
}

data class LegacyTuningSettings(
    val resolutionCap: ResolutionCap = ResolutionCap.SD_480P,
    val forceH264Avc: Boolean = true,
    val ramBufferProfile: RamBufferProfile = RamBufferProfile.TAB2_4MB,
    val rgb565Thumbnails: Boolean = true,
    val textOnlyNoThumbnails: Boolean = false,
    val disableAnimations: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val audioOnlyMode: Boolean = false,
    val toddlerKidsMode: Boolean = false,
    val appLanguage: AppLanguage = AppLanguage.DUTCH,
    val pipedApiBaseUrl: String = "https://pipedapi.kavin.rocks"
) {
    val isGalaxyTab2Preset: Boolean
        get() = resolutionCap == ResolutionCap.SD_480P &&
            forceH264Avc &&
            ramBufferProfile == RamBufferProfile.TAB2_4MB &&
            rgb565Thumbnails &&
            disableAnimations
}

data class VideoItem(
    val id: String,
    val title: String,
    val channelTitle: String,
    val channelId: String,
    val category: String,
    val publishedLabel: String,
    val thumbnailUrl: String,
    val directStreamUrl: String? = null,
    val durationLabel: String? = null
)

data class ChannelSubscription(
    val channelId: String,
    val name: String,
    val category: String,
    val addedAt: Long
)

data class WatchHistoryEntry(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val channelId: String,
    val thumbnailUrl: String,
    val directStreamUrl: String?,
    val lastPositionMs: Long,
    val durationMs: Long,
    val watchedAt: Long
)

data class BookmarkEntry(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val channelId: String,
    val thumbnailUrl: String,
    val directStreamUrl: String?,
    val savedAt: Long
)

data class SponsorSegment(
    val uuid: String,
    val category: String,
    val startSec: Float,
    val endSec: Float
)

data class ResolvedStream(
    val streamUrl: String,
    val audioOnlyUrl: String?,
    val isHls: Boolean,
    val qualityLabel: String,
    val codecLabel: String,
    val sourceHost: String
)

data class DeviceTelemetry(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val appHeapUsedMb: Long,
    val appHeapMaxMb: Long,
    val androidVersionLabel: String,
    val cpuAbi: String,
    val isLowRamClass: Boolean,
    val h264HardwareDecoders: List<String>,
    val supportsVp9Hardware: Boolean
)
