package com.example.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.R
import com.example.data.VideoRepository
import com.example.data.model.DeviceTelemetry
import com.example.data.model.LegacyTuningSettings
import com.example.data.model.RamBufferProfile
import com.example.data.model.ResolutionCap
import com.example.data.model.VideoItem

class TuningTabView(
    private val context: Context,
    private val fonts: TabTubeFonts,
    private val onUpdateSettings: ((LegacyTuningSettings) -> LegacyTuningSettings) -> Unit,
    private val onApplyTab2Preset: () -> Unit,
    private val onApplyStandardPreset: () -> Unit,
    private val onRefreshTelemetry: () -> Unit,
    private val onLaunchBenchmark: (VideoItem) -> Unit
) {
    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(24))
    }

    val rootView = ScrollView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        addView(container)
    }

    fun render(settings: LegacyTuningSettings, telemetry: DeviceTelemetry) {
        container.removeAllViews()

        // Hero Banner + Presets Card
        val heroCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_DARK, TabTubeColors.BORDER_SUBTLE, 1, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, context.dp(14)) }
        }

        val bannerFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(136)
            )
        }
        bannerFrame.addView(ImageView(context).apply {
            setImageResource(R.drawable.img_tab2_banner_1795098746189)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Galaxy Tab 2 hardware-optimalisatie banner"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })
        val overlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(0x880D0F14.toInt())
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        overlay.addView(TextView(context).apply {
            text = if (settings.isGalaxyTab2Preset) "GALAXY TAB 2 PROFIEL ACTIEF" else "AANGEPAST PROFIEL"
            typeface = fonts.mono
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(TabTubeColors.PHOSPHOR_TEAL)
            setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.PHOSPHOR_TEAL_CONTAINER, TabTubeColors.PHOSPHOR_TEAL, 1, 6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        overlay.addView(TextView(context).apply {
            text = "SmartTube Legacy Hardware-Engine"
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setPadding(0, context.dp(6), 0, 0)
        })
        bannerFrame.addView(overlay)
        heroCard.addView(bannerFrame)

        val heroBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(14))
        }
        heroBody.addView(TextView(context).apply {
            text = "Speciaal gebouwd voor oudere Android 4.1+ tablets met 1 GB RAM en dual-core CPU (zoals Samsung Galaxy Tab 2)."
            typeface = fonts.body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(TabTubeColors.TEXT_SECONDARY)
        })
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(12), 0, 0)
        }
        presetRow.addView(Button(context).apply {
            text = "⚡ Tab 2 (1GB RAM)"
            typeface = fonts.heading
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SIGNAL_RED, TabTubeColors.SIGNAL_RED, 0, 8))
            layoutParams = LinearLayout.LayoutParams(0, context.dp(48), 1f).apply {
                setMargins(0, 0, context.dp(8), 0)
            }
            setOnClickListener { onApplyTab2Preset() }
        })
        presetRow.addView(Button(context).apply {
            text = "Standaard Tablet"
            typeface = fonts.body
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setCompatBackground(context.pillButtonDrawable(false))
            layoutParams = LinearLayout.LayoutParams(0, context.dp(48), 1f)
            setOnClickListener { onApplyStandardPreset() }
        })
        heroBody.addView(presetRow)
        heroCard.addView(heroBody)
        container.addView(heroCard)

        // Live Telemetry Card
        val teleCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_DARK, TabTubeColors.BORDER_SUBTLE, 1, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, context.dp(14)) }
        }
        val teleHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        teleHeader.addView(TextView(context).apply {
            text = "Live Apparaat & Codec Telemetrie"
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        teleHeader.addView(Button(context).apply {
            text = "↻"
            setTextColor(TabTubeColors.PHOSPHOR_TEAL)
            setCompatBackground(context.pillButtonDrawable(false))
            layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
            setOnClickListener { onRefreshTelemetry() }
        })
        teleCard.addView(teleHeader)
        teleCard.addView(telemetryRow("Systeem RAM", "${telemetry.availableRamMb} MB vrij / ${telemetry.totalRamMb} MB"))
        teleCard.addView(telemetryRow("App JVM Heap", "${telemetry.appHeapUsedMb} MB / ${telemetry.appHeapMaxMb} MB"))
        teleCard.addView(telemetryRow("OS & CPU ABI", "${telemetry.androidVersionLabel} • ${telemetry.cpuAbi}"))
        teleCard.addView(
            telemetryRow(
                "VP9 Hardware",
                if (telemetry.supportsVp9Hardware) "Ondersteund" else "Geen (software VP9 vermeden)"
            )
        )
        teleCard.addView(
            telemetryRow(
                "H.264 HW Decoders",
                if (telemetry.h264HardwareDecoders.isEmpty()) "Systeem/OMX standaard"
                else telemetry.h264HardwareDecoders.joinToString(", ")
            )
        )
        teleCard.addView(Button(context).apply {
            text = "▶ Test Hardware Player (H.264 MP4 Benchmark)"
            typeface = fonts.heading
            setTextColor(TabTubeColors.PHOSPHOR_TEAL)
            setCompatBackground(context.pillButtonDrawable(true))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(48)
            ).apply { setMargins(0, context.dp(10), 0, 0) }
            setOnClickListener { onLaunchBenchmark(VideoRepository.HARDWARE_BENCHMARK_VIDEO) }
        })
        container.addView(teleCard)

        // Resolution & Cache Selector Card
        val capCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_DARK, TabTubeColors.BORDER_SUBTLE, 1, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, context.dp(14)) }
        }
        capCard.addView(TextView(context).apply {
            text = "Maximale Video-Resolutie"
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
        })
        val resRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(8), 0, context.dp(12))
        }
        for (cap in ResolutionCap.entries) {
            val selected = settings.resolutionCap == cap
            resRow.addView(Button(context).apply {
                text = "${cap.shortLabel} (${cap.description})"
                setTextColor(if (selected) TabTubeColors.PHOSPHOR_TEAL else TabTubeColors.TEXT_SECONDARY)
                setCompatBackground(context.pillButtonDrawable(selected))
                setPadding(context.dp(12), 0, context.dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    context.dp(48)
                ).apply { setMargins(0, 0, context.dp(8), 0) }
                setOnClickListener { onUpdateSettings { it.copy(resolutionCap = cap) } }
            })
        }
        capCard.addView(HorizontalScrollView(context).apply { addView(resRow) })

        capCard.addView(TextView(context).apply {
            text = "Thumbnail & Geheugencache Limiet"
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
        })
        val ramRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(8), 0, 0)
        }
        for (profile in RamBufferProfile.entries) {
            val selected = settings.ramBufferProfile == profile
            ramRow.addView(Button(context).apply {
                text = profile.shortLabel
                setTextColor(if (selected) TabTubeColors.PHOSPHOR_TEAL else TabTubeColors.TEXT_SECONDARY)
                setCompatBackground(context.pillButtonDrawable(selected))
                setPadding(context.dp(12), 0, context.dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    context.dp(48)
                ).apply { setMargins(0, 0, context.dp(8), 0) }
                setOnClickListener { onUpdateSettings { it.copy(ramBufferProfile = profile) } }
            })
        }
        capCard.addView(HorizontalScrollView(context).apply { addView(ramRow) })
        container.addView(capCard)

        // Switches Card
        val switchesCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_DARK, TabTubeColors.BORDER_SUBTLE, 1, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, context.dp(14)) }
        }
        switchesCard.addView(TextView(context).apply {
            text = "Codec, Geheugen & SmartTube Opties"
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setPadding(0, 0, 0, context.dp(6))
        })
        switchesCard.addView(
            toggleRow(
                "Peutermodus (YouTube Kids 1–4 jaar)",
                "Geeft voorkeur aan peuterkanalen, kindvriendelijke zoekresultaten en filtert ongeschikte video's",
                settings.toddlerKidsMode
            ) { v -> onUpdateSettings { it.copy(toddlerKidsMode = v) } }
        )
        switchesCard.addView(
            toggleRow(
                "Forceer H.264 / AVC Hardware-Decoder",
                "Vermijdt trage software-decodering van VP9/AV1",
                settings.forceH264Avc
            ) { v -> onUpdateSettings { it.copy(forceH264Avc = v) } }
        )
        switchesCard.addView(
            toggleRow(
                "RGB_565 Thumbnails (50% minder RAM)",
                "16-bit thumbnails in plaats van 32-bit ARGB_8888",
                settings.rgb565Thumbnails
            ) { v -> onUpdateSettings { it.copy(rgb565Thumbnails = v) } }
        )
        switchesCard.addView(
            toggleRow(
                "Tekstmodus (geen thumbnails laden)",
                "Maximale snelheid op verouderde hardware",
                settings.textOnlyNoThumbnails
            ) { v -> onUpdateSettings { it.copy(textOnlyNoThumbnails = v) } }
        )
        switchesCard.addView(
            toggleRow(
                "Zero-Jank UI (geen schaduwen/animaties)",
                "Voorkomt frame-drops op PowerVR SGX540 GPU",
                settings.disableAnimations
            ) { v -> onUpdateSettings { it.copy(disableAnimations = v) } }
        )
        switchesCard.addView(
            toggleRow(
                "SponsorBlock Automatisch Overslaan",
                "Slaat sponsors, intro's en outro's over via sponsor.ajay.app",
                settings.sponsorBlockEnabled
            ) { v -> onUpdateSettings { it.copy(sponsorBlockEnabled = v) } }
        )
        switchesCard.addView(
            toggleRow(
                "Alleen Audio Modus",
                "Schakelt videodecoder uit voor vloeiende audio/podcasts",
                settings.audioOnlyMode
            ) { v -> onUpdateSettings { it.copy(audioOnlyMode = v) } }
        )
        container.addView(switchesCard)

        // Piped Instance Card
        val pipedCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_DARK, TabTubeColors.BORDER_SUBTLE, 1, 14))
        }
        pipedCard.addView(TextView(context).apply {
            text = "Piped API Server"
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
        })
        val pipedEdit = EditText(context).apply {
            setText(settings.pipedApiBaseUrl)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            typeface = fonts.mono
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_ELEVATED, TabTubeColors.BORDER_SUBTLE, 1, 8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, context.dp(8), 0, context.dp(8)) }
        }
        pipedCard.addView(pipedEdit)
        pipedCard.addView(Button(context).apply {
            text = "Server opslaan"
            setTextColor(TabTubeColors.PHOSPHOR_TEAL)
            setCompatBackground(context.pillButtonDrawable(false))
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            setOnClickListener {
                val url = pipedEdit.text.toString().trim()
                if (url.startsWith("https://")) {
                    onUpdateSettings { it.copy(pipedApiBaseUrl = url) }
                }
            }
        })
        container.addView(pipedCard)
    }

    private fun telemetryRow(label: String, value: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(3), 0, context.dp(3))
            addView(TextView(context).apply {
                text = label
                typeface = fonts.body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(TabTubeColors.TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f)
            })
            addView(TextView(context).apply {
                text = value
                typeface = fonts.mono
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(TabTubeColors.TEXT_PRIMARY)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f)
            })
        }
    }

    private fun toggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(6), 0, context.dp(6))
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(context).apply {
            text = title
            typeface = fonts.body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
        })
        textCol.addView(TextView(context).apply {
            text = subtitle
            typeface = fonts.body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(TabTubeColors.TEXT_SECONDARY)
        })
        val cb = CheckBox(context).apply {
            isChecked = checked
            layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        row.addView(textCol)
        row.addView(cb)
        return row
    }
}
