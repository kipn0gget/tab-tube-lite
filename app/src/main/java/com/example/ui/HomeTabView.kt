package com.example.ui

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.example.data.VideoRepository
import com.example.data.model.LegacyTuningSettings
import com.example.data.model.VideoItem
import com.example.data.remote.KidsContentFilter

class HomeTabView(
    private val context: Context,
    private val fonts: TabTubeFonts,
    private val onSearch: (String) -> Unit,
    private val onClearSearch: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onSelectCategory: (String) -> Unit,
    private val onOpenVideo: (VideoItem) -> Unit,
    private val onToggleBookmark: (VideoItem) -> Unit
) {
    val rootView: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private val searchInput = EditText(context).apply {
        hint = "Zoek video, ID of YouTube-URL…"
        setHintTextColor(TabTubeColors.TEXT_SECONDARY)
        setTextColor(TabTubeColors.TEXT_PRIMARY)
        typeface = fonts.body
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        setCompatBackground(context.focusableCardDrawable(TabTubeColors.SURFACE_DARK, TabTubeColors.BORDER_SUBTLE, TabTubeColors.SIGNAL_RED, 10))
        layoutParams = LinearLayout.LayoutParams(0, context.dp(48), 1f)
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onSearch(text.toString())
                true
            } else {
                false
            }
        }
    }

    private val categoryRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(context.dp(14), context.dp(2), context.dp(14), context.dp(6))
    }

    private val toddlerTopicRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(context.dp(14), 0, context.dp(14), context.dp(8))
    }

    private lateinit var toddlerTopicScroll: HorizontalScrollView

    private val statusBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(6)
        ).apply {
            setMargins(context.dp(14), 0, context.dp(14), context.dp(6))
        }
    }

    private val gridContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(14), context.dp(4), context.dp(14), context.dp(18))
    }

    private lateinit var searchBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var refreshBtn: Button

    init {
        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(8))
        }

        searchBtn = Button(context).apply {
            text = "Zoek"
            typeface = fonts.heading
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SIGNAL_RED, TabTubeColors.SIGNAL_RED, 0, 10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(48)
            ).apply {
                setMargins(context.dp(8), 0, 0, 0)
            }
            setPadding(context.dp(14), 0, context.dp(14), 0)
            setOnClickListener { onSearch(searchInput.text.toString()) }
        }

        clearBtn = Button(context).apply {
            text = "Wis"
            typeface = fonts.body
            setTextColor(TabTubeColors.TEXT_SECONDARY)
            setCompatBackground(context.pillButtonDrawable(false))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(48)
            ).apply {
                setMargins(context.dp(6), 0, 0, 0)
            }
            setOnClickListener {
                searchInput.setText("")
                onClearSearch()
            }
        }

        refreshBtn = Button(context).apply {
            text = "↻"
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(TabTubeColors.PHOSPHOR_TEAL)
            setCompatBackground(context.pillButtonDrawable(false))
            layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
                setMargins(context.dp(6), 0, 0, 0)
            }
            contentDescription = "Vernieuw feed"
            setOnClickListener { onRefresh() }
        }

        searchRow.addView(searchInput)
        searchRow.addView(searchBtn)
        searchRow.addView(clearBtn)
        searchRow.addView(refreshBtn)
        rootView.addView(searchRow)

        val categoryScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryRow)
        }
        rootView.addView(categoryScroll)

        toddlerTopicScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(toddlerTopicRow)
        }
        rootView.addView(toddlerTopicScroll)
        rootView.addView(statusBar)

        val feedScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(gridContainer)
        }
        rootView.addView(feedScroll)
    }

    fun render(
        isLoading: Boolean,
        videos: List<VideoItem>,
        errorMessage: String?,
        categories: List<String>,
        selectedCategory: String,
        bookmarkIds: Set<String>,
        settings: LegacyTuningSettings,
        isSearchMode: Boolean
    ) {
        val strings = AppStrings.of(settings.appLanguage)
        statusBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        searchBtn.text = strings.btnSearch
        clearBtn.text = strings.btnClear
        refreshBtn.contentDescription = strings.cdRefreshFeed
        searchInput.hint = if (settings.toddlerKidsMode) {
            strings.searchHintToddler
        } else {
            strings.searchHintStandard
        }

        categoryRow.removeAllViews()
        for (cat in categories) {
            val selected = cat == selectedCategory
            val label = if (cat == ALL_CATEGORY_KEY) strings.categoryAll else strings.formatCategory(cat)
            val chip = TextView(context).apply {
                text = label
                typeface = fonts.body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (selected) TabTubeColors.PHOSPHOR_TEAL else TabTubeColors.TEXT_SECONDARY)
                setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
                setCompatBackground(context.pillButtonDrawable(selected))
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, context.dp(8), 0)
                }
                setOnClickListener { onSelectCategory(cat) }
            }
            categoryRow.addView(chip)
        }

        toddlerTopicRow.removeAllViews()
        if (settings.toddlerKidsMode) {
            toddlerTopicScroll.visibility = View.VISIBLE
            toddlerTopicRow.addView(TextView(context).apply {
                text = strings.toddlerChipLabel
                typeface = fonts.mono
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(TabTubeColors.AMBER_SPONSOR)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(8), context.dp(8), context.dp(8))
            })
            val activeQuery = searchInput.text.toString().trim()
            for (topic in KidsContentFilter.QUICK_TODDLER_TOPICS) {
                val selected = isSearchMode && activeQuery.equals(topic, ignoreCase = true)
                toddlerTopicRow.addView(TextView(context).apply {
                    text = topic
                    typeface = fonts.body
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(if (selected) TabTubeColors.OBSIDIAN_BG else TabTubeColors.TEXT_PRIMARY)
                    setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
                    setCompatBackground(
                        if (selected) {
                            context.roundedBoxDrawable(TabTubeColors.AMBER_SPONSOR, TabTubeColors.AMBER_SPONSOR, 0, 8)
                        } else {
                            context.roundedBoxDrawable(TabTubeColors.SURFACE_ELEVATED, TabTubeColors.BORDER_SUBTLE, 1, 8)
                        }
                    )
                    isFocusable = true
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, context.dp(8), 0) }
                    setOnClickListener {
                        searchInput.setText(topic)
                        onSearch(topic)
                    }
                })
            }
        } else {
            toddlerTopicScroll.visibility = View.GONE
        }

        gridContainer.removeAllViews()

        if (isLoading && videos.isEmpty()) {
            val loadingText = TextView(context).apply {
                text = if (isSearchMode && settings.toddlerKidsMode) {
                    strings.loadingSearchToddler
                } else if (isSearchMode) {
                    strings.loadingSearchStandard
                } else {
                    strings.loadingFeed
                }
                typeface = fonts.body
                setTextColor(TabTubeColors.TEXT_SECONDARY)
                gravity = Gravity.CENTER
                setPadding(context.dp(16), context.dp(32), context.dp(16), context.dp(16))
            }
            gridContainer.addView(loadingText)
            return
        }

        if (errorMessage != null && videos.isEmpty()) {
            val errCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
                setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_DARK, TabTubeColors.BORDER_SUBTLE, 1, 14))
            }
            errCard.addView(TextView(context).apply {
                text = if (isSearchMode) strings.searchFailedTitle else strings.feedFailedTitle
                typeface = fonts.heading
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(TabTubeColors.TEXT_PRIMARY)
            })
            errCard.addView(TextView(context).apply {
                text = errorMessage
                typeface = fonts.body
                setTextColor(TabTubeColors.TEXT_SECONDARY)
                setPadding(0, context.dp(6), 0, context.dp(12))
            })
            val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            btnRow.addView(Button(context).apply {
                text = strings.btnRetry
                setTextColor(TabTubeColors.TEXT_PRIMARY)
                setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SIGNAL_RED, TabTubeColors.SIGNAL_RED, 0, 8))
                setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
                setOnClickListener { onRefresh() }
            })
            btnRow.addView(Button(context).apply {
                text = strings.btnBenchmark
                setTextColor(TabTubeColors.PHOSPHOR_TEAL)
                setCompatBackground(context.pillButtonDrawable(true))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(context.dp(10), 0, 0, 0) }
                setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
                setOnClickListener { onOpenVideo(VideoRepository.HARDWARE_BENCHMARK_VIDEO) }
            })
            errCard.addView(btnRow)
            gridContainer.addView(errCard)
            return
        }

        if (videos.isEmpty()) {
            gridContainer.addView(TextView(context).apply {
                text = if (isSearchMode) strings.emptySearch else strings.emptyCategory
                typeface = fonts.body
                setTextColor(TabTubeColors.TEXT_SECONDARY)
                setPadding(context.dp(12), context.dp(24), context.dp(12), context.dp(12))
            })
            return
        }

        val screenWidthDp = context.resources.configuration.screenWidthDp
        val cols = when {
            screenWidthDp >= 900 -> 3
            screenWidthDp >= 560 -> 2
            else -> 1
        }

        var idx = 0
        while (idx < videos.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, context.dp(12))
                }
            }

            for (c in 0 until cols) {
                if (idx < videos.size) {
                    val video = videos[idx]
                    val card = buildVideoCardView(
                        context = context,
                        fonts = fonts,
                        video = video,
                        isBookmarked = bookmarkIds.contains(video.id),
                        textOnlyMode = settings.textOnlyNoThumbnails,
                        rgb565 = settings.rgb565Thumbnails,
                        resumeLabel = null,
                        strings = strings,
                        onClick = { onOpenVideo(video) },
                        onToggleBookmark = { onToggleBookmark(video) }
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (c < cols - 1) setMargins(0, 0, context.dp(10), 0)
                        }
                    }
                    row.addView(card)
                    idx++
                } else {
                    val spacer = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    }
                    row.addView(spacer)
                }
            }
            gridContainer.addView(row)
        }
    }

    companion object {
        const val ALL_CATEGORY_KEY = "__ALL__"
    }
}
