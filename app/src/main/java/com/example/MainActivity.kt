package com.example

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.VideoRepository
import com.example.data.local.SettingsStore
import com.example.data.local.TabTubeDbHelper
import com.example.data.model.LegacyTuningSettings
import com.example.data.model.VideoItem
import com.example.data.remote.LegacyHttpClient
import com.example.engine.LegacyHardwareEngine
import com.example.ui.ChannelsTabView
import com.example.ui.HomeTabView
import com.example.ui.LibraryTabView
import com.example.ui.PlayerOverlayView
import com.example.ui.TabTubeColors
import com.example.ui.TabTubeFonts
import com.example.ui.TuningTabView
import com.example.ui.dp
import com.example.ui.pillButtonDrawable
import com.example.ui.roundedBoxDrawable
import com.example.ui.setCompatBackground

class MainActivity : Activity() {

    private enum class MainTab { HOME, CHANNELS, LIBRARY, TUNING }

    private lateinit var fonts: TabTubeFonts
    private lateinit var dbHelper: TabTubeDbHelper
    private lateinit var settingsStore: SettingsStore
    private lateinit var repository: VideoRepository

    private var currentTab = MainTab.HOME
    private var selectedCategory = "Alles"
    private var isSearchMode = false
    private var isLoadingFeed = true
    private var feedError: String? = null
    private var rawFeedVideos: List<VideoItem> = emptyList()
    private var settings: LegacyTuningSettings = LegacyTuningSettings()

    private lateinit var rootFrame: FrameLayout
    private lateinit var mainShell: LinearLayout
    private lateinit var headerBadge: TextView
    private lateinit var contentFrame: FrameLayout
    private lateinit var navBarContainer: LinearLayout

    private lateinit var homeTabView: HomeTabView
    private lateinit var channelsTabView: ChannelsTabView
    private lateinit var libraryTabView: LibraryTabView
    private lateinit var tuningTabView: TuningTabView
    private lateinit var playerOverlayView: PlayerOverlayView

    private var isPlayerVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LegacyHttpClient.init(this)
        fonts = TabTubeFonts(this)
        dbHelper = TabTubeDbHelper(this)
        settingsStore = SettingsStore(this)
        repository = VideoRepository(dbHelper)

        settings = settingsStore.load()
        LegacyHardwareEngine.configureCache(settings)
        repository.ensureDefaultSubscriptions()

        buildRootUi()
        setContentView(rootFrame)
        refreshFeed()
    }

    private fun buildRootUi() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(TabTubeColors.OBSIDIAN_BG)
            if (Build.VERSION.SDK_INT >= 20) {
                setOnApplyWindowInsetsListener { v, insets: WindowInsets ->
                    @Suppress("DEPRECATION")
                    v.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom
                    )
                    insets
                }
            }
        }

        mainShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top App Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(TabTubeColors.SURFACE_DARK)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        topBar.addView(TextView(this).apply {
            text = "▶ TabTube Lite"
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerBadge = TextView(this).apply {
            typeface = fonts.mono
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(TabTubeColors.PHOSPHOR_TEAL)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setCompatBackground(roundedBoxDrawable(TabTubeColors.PHOSPHOR_TEAL_CONTAINER, TabTubeColors.PHOSPHOR_TEAL, 1, 6))
        }
        topBar.addView(headerBadge)
        mainShell.addView(topBar)

        val isWideTablet = resources.configuration.screenWidthDp >= 600
        val bodyContainer = LinearLayout(this).apply {
            orientation = if (isWideTablet) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        navBarContainer = LinearLayout(this).apply {
            orientation = if (isWideTablet) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(TabTubeColors.SURFACE_DARK)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = if (isWideTablet) {
                LinearLayout.LayoutParams(dp(116), ViewGroup.LayoutParams.MATCH_PARENT)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        contentFrame = FrameLayout(this).apply {
            layoutParams = if (isWideTablet) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        }

        if (isWideTablet) {
            bodyContainer.addView(navBarContainer)
            bodyContainer.addView(contentFrame)
        } else {
            bodyContainer.addView(contentFrame)
            bodyContainer.addView(navBarContainer)
        }
        mainShell.addView(bodyContainer)

        // Instantiate Tab Views
        homeTabView = HomeTabView(
            context = this,
            fonts = fonts,
            onSearch = { query -> performSearch(query) },
            onClearSearch = {
                isSearchMode = false
                refreshFeed()
            },
            onRefresh = {
                isSearchMode = false
                refreshFeed()
            },
            onSelectCategory = { cat ->
                selectedCategory = cat
                renderActiveTab()
            },
            onOpenVideo = { video -> openPlayer(video) },
            onToggleBookmark = { video ->
                repository.toggleBookmark(video)
                renderActiveTab()
            }
        )

        channelsTabView = ChannelsTabView(
            context = this,
            fonts = fonts,
            onAddChannel = { cid, name, cat ->
                repository.addSubscription(cid, name, cat)
                refreshFeed()
                renderActiveTab()
            },
            onRemoveChannel = { cid ->
                repository.removeSubscription(cid)
                refreshFeed()
                renderActiveTab()
            },
            onSelectCategory = { cat ->
                selectedCategory = cat
                currentTab = MainTab.HOME
                renderActiveTab()
            }
        )

        libraryTabView = LibraryTabView(
            context = this,
            fonts = fonts,
            onOpenVideo = { video -> openPlayer(video) },
            onToggleBookmark = { video ->
                repository.toggleBookmark(video)
                renderActiveTab()
            },
            onClearHistory = {
                repository.clearHistory()
                renderActiveTab()
            }
        )

        tuningTabView = TuningTabView(
            context = this,
            fonts = fonts,
            onUpdateSettings = { transform ->
                val prevToddler = settings.toddlerKidsMode
                settings = settingsStore.update(transform)
                LegacyHardwareEngine.configureCache(settings)
                if (prevToddler != settings.toddlerKidsMode && !isSearchMode) {
                    refreshFeed()
                } else {
                    renderActiveTab()
                }
                settings
            },
            onApplyTab2Preset = {
                settings = settingsStore.applyGalaxyTab2Preset()
                LegacyHardwareEngine.configureCache(settings)
                renderActiveTab()
            },
            onApplyStandardPreset = {
                settings = settingsStore.applyStandardTabletPreset()
                LegacyHardwareEngine.configureCache(settings)
                renderActiveTab()
            },
            onRefreshTelemetry = { renderActiveTab() },
            onLaunchBenchmark = { video -> openPlayer(video) }
        )

        playerOverlayView = PlayerOverlayView(
            context = this,
            fonts = fonts,
            repository = repository,
            onToggleBookmark = { video ->
                val saved = repository.toggleBookmark(video)
                renderActiveTab()
                saved
            },
            onToggleAudioOnly = { audioOnly ->
                settings = settingsStore.update { it.copy(audioOnlyMode = audioOnly) }
                updateHeaderBadge()
            },
            onClose = { closePlayer() }
        )
        playerOverlayView.rootView.visibility = View.GONE

        contentFrame.addView(homeTabView.rootView)
        contentFrame.addView(channelsTabView.rootView)
        contentFrame.addView(libraryTabView.rootView)
        contentFrame.addView(tuningTabView.rootView)

        rootFrame.addView(mainShell)
        rootFrame.addView(playerOverlayView.rootView)

        renderActiveTab()
    }

    private fun refreshFeed() {
        isLoadingFeed = true
        feedError = null
        renderActiveTab()
        repository.loadSubscriptionsFeedAsync(settings) { result ->
            isLoadingFeed = false
            rawFeedVideos = result.videos
            feedError = if (result.videos.isEmpty()) result.warningMessage else null
            renderActiveTab()
        }
    }

    private fun performSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            isSearchMode = false
            refreshFeed()
            return
        }
        isLoadingFeed = true
        isSearchMode = true
        feedError = null
        renderActiveTab()

        repository.searchVideosAsync(trimmed, settings) { res ->
            isLoadingFeed = false
            res.fold(
                onSuccess = { list ->
                    rawFeedVideos = list
                    feedError = null
                },
                onFailure = { err ->
                    rawFeedVideos = emptyList()
                    feedError = err.message ?: "Zoeken mislukt"
                }
            )
            renderActiveTab()
        }
    }

    private fun openPlayer(video: VideoItem) {
        isPlayerVisible = true
        playerOverlayView.rootView.visibility = View.VISIBLE
        mainShell.visibility = View.GONE
        val isSaved = repository.getBookmarkIds().contains(video.id)
        playerOverlayView.openVideo(video, settings, isSaved)
    }

    private fun closePlayer() {
        playerOverlayView.releaseAll()
        playerOverlayView.rootView.visibility = View.GONE
        mainShell.visibility = View.VISIBLE
        isPlayerVisible = false
        renderActiveTab()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isPlayerVisible) {
            closePlayer()
            return
        }
        if (currentTab != MainTab.HOME) {
            currentTab = MainTab.HOME
            renderActiveTab()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        playerOverlayView.releaseAll()
        super.onDestroy()
    }

    private fun updateHeaderBadge() {
        val prefix = if (settings.toddlerKidsMode) "Peuter • " else ""
        headerBadge.text = "$prefix${settings.resolutionCap.shortLabel} • ${
            if (settings.forceH264Avc) "H.264" else "Auto"
        } • ${settings.ramBufferProfile.shortLabel}"
    }

    private fun renderNavBar() {
        val isWideTablet = resources.configuration.screenWidthDp >= 600
        navBarContainer.removeAllViews()
        val items = listOf(
            MainTab.HOME to "Home",
            MainTab.CHANNELS to "Kanalen",
            MainTab.LIBRARY to "Bibliotheek",
            MainTab.TUNING to "Tab 2 Tuning"
        )
        for ((tab, label) in items) {
            val selected = currentTab == tab
            val btn = Button(this).apply {
                text = label
                typeface = if (selected) fonts.heading else fonts.body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (selected) TabTubeColors.TEXT_PRIMARY else TabTubeColors.TEXT_SECONDARY)
                setCompatBackground(
                    if (selected) {
                        roundedBoxDrawable(TabTubeColors.SIGNAL_RED_CONTAINER, TabTubeColors.SIGNAL_RED, 1, 8)
                    } else {
                        pillButtonDrawable(false)
                    }
                )
                layoutParams = if (isWideTablet) {
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                    ).apply { setMargins(0, 0, 0, dp(8)) }
                } else {
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    }
                }
                setOnClickListener {
                    currentTab = tab
                    renderActiveTab()
                }
            }
            navBarContainer.addView(btn)
        }
    }

    private fun renderActiveTab() {
        updateHeaderBadge()
        renderNavBar()

        homeTabView.rootView.visibility = if (currentTab == MainTab.HOME) View.VISIBLE else View.GONE
        channelsTabView.rootView.visibility = if (currentTab == MainTab.CHANNELS) View.VISIBLE else View.GONE
        libraryTabView.rootView.visibility = if (currentTab == MainTab.LIBRARY) View.VISIBLE else View.GONE
        tuningTabView.rootView.visibility = if (currentTab == MainTab.TUNING) View.VISIBLE else View.GONE

        val subs = repository.getSubscriptions()
        val bookmarkIds = repository.getBookmarkIds()

        when (currentTab) {
            MainTab.HOME -> {
                val categories = listOf("Alles") + subs.map { it.category }.distinct()
                val visibleVideos = if (selectedCategory == "Alles" || isSearchMode) {
                    rawFeedVideos
                } else {
                    rawFeedVideos.filter { it.category.equals(selectedCategory, ignoreCase = true) }
                }
                homeTabView.render(
                    isLoading = isLoadingFeed,
                    videos = visibleVideos,
                    errorMessage = feedError,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    bookmarkIds = bookmarkIds,
                    settings = settings,
                    isSearchMode = isSearchMode
                )
            }
            MainTab.CHANNELS -> channelsTabView.render(subs)
            MainTab.LIBRARY -> libraryTabView.render(
                history = repository.getHistory(),
                bookmarks = repository.getBookmarks(),
                bookmarkIds = bookmarkIds,
                settings = settings
            )
            MainTab.TUNING -> tuningTabView.render(
                settings = settings,
                telemetry = LegacyHardwareEngine.collectDeviceTelemetry(this)
            )
        }
    }
}
