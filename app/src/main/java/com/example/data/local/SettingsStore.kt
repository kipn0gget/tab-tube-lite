package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.LegacyTuningSettings
import com.example.data.model.RamBufferProfile
import com.example.data.model.ResolutionCap

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("tabtube_legacy_tuning", Context.MODE_PRIVATE)

    fun load(): LegacyTuningSettings {
        val resName = prefs.getString(KEY_RES_CAP, ResolutionCap.SD_480P.name)
        val resCap = resName?.let { runCatching { ResolutionCap.valueOf(it) }.getOrNull() }
            ?: ResolutionCap.SD_480P

        val ramName = prefs.getString(KEY_RAM_BUFFER, RamBufferProfile.TAB2_4MB.name)
        val ramProfile = ramName?.let { runCatching { RamBufferProfile.valueOf(it) }.getOrNull() }
            ?: RamBufferProfile.TAB2_4MB

        return LegacyTuningSettings(
            resolutionCap = resCap,
            forceH264Avc = prefs.getBoolean(KEY_FORCE_H264, true),
            ramBufferProfile = ramProfile,
            rgb565Thumbnails = prefs.getBoolean(KEY_RGB565, true),
            textOnlyNoThumbnails = prefs.getBoolean(KEY_TEXT_ONLY, false),
            disableAnimations = prefs.getBoolean(KEY_NO_ANIM, true),
            sponsorBlockEnabled = prefs.getBoolean(KEY_SPONSORBLOCK, true),
            audioOnlyMode = prefs.getBoolean(KEY_AUDIO_ONLY, false),
            toddlerKidsMode = prefs.getBoolean(KEY_TODDLER_KIDS_MODE, true),
            pipedApiBaseUrl = prefs.getString(KEY_PIPED_URL, "https://pipedapi.kavin.rocks")
                ?: "https://pipedapi.kavin.rocks"
        )
    }

    fun update(transform: (LegacyTuningSettings) -> LegacyTuningSettings): LegacyTuningSettings {
        val next = transform(load())
        prefs.edit()
            .putString(KEY_RES_CAP, next.resolutionCap.name)
            .putBoolean(KEY_FORCE_H264, next.forceH264Avc)
            .putString(KEY_RAM_BUFFER, next.ramBufferProfile.name)
            .putBoolean(KEY_RGB565, next.rgb565Thumbnails)
            .putBoolean(KEY_TEXT_ONLY, next.textOnlyNoThumbnails)
            .putBoolean(KEY_NO_ANIM, next.disableAnimations)
            .putBoolean(KEY_SPONSORBLOCK, next.sponsorBlockEnabled)
            .putBoolean(KEY_AUDIO_ONLY, next.audioOnlyMode)
            .putBoolean(KEY_TODDLER_KIDS_MODE, next.toddlerKidsMode)
            .putString(KEY_PIPED_URL, next.pipedApiBaseUrl)
            .apply()
        return next
    }

    fun applyGalaxyTab2Preset(): LegacyTuningSettings {
        return update {
            it.copy(
                resolutionCap = ResolutionCap.SD_480P,
                forceH264Avc = true,
                ramBufferProfile = RamBufferProfile.TAB2_4MB,
                rgb565Thumbnails = true,
                textOnlyNoThumbnails = false,
                disableAnimations = true,
                sponsorBlockEnabled = true
            )
        }
    }

    fun applyStandardTabletPreset(): LegacyTuningSettings {
        return update {
            it.copy(
                resolutionCap = ResolutionCap.HD_720P,
                forceH264Avc = false,
                ramBufferProfile = RamBufferProfile.STANDARD_16MB,
                rgb565Thumbnails = false,
                textOnlyNoThumbnails = false,
                disableAnimations = false,
                sponsorBlockEnabled = true
            )
        }
    }

    private companion object {
        const val KEY_RES_CAP = "resolution_cap"
        const val KEY_FORCE_H264 = "force_h264"
        const val KEY_RAM_BUFFER = "ram_buffer"
        const val KEY_RGB565 = "rgb565_thumbs"
        const val KEY_TEXT_ONLY = "text_only"
        const val KEY_NO_ANIM = "disable_animations"
        const val KEY_SPONSORBLOCK = "sponsor_block"
        const val KEY_AUDIO_ONLY = "audio_only"
        const val KEY_TODDLER_KIDS_MODE = "toddler_kids_mode"
        const val KEY_PIPED_URL = "piped_base_url"
    }
}
