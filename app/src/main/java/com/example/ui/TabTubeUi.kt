package com.example.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.data.model.SponsorSegment
import com.example.data.model.VideoItem
import com.example.engine.LegacyHardwareEngine

object TabTubeColors {
    const val OBSIDIAN_BG = 0xFF0D0F14.toInt()
    const val SURFACE_DARK = 0xFF161922.toInt()
    const val SURFACE_ELEVATED = 0xFF1E2330.toInt()
    const val BORDER_SUBTLE = 0xFF2D3548.toInt()

    const val SIGNAL_RED = 0xFFFF3B30.toInt()
    const val SIGNAL_RED_CONTAINER = 0xFF421414.toInt()
    const val PHOSPHOR_TEAL = 0xFF2DD4BF.toInt()
    const val PHOSPHOR_TEAL_CONTAINER = 0xFF0F3834.toInt()
    const val SPONSOR_GREEN = 0xFF22C55E.toInt()
    const val AMBER_SPONSOR = 0xFFF59E0B.toInt()

    const val TEXT_PRIMARY = 0xFFF3F4F6.toInt()
    const val TEXT_SECONDARY = 0xFF9CA3AF.toInt()
}

class TabTubeFonts(context: Context) {
    val heading: Typeface = runCatching {
        Typeface.createFromAsset(context.assets, "fonts/space_grotesk.ttf")
    }.getOrDefault(Typeface.DEFAULT_BOLD)

    val body: Typeface = runCatching {
        Typeface.createFromAsset(context.assets, "fonts/inter.ttf")
    }.getOrDefault(Typeface.SANS_SERIF)

    val mono: Typeface = runCatching {
        Typeface.createFromAsset(context.assets, "fonts/jetbrains_mono.ttf")
    }.getOrDefault(Typeface.MONOSPACE)
}

fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

fun Context.roundedBoxDrawable(
    fillColor: Int,
    strokeColor: Int = TabTubeColors.BORDER_SUBTLE,
    strokeWidthDp: Int = 1,
    radiusDp: Int = 12
): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fillColor)
        setStroke(dp(strokeWidthDp), strokeColor)
    }
}

fun Context.focusableCardDrawable(
    fillColor: Int = TabTubeColors.SURFACE_DARK,
    normalStroke: Int = TabTubeColors.BORDER_SUBTLE,
    focusStroke: Int = TabTubeColors.SIGNAL_RED,
    radiusDp: Int = 12
): Drawable {
    val focused = roundedBoxDrawable(fillColor, focusStroke, 2, radiusDp)
    val pressed = roundedBoxDrawable(TabTubeColors.SURFACE_ELEVATED, focusStroke, 2, radiusDp)
    val normal = roundedBoxDrawable(fillColor, normalStroke, 1, radiusDp)
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), normal)
    }
}

fun Context.pillButtonDrawable(selected: Boolean): Drawable {
    return if (selected) {
        roundedBoxDrawable(
            fillColor = TabTubeColors.PHOSPHOR_TEAL_CONTAINER,
            strokeColor = TabTubeColors.PHOSPHOR_TEAL,
            strokeWidthDp = 1,
            radiusDp = 8
        )
    } else {
        focusableCardDrawable(
            fillColor = TabTubeColors.SURFACE_DARK,
            normalStroke = TabTubeColors.BORDER_SUBTLE,
            focusStroke = TabTubeColors.PHOSPHOR_TEAL,
            radiusDp = 8
        )
    }
}

@Suppress("DEPRECATION")
fun View.setCompatBackground(drawable: Drawable?) {
    if (Build.VERSION.SDK_INT >= 16) {
        background = drawable
    } else {
        setBackgroundDrawable(drawable)
    }
}

fun buildVideoCardView(
    context: Context,
    fonts: TabTubeFonts,
    video: VideoItem,
    isBookmarked: Boolean,
    textOnlyMode: Boolean,
    rgb565: Boolean,
    resumeLabel: String?,
    strings: AppStrings = AppStrings.DUTCH,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit
): View {
    val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        isClickable = true
        setCompatBackground(context.focusableCardDrawable())
        setOnClickListener { onClick() }
    }

    if (!textOnlyMode) {
        val thumbContainer = android.widget.FrameLayout(context).apply {
            setBackgroundColor(TabTubeColors.SURFACE_ELEVATED)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(146)
            )
        }

        val thumbView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = video.title
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        LegacyHardwareEngine.loadThumbnailInto(thumbView, video.thumbnailUrl, rgb565)
        thumbContainer.addView(thumbView)

        if (video.durationLabel != null) {
            val durBadge = TextView(context).apply {
                text = video.durationLabel
                typeface = fonts.mono
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(TabTubeColors.TEXT_PRIMARY)
                setPadding(context.dp(6), context.dp(3), context.dp(6), context.dp(3))
                setCompatBackground(context.roundedBoxDrawable(0xDE0D0F14.toInt(), TabTubeColors.BORDER_SUBTLE, 1, 6))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply {
                    setMargins(0, 0, context.dp(8), context.dp(8))
                }
            }
            thumbContainer.addView(durBadge)
        }

        if (resumeLabel != null) {
            val resumeBadge = TextView(context).apply {
                text = resumeLabel
                typeface = fonts.mono
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(TabTubeColors.OBSIDIAN_BG)
                setPadding(context.dp(6), context.dp(3), context.dp(6), context.dp(3))
                setCompatBackground(context.roundedBoxDrawable(TabTubeColors.PHOSPHOR_TEAL, TabTubeColors.PHOSPHOR_TEAL, 0, 6))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                ).apply {
                    setMargins(context.dp(8), 0, 0, context.dp(8))
                }
            }
            thumbContainer.addView(resumeBadge)
        }

        card.addView(thumbContainer)
    }

    val footer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(context.dp(12), context.dp(10), context.dp(4), context.dp(10))
    }

    val textCol = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    val titleView = TextView(context).apply {
        text = video.title
        typeface = fonts.heading
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(TabTubeColors.TEXT_PRIMARY)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    textCol.addView(titleView)

    val metaView = TextView(context).apply {
        text = buildString {
            append(video.channelTitle)
            append(" • ")
            append(video.publishedLabel)
            if (textOnlyMode && resumeLabel != null) {
                append(" • ")
                append(resumeLabel)
            }
        }
        typeface = fonts.body
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(TabTubeColors.PHOSPHOR_TEAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, context.dp(4), 0, 0)
    }
    textCol.addView(metaView)

    val bookmarkBtn = ImageButton(context).apply {
        setImageResource(
            if (isBookmarked) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )
        contentDescription = if (isBookmarked) strings.cdRemoveBookmark else strings.cdSaveBookmark
        setCompatBackground(context.focusableCardDrawable(TabTubeColors.SURFACE_DARK, TabTubeColors.SURFACE_DARK, TabTubeColors.SIGNAL_RED, 8))
        layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
        setOnClickListener { onToggleBookmark() }
    }

    footer.addView(textCol)
    footer.addView(bookmarkBtn)
    card.addView(footer)
    return card
}

class SponsorTimelineOverlay(context: Context) : View(context) {
    private val sponsorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TabTubeColors.SPONSOR_GREEN
        style = Paint.Style.FILL
    }
    private val otherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TabTubeColors.AMBER_SPONSOR
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TabTubeColors.SURFACE_ELEVATED
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private var segments: List<SponsorSegment> = emptyList()
    private var durationMs: Long = 0L

    fun updateSegments(newSegments: List<SponsorSegment>, newDurationMs: Long) {
        segments = newSegments
        durationMs = newDurationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)

        if (durationMs <= 0L || segments.isEmpty()) return
        val durSec = durationMs / 1000f
        for (seg in segments) {
            val startX = ((seg.startSec / durSec).coerceIn(0f, 1f)) * w
            val endX = ((seg.endSec / durSec).coerceIn(0f, 1f)) * w
            val paint = if (seg.category == "sponsor") sponsorPaint else otherPaint
            rect.set(startX, 0f, maxOf(endX, startX + 6f), h)
            canvas.drawRect(rect, paint)
        }
    }
}

fun buildSeekbarWithSponsors(
    context: Context,
    onUserSeek: (Int) -> Unit
): Pair<LinearLayout, Pair<SeekBar, SponsorTimelineOverlay>> {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val timelineOverlay = SponsorTimelineOverlay(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(6)
        ).apply {
            setMargins(context.dp(12), context.dp(4), context.dp(12), context.dp(2))
        }
    }

    val seekBar = SeekBar(context).apply {
        max = 1000
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onUserSeek(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    container.addView(timelineOverlay)
    container.addView(seekBar)
    return container to (seekBar to timelineOverlay)
}
