package com.example.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.data.model.BookmarkEntry
import com.example.data.model.LegacyTuningSettings
import com.example.data.model.VideoItem
import com.example.data.model.WatchHistoryEntry
import com.example.data.remote.PipedStreamResolver

class LibraryTabView(
    private val context: Context,
    private val fonts: TabTubeFonts,
    private val onOpenVideo: (VideoItem) -> Unit,
    private val onToggleBookmark: (VideoItem) -> Unit,
    private val onClearHistory: () -> Unit
) {
    private var showBookmarks = false

    val rootView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private val headerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(8))
    }

    private val listContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(20))
    }

    private var lastHistory: List<WatchHistoryEntry> = emptyList()
    private var lastBookmarks: List<BookmarkEntry> = emptyList()
    private var lastBookmarkIds: Set<String> = emptySet()
    private var lastSettings: LegacyTuningSettings = LegacyTuningSettings()

    init {
        rootView.addView(headerRow)
        rootView.addView(ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(listContainer)
        })
    }

    fun render(
        history: List<WatchHistoryEntry>,
        bookmarks: List<BookmarkEntry>,
        bookmarkIds: Set<String>,
        settings: LegacyTuningSettings
    ) {
        val strings = AppStrings.of(settings.appLanguage)
        lastHistory = history
        lastBookmarks = bookmarks
        lastBookmarkIds = bookmarkIds
        lastSettings = settings

        headerRow.removeAllViews()
        val historyBtn = Button(context).apply {
            text = String.format(strings.libHistoryFmt, history.size)
            typeface = fonts.body
            setTextColor(if (!showBookmarks) TabTubeColors.PHOSPHOR_TEAL else TabTubeColors.TEXT_SECONDARY)
            setCompatBackground(context.pillButtonDrawable(!showBookmarks))
            setPadding(context.dp(12), 0, context.dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(48)
            ).apply { setMargins(0, 0, context.dp(8), 0) }
            setOnClickListener {
                showBookmarks = false
                render(lastHistory, lastBookmarks, lastBookmarkIds, lastSettings)
            }
        }
        val bookmarkBtn = Button(context).apply {
            text = String.format(strings.libSavedFmt, bookmarks.size)
            typeface = fonts.body
            setTextColor(if (showBookmarks) TabTubeColors.PHOSPHOR_TEAL else TabTubeColors.TEXT_SECONDARY)
            setCompatBackground(context.pillButtonDrawable(showBookmarks))
            setPadding(context.dp(12), 0, context.dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(48)
            )
            setOnClickListener {
                showBookmarks = true
                render(lastHistory, lastBookmarks, lastBookmarkIds, lastSettings)
            }
        }
        headerRow.addView(historyBtn)
        headerRow.addView(bookmarkBtn)
        headerRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

        if (!showBookmarks && history.isNotEmpty()) {
            headerRow.addView(Button(context).apply {
                text = strings.libClear
                setTextColor(TabTubeColors.SIGNAL_RED)
                setCompatBackground(context.pillButtonDrawable(false))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(48))
                setOnClickListener { onClearHistory() }
            })
        }

        listContainer.removeAllViews()

        if (!showBookmarks) {
            if (history.isEmpty()) {
                listContainer.addView(emptyMessage(strings.libEmptyHistory))
                return
            }
            for (entry in history) {
                val video = VideoItem(
                    id = entry.videoId,
                    title = entry.title,
                    channelTitle = entry.channelTitle,
                    channelId = entry.channelId,
                    category = strings.libHistoryCat,
                    publishedLabel = strings.libWatched,
                    thumbnailUrl = entry.thumbnailUrl,
                    directStreamUrl = entry.directStreamUrl
                )
                val resumeLabel = if (entry.lastPositionMs > 3000L) {
                    "${strings.libResumePrefix}${PipedStreamResolver.formatDuration(entry.lastPositionMs / 1000L)}"
                } else {
                    null
                }
                listContainer.addView(
                    buildVideoCardView(
                        context = context,
                        fonts = fonts,
                        video = video,
                        isBookmarked = bookmarkIds.contains(video.id),
                        textOnlyMode = settings.textOnlyNoThumbnails,
                        rgb565 = settings.rgb565Thumbnails,
                        resumeLabel = resumeLabel,
                        strings = strings,
                        onClick = { onOpenVideo(video) },
                        onToggleBookmark = { onToggleBookmark(video) }
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, context.dp(10)) }
                    }
                )
            }
        } else {
            if (bookmarks.isEmpty()) {
                listContainer.addView(emptyMessage(strings.libEmptySaved))
                return
            }
            for (entry in bookmarks) {
                val video = VideoItem(
                    id = entry.videoId,
                    title = entry.title,
                    channelTitle = entry.channelTitle,
                    channelId = entry.channelId,
                    category = strings.libSavedCat,
                    publishedLabel = strings.libSavedLabel,
                    thumbnailUrl = entry.thumbnailUrl,
                    directStreamUrl = entry.directStreamUrl
                )
                listContainer.addView(
                    buildVideoCardView(
                        context = context,
                        fonts = fonts,
                        video = video,
                        isBookmarked = true,
                        textOnlyMode = settings.textOnlyNoThumbnails,
                        rgb565 = settings.rgb565Thumbnails,
                        resumeLabel = null,
                        strings = strings,
                        onClick = { onOpenVideo(video) },
                        onToggleBookmark = { onToggleBookmark(video) }
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, context.dp(10)) }
                    }
                )
            }
        }
    }

    private fun emptyMessage(msg: String): TextView {
        return TextView(context).apply {
            text = msg
            typeface = fonts.body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(TabTubeColors.TEXT_SECONDARY)
            setPadding(context.dp(8), context.dp(24), context.dp(8), context.dp(12))
        }
    }
}
