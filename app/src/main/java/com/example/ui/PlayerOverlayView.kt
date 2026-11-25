package com.example.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.example.data.VideoRepository
import com.example.data.model.LegacyTuningSettings
import com.example.data.model.ResolvedStream
import com.example.data.model.SponsorSegment
import com.example.data.model.VideoItem
import com.example.data.remote.LocalStreamProxy
import com.example.data.remote.PipedStreamResolver

class PlayerOverlayView(
    private val context: Context,
    private val fonts: TabTubeFonts,
    private val repository: VideoRepository,
    private val onToggleBookmark: (VideoItem) -> Boolean,
    private val onToggleAudioOnly: (Boolean) -> Unit,
    private val onClose: () -> Unit
) {
    val rootView: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(TabTubeColors.OBSIDIAN_BG)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentVideo: VideoItem? = null
    private var currentSettings: LegacyTuningSettings = LegacyTuningSettings()
    private var resolvedStream: ResolvedStream? = null
    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private val skippedIds = HashSet<String>()
    private var resumePositionMs: Long = 0L
    private var playbackSpeed: Float = 1.0f
    private var isFullscreen: Boolean = false

    private var mediaPlayer: MediaPlayer? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var isPrepared = false
    private var usingVlc = false
    private var pendingStreamUrl: String? = null
    private var activeStreamUrl: String? = null
    private var retriedViaProxy = false
    private val streamProxy = LocalStreamProxy()
    private lateinit var vlcController: VlcPlayerController

    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(TabTubeColors.SURFACE_DARK)
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
    }
    private val topTitle = TextView(context).apply {
        typeface = fonts.heading
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(TabTubeColors.TEXT_PRIMARY)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val topSubtitle = TextView(context).apply {
        typeface = fonts.mono
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(TabTubeColors.PHOSPHOR_TEAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val bookmarkBtn = Button(context).apply {
        setTextColor(TabTubeColors.TEXT_PRIMARY)
        setCompatBackground(context.pillButtonDrawable(false))
        layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
    }

    private val videoViewport = FrameLayout(context).apply {
        setBackgroundColor(0xFF000000.toInt())
        setCompatBackground(context.roundedBoxDrawable(0xFF000000.toInt(), TabTubeColors.BORDER_SUBTLE, 1, 10))
    }
    private val surfaceView = SurfaceView(context)
    private val loadingIndicator = ProgressBar(context).apply {
        isIndeterminate = true
    }
    private val skipNoticePill = TextView(context).apply {
        typeface = fonts.mono
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(TabTubeColors.OBSIDIAN_BG)
        setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
        setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SPONSOR_GREEN, TabTubeColors.SPONSOR_GREEN, 0, 8))
        visibility = View.GONE
    }
    private val audioBanner = TextView(context).apply {
        text = "Alleen Audio Modus Actief\nVideodecoder uitgeschakeld voor Tab 2"
        typeface = fonts.heading
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(TabTubeColors.PHOSPHOR_TEAL)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val errorCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        setCompatBackground(context.roundedBoxDrawable(0xEE161922.toInt(), TabTubeColors.SIGNAL_RED, 1, 12))
        visibility = View.GONE
    }
    private val errorLabel = TextView(context).apply {
        typeface = fonts.body
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(TabTubeColors.TEXT_PRIMARY)
        gravity = Gravity.CENTER
    }

    private var seekBarRef: SeekBar? = null
    private var timelineOverlayRef: SponsorTimelineOverlay? = null
    private val timeText = TextView(context).apply {
        typeface = fonts.mono
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(TabTubeColors.TEXT_SECONDARY)
    }
    private val playPauseBtn = Button(context).apply {
        text = "▶"
        typeface = fonts.heading
        setTextColor(TabTubeColors.TEXT_PRIMARY)
        setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SIGNAL_RED, TabTubeColors.SIGNAL_RED, 0, 8))
        layoutParams = LinearLayout.LayoutParams(context.dp(52), context.dp(48))
    }
    private val speedBtn = Button(context).apply {
        text = "1.0x"
        typeface = fonts.mono
        setTextColor(TabTubeColors.TEXT_PRIMARY)
        setCompatBackground(context.pillButtonDrawable(false))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            context.dp(48)
        )
    }
    private val quickActionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, context.dp(6), 0, 0)
    }
    private val controlsBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val detailsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
        setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_DARK, TabTubeColors.BORDER_SUBTLE, 1, 12))
    }
    private lateinit var bodyCol: LinearLayout

    private var tickCount = 0
    private val ticker = object : Runnable {
        override fun run() {
            if ((usingVlc && vlcController.isActive) || (mediaPlayer != null && isPrepared)) {
                val pos = activePositionMs()
                val dur = activeDurationMs()
                if (dur > 0L) {
                    seekBarRef?.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    timelineOverlayRef?.updateSegments(sponsorSegments, dur)
                }
                timeText.text = "${PipedStreamResolver.formatDuration(pos / 1000L)} / ${
                    PipedStreamResolver.formatDuration(dur.coerceAtLeast(0L) / 1000L)
                }"
                playPauseBtn.text = if (activeIsPlaying()) "❚❚" else "▶"

                if (currentSettings.sponsorBlockEnabled) {
                    val curSec = pos / 1000f
                    val hit = sponsorSegments.firstOrNull { seg ->
                        seg.uuid !in skippedIds && curSec >= seg.startSec && curSec < (seg.endSec - 0.5f)
                    }
                    if (hit != null) {
                        skippedIds.add(hit.uuid)
                        val targetMs = (hit.endSec * 1000f).toLong()
                        seekToPositionMs(targetMs)
                        skipNoticePill.text = "${categoryLabel(hit.category)}${AppStrings.of(currentSettings.appLanguage).playerSkippedSuffix}"
                        skipNoticePill.visibility = View.VISIBLE
                        handler.postDelayed({ skipNoticePill.visibility = View.GONE }, 3000L)
                    }
                }

                tickCount++
                val v = currentVideo
                if (tickCount % 10 == 0 && pos > 0L && v != null) {
                    repository.savePlaybackProgressAsync(v, pos, dur)
                }
            }
            handler.postDelayed(this, 500L)
        }
    }

    init {
        vlcController = VlcPlayerController(
            context = context,
            viewport = videoViewport,
            onPrepared = { durMs ->
                isPrepared = true
                loadingIndicator.visibility = View.GONE
                errorCard.visibility = View.GONE
                if (resumePositionMs > 3000L && (durMs <= 0L || durMs > resumePositionMs)) {
                    vlcController.seekToMs(resumePositionMs)
                }
                if (playbackSpeed != 1.0f) {
                    vlcController.setPlaybackRate(playbackSpeed)
                }
                if (durMs > 0L) {
                    timelineOverlayRef?.updateSegments(sponsorSegments, durMs)
                }
            },
            onBufferingChanged = { buffering ->
                if (usingVlc) {
                    loadingIndicator.visibility = if (buffering) View.VISIBLE else View.GONE
                }
            },
            onPlaybackStateChanged = { playing ->
                if (usingVlc) {
                    playPauseBtn.text = if (playing) "❚❚" else "▶"
                }
            },
            onError = { errMsg ->
                if (!usingVlc) return@VlcPlayerController
                val url = activeStreamUrl
                if (!retriedViaProxy && url != null && url.startsWith("https://", ignoreCase = true)) {
                    retriedViaProxy = true
                    val proxied = runCatching { streamProxy.startProxyForUrl(url) }.getOrNull()
                    if (proxied != null && vlcController.start(proxied, currentSettings)) {
                        return@VlcPlayerController
                    }
                }
                // Fall back to native MediaPlayer once if VLC encountered a codec/demux error
                vlcController.stopMediaOnly()
                usingVlc = false
                if (Build.VERSION.SDK_INT < 23 && playbackSpeed != 1.0f) {
                    playbackSpeed = 1.0f
                    speedBtn.text = "1.0x"
                }
                if (url != null) {
                    startNativeMediaPlayer(url, forceProxy = retriedViaProxy)
                    renderDetailsPanel(null)
                } else {
                    showNativeError(errMsg)
                    renderDetailsPanel(errMsg)
                }
            }
        )
        buildLayout()
    }

    private fun buildLayout() {
        val backBtn = Button(context).apply {
            text = "←"
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setCompatBackground(context.pillButtonDrawable(false))
            layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
            setOnClickListener {
                releaseAll()
                onClose()
            }
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(10), 0, context.dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(topTitle)
        titleCol.addView(topSubtitle)
        topBar.addView(backBtn)
        topBar.addView(titleCol)
        topBar.addView(bookmarkBtn)
        rootView.addView(topBar)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceHolder = holder
                if (usingVlc) return
                val pending = pendingStreamUrl
                if (pending != null && !currentSettings.audioOnlyMode && mediaPlayer == null) {
                    pendingStreamUrl = null
                    startNativeMediaPlayer(pending, forceProxy = retriedViaProxy)
                    return
                }
                if (!currentSettings.audioOnlyMode && holder.surface?.isValid == true) {
                    runCatching { mediaPlayer?.setDisplay(holder) }
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceHolder = null
                runCatching { mediaPlayer?.setDisplay(null) }
            }
        })

        videoViewport.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        videoViewport.addView(
            audioBanner,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        videoViewport.addView(
            loadingIndicator,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        videoViewport.addView(
            skipNoticePill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, context.dp(10), context.dp(10), 0) }
        )

        errorCard.addView(errorLabel)
        val retryBtn = Button(context).apply {
            text = AppStrings.of(currentSettings.appLanguage).playerRetry
            typeface = fonts.heading
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SIGNAL_RED, TabTubeColors.SIGNAL_RED, 0, 8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(48)
            ).apply { setMargins(0, context.dp(10), 0, 0) }
            setPadding(context.dp(14), 0, context.dp(14), 0)
            setOnClickListener {
                currentVideo?.let { v ->
                    openVideo(v, currentSettings, repository.getBookmarkIds().contains(v.id))
                }
            }
        }
        errorCard.addView(retryBtn)
        videoViewport.addView(
            errorCard,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { setMargins(context.dp(16), context.dp(16), context.dp(16), context.dp(16)) }
        )

        val (seekContainer, refs) = buildSeekbarWithSponsors(context) { progress ->
            if (isPrepared || (usingVlc && vlcController.isActive)) {
                val dur = activeDurationMs()
                if (dur > 0L) {
                    val targetMs = ((dur * progress.toLong()) / 1000L).coerceIn(0L, dur)
                    seekToPositionMs(targetMs)
                }
            }
        }
        seekBarRef = refs.first
        timelineOverlayRef = refs.second

        controlsBox.addView(seekContainer)
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), context.dp(2), context.dp(4), context.dp(4))
        }
        btnRow.addView(timeText.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        btnRow.addView(Button(context).apply {
            text = "-10s"
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setCompatBackground(context.pillButtonDrawable(false))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(48))
                .apply { setMargins(0, 0, context.dp(6), 0) }
            setOnClickListener {
                if (isPrepared || (usingVlc && vlcController.isActive)) {
                    seekToPositionMs((activePositionMs() - 10_000L).coerceAtLeast(0L))
                }
            }
        })
        playPauseBtn.setOnClickListener {
            if (usingVlc && vlcController.isActive) {
                vlcController.togglePlayPause()
                return@setOnClickListener
            }
            val mp = mediaPlayer
            if (mp != null && isPrepared) {
                runCatching {
                    if (mp.isPlaying) mp.pause() else mp.start()
                }
            }
        }
        btnRow.addView(playPauseBtn)
        btnRow.addView(Button(context).apply {
            text = "+10s"
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setCompatBackground(context.pillButtonDrawable(false))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(48))
                .apply { setMargins(context.dp(6), 0, context.dp(6), 0) }
            setOnClickListener {
                if (isPrepared || (usingVlc && vlcController.isActive)) {
                    val dur = activeDurationMs()
                    val next = activePositionMs() + 10_000L
                    seekToPositionMs(if (dur > 0L) next.coerceAtMost(dur) else next)
                }
            }
        })
        speedBtn.setOnClickListener { cyclePlaybackSpeed() }
        btnRow.addView(speedBtn)
        controlsBox.addView(btnRow)
        controlsBox.addView(quickActionRow)

        val contentScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        bodyCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(18))
        }

        bodyCol.addView(videoViewport)
        bodyCol.addView(controlsBox)
        bodyCol.addView(
            detailsContainer.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, context.dp(10), 0, 0) }
            }
        )
        contentScroll.addView(bodyCol)
        rootView.addView(contentScroll)
        applyViewportSizing()
    }

    private fun applyViewportSizing() {
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val heightPx = if (isFullscreen) {
            (screenH * 0.72f).toInt().coerceAtLeast(context.dp(260))
        } else if (context.resources.configuration.screenWidthDp >= 720) {
            (screenW * 0.34f).toInt().coerceIn(context.dp(240), context.dp(360))
        } else {
            context.dp(220)
        }
        videoViewport.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            heightPx
        )
        detailsContainer.visibility = if (isFullscreen) View.GONE else View.VISIBLE
    }

    private fun fitSurfaceAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        videoViewport.post {
            if (currentVideo == null) return@post
            val vw = videoViewport.width
            val vh = videoViewport.height
            if (vw <= 0 || vh <= 0) return@post
            val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
            val viewAspect = vw.toFloat() / vh.toFloat()

            val targetW: Int
            val targetH: Int
            if (videoAspect > viewAspect) {
                targetW = vw
                targetH = (vw / videoAspect).toInt().coerceAtLeast(1)
            } else {
                targetH = vh
                targetW = (vh * videoAspect).toInt().coerceAtLeast(1)
            }
            surfaceView.layoutParams = FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER)
        }
    }

    fun openVideo(
        video: VideoItem,
        settings: LegacyTuningSettings,
        isBookmarked: Boolean
    ) {
        releaseMediaPlayerOnly()
        currentVideo = video
        currentSettings = settings
        resolvedStream = null
        sponsorSegments = emptyList()
        skippedIds.clear()
        playbackSpeed = 1.0f
        speedBtn.text = "1.0x"
        errorCard.visibility = View.GONE

        topTitle.text = video.title
        topSubtitle.text = "${video.channelTitle} • ${settings.resolutionCap.shortLabel} • ${
            if (settings.forceH264Avc) "H.264 Lock" else "Auto"
        } • ${settings.ramBufferProfile.shortLabel}"

        updateBookmarkIcon(isBookmarked)
        bookmarkBtn.setOnClickListener {
            val savedNow = onToggleBookmark(video)
            updateBookmarkIcon(savedNow)
        }

        val strings = AppStrings.of(settings.appLanguage)
        audioBanner.text = strings.playerAudioBanner
        loadingIndicator.visibility = View.VISIBLE
        renderDetailsPanel(strings.playerPreparing)
        renderQuickActions()

        handler.removeCallbacks(ticker)
        handler.post(ticker)

        repository.loadPlayerResourcesAsync(video, settings) { result ->
            if (currentVideo?.id != video.id) return@loadPlayerResourcesAsync
            loadingIndicator.visibility = View.GONE
            sponsorSegments = result.sponsorSegments
            resumePositionMs = result.resumePositionMs

            result.streamResult.fold(
                onSuccess = { stream ->
                    resolvedStream = stream
                    startBuiltInPlayer(stream.streamUrl)
                    renderDetailsPanel(null)
                },
                onFailure = { err ->
                    resolvedStream = null
                    val msg = err.message ?: strings.playerNoStream
                    showNativeError("${strings.playerOpenErrorPrefix}$msg")
                    renderDetailsPanel(msg)
                }
            )
            renderQuickActions()
        }
    }

    private fun startBuiltInPlayer(streamUrl: String) {
        releaseMediaPlayerOnly()
        activeStreamUrl = streamUrl
        retriedViaProxy = false
        errorCard.visibility = View.GONE
        loadingIndicator.visibility = View.VISIBLE
        audioBanner.visibility = if (currentSettings.audioOnlyMode) View.VISIBLE else View.GONE

        if (vlcController.isSupportedOnDevice()) {
            surfaceView.visibility = View.GONE
            if (vlcController.start(streamUrl, currentSettings)) {
                usingVlc = true
                return
            }
        }
        usingVlc = false
        startNativeMediaPlayer(streamUrl)
    }

    private fun activePositionMs(): Long =
        if (usingVlc) vlcController.getCurrentPositionMs()
        else runCatching { mediaPlayer?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    private fun activeDurationMs(): Long =
        if (usingVlc) vlcController.getDurationMs()
        else runCatching { mediaPlayer?.duration?.toLong() ?: 0L }.getOrDefault(0L)

    private fun activeIsPlaying(): Boolean =
        if (usingVlc) vlcController.isPlaying()
        else runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)

    private fun seekToPositionMs(targetMs: Long) {
        if (usingVlc) {
            vlcController.seekToMs(targetMs)
        } else {
            runCatching { mediaPlayer?.seekTo(targetMs.toInt()) }
        }
    }

    private fun showNativeError(message: String) {
        loadingIndicator.visibility = View.GONE
        errorLabel.text = message
        errorCard.visibility = View.VISIBLE
    }

    private fun updateBookmarkIcon(saved: Boolean) {
        bookmarkBtn.text = if (saved) "★" else "☆"
        bookmarkBtn.setTextColor(if (saved) TabTubeColors.SIGNAL_RED else TabTubeColors.TEXT_PRIMARY)
    }

    @Suppress("DEPRECATION")
    private fun startNativeMediaPlayer(streamUrl: String, forceProxy: Boolean = false) {
        releaseMediaPlayerOnly()
        usingVlc = false
        activeStreamUrl = streamUrl
        if (!forceProxy) retriedViaProxy = false

        errorCard.visibility = View.GONE
        surfaceView.visibility = if (currentSettings.audioOnlyMode) View.GONE else View.VISIBLE
        audioBanner.visibility = if (currentSettings.audioOnlyMode) View.VISIBLE else View.GONE
        loadingIndicator.visibility = View.VISIBLE
        isPrepared = false

        val holder = surfaceHolder
        if (!currentSettings.audioOnlyMode && (holder == null || holder.surface?.isValid != true)) {
            // Wait until SurfaceHolder.Callback.surfaceCreated provides a valid Surface
            pendingStreamUrl = streamUrl
            return
        }
        pendingStreamUrl = null

        val mp = MediaPlayer()
        mediaPlayer = mp
        mp.apply {
            runCatching {
                if (Build.VERSION.SDK_INT >= 21) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                } else {
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                }
                setScreenOnWhilePlaying(true)
            }
            setOnVideoSizeChangedListener { player, w, h ->
                if (mediaPlayer === player) {
                    fitSurfaceAspectRatio(w, h)
                }
            }
            setOnPreparedListener { prepared ->
                if (mediaPlayer !== prepared) return@setOnPreparedListener
                runCatching {
                    isPrepared = true
                    loadingIndicator.visibility = View.GONE
                    fitSurfaceAspectRatio(prepared.videoWidth, prepared.videoHeight)
                    val dur = prepared.duration
                    if (resumePositionMs > 3000L && dur > resumePositionMs) {
                        prepared.seekTo(resumePositionMs.toInt())
                    }
                    if (Build.VERSION.SDK_INT >= 23 && playbackSpeed != 1.0f) {
                        runCatching {
                            prepared.playbackParams = prepared.playbackParams.setSpeed(playbackSpeed)
                        }
                    }
                    prepared.start()
                    timelineOverlayRef?.updateSegments(sponsorSegments, dur.toLong())
                }.onFailure { err ->
                    isPrepared = false
                    loadingIndicator.visibility = View.GONE
                    showNativeError(err.message ?: "Kan stream niet starten")
                }
            }
            setOnErrorListener { player, what, extra ->
                if (mediaPlayer !== player) return@setOnErrorListener true
                isPrepared = false
                if (!retriedViaProxy && streamUrl.startsWith("https://", ignoreCase = true)) {
                    retriedViaProxy = true
                    startNativeMediaPlayer(streamUrl, forceProxy = true)
                    return@setOnErrorListener true
                }
                loadingIndicator.visibility = View.GONE
                showNativeError("Hardware-decoder fout ($what / $extra). Probeer 360p of Alleen Audio.")
                true
            }
        }

        if (!currentSettings.audioOnlyMode && holder != null && holder.surface?.isValid == true) {
            runCatching { mp.setDisplay(holder) }
        }

        val shouldUseProxy = forceProxy ||
            (Build.VERSION.SDK_INT <= 20 && streamUrl.startsWith("https://", ignoreCase = true))

        runCatching {
            val playableUri = if (shouldUseProxy && streamUrl.startsWith("https://", ignoreCase = true)) {
                streamProxy.startProxyForUrl(streamUrl)
            } else {
                streamUrl
            }
            mp.setDataSource(context, Uri.parse(playableUri))
            mp.prepareAsync()
        }.onFailure { err ->
            loadingIndicator.visibility = View.GONE
            if (mediaPlayer === mp) mediaPlayer = null
            runCatching { mp.release() }
            showNativeError(err.message ?: "Kan stream niet afspelen")
        }
    }

    private fun releaseMediaPlayerOnly() {
        val v = currentVideo
        if (isPrepared && v != null) {
            val pos = activePositionMs()
            val dur = activeDurationMs()
            if (pos > 0L) repository.savePlaybackProgressAsync(v, pos, dur)
        }
        isPrepared = false
        vlcController.stopMediaOnly()
        val mp = mediaPlayer
        mediaPlayer = null
        runCatching { mp?.reset() }
        runCatching { mp?.release() }
    }

    fun releaseAll() {
        currentVideo = null
        pendingStreamUrl = null
        activeStreamUrl = null
        usingVlc = false
        handler.removeCallbacks(ticker)
        releaseMediaPlayerOnly()
        vlcController.stopAndReleaseAll()
        streamProxy.stop()
    }

    private fun cyclePlaybackSpeed() {
        if (!usingVlc && Build.VERSION.SDK_INT < 23) {
            speedBtn.text = "1.0x"
            return
        }
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        playbackSpeed = speeds[(speeds.indexOf(playbackSpeed) + 1) % speeds.size]
        speedBtn.text = "${playbackSpeed}x"
        if (usingVlc) {
            vlcController.setPlaybackRate(playbackSpeed)
            return
        }
        val mp = mediaPlayer
        if (mp != null && isPrepared && Build.VERSION.SDK_INT >= 23) {
            runCatching { mp.playbackParams = mp.playbackParams.setSpeed(playbackSpeed) }
        }
    }

    private fun renderQuickActions() {
        val strings = AppStrings.of(currentSettings.appLanguage)
        quickActionRow.removeAllViews()

        quickActionRow.addView(Button(context).apply {
            text = if (isFullscreen) strings.playerFullscreenOn else strings.playerFullscreenOff
            setTextColor(if (isFullscreen) TabTubeColors.PHOSPHOR_TEAL else TabTubeColors.TEXT_SECONDARY)
            setCompatBackground(context.pillButtonDrawable(isFullscreen))
            setPadding(context.dp(12), 0, context.dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(48)
            ).apply { setMargins(0, 0, context.dp(8), 0) }
            setOnClickListener {
                isFullscreen = !isFullscreen
                applyViewportSizing()
                renderQuickActions()
            }
        })

        quickActionRow.addView(Button(context).apply {
            text = if (currentSettings.audioOnlyMode) strings.playerAudioOn else strings.playerAudioOff
            setTextColor(if (currentSettings.audioOnlyMode) TabTubeColors.PHOSPHOR_TEAL else TabTubeColors.TEXT_SECONDARY)
            setCompatBackground(context.pillButtonDrawable(currentSettings.audioOnlyMode))
            setPadding(context.dp(12), 0, context.dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(48)
            )
            setOnClickListener {
                val nextAudio = !currentSettings.audioOnlyMode
                currentSettings = currentSettings.copy(audioOnlyMode = nextAudio)
                onToggleAudioOnly(nextAudio)
                if (usingVlc) {
                    vlcController.setAudioOnly(nextAudio)
                    surfaceView.visibility = View.GONE
                    audioBanner.visibility = if (nextAudio) View.VISIBLE else View.GONE
                } else if (nextAudio) {
                    runCatching { mediaPlayer?.setDisplay(null) }
                    surfaceView.visibility = View.GONE
                    audioBanner.visibility = View.VISIBLE
                    val pending = pendingStreamUrl
                    if (pending != null && mediaPlayer == null) {
                        pendingStreamUrl = null
                        startNativeMediaPlayer(pending)
                    }
                } else {
                    surfaceView.visibility = View.VISIBLE
                    audioBanner.visibility = View.GONE
                    surfaceHolder?.takeIf { it.surface?.isValid == true }?.let { holder ->
                        runCatching { mediaPlayer?.setDisplay(holder) }
                    }
                }
                renderQuickActions()
            }
        })
    }

    private fun renderDetailsPanel(statusNote: String?) {
        detailsContainer.removeAllViews()
        val video = currentVideo ?: return
        detailsContainer.addView(TextView(context).apply {
            text = video.title
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
        })
        detailsContainer.addView(TextView(context).apply {
            text = "${video.channelTitle} • ${video.publishedLabel}"
            typeface = fonts.body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(TabTubeColors.PHOSPHOR_TEAL)
            setPadding(0, context.dp(4), 0, context.dp(8))
        })

        val strings = AppStrings.of(currentSettings.appLanguage)
        val engineLabel = if (usingVlc) strings.playerEngineVlc else strings.playerEngineNative
        val streamLabel = if (resolvedStream != null) {
            "$engineLabel • ${resolvedStream?.qualityLabel} • ${resolvedStream?.codecLabel} (${resolvedStream?.sourceHost})"
        } else {
            statusNote ?: strings.playerPreparingDirect
        }
        detailsContainer.addView(TextView(context).apply {
            text = streamLabel
            typeface = fonts.mono
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
            setCompatBackground(context.roundedBoxDrawable(TabTubeColors.SURFACE_ELEVATED, TabTubeColors.BORDER_SUBTLE, 1, 8))
        })

        detailsContainer.addView(TextView(context).apply {
            text = String.format(strings.playerSponsorTitleFmt, sponsorSegments.size)
            typeface = fonts.heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(TabTubeColors.TEXT_PRIMARY)
            setPadding(0, context.dp(12), 0, context.dp(6))
        })

        if (!currentSettings.sponsorBlockEnabled) {
            detailsContainer.addView(TextView(context).apply {
                text = strings.playerSponsorDisabled
                typeface = fonts.body
                setTextColor(TabTubeColors.TEXT_SECONDARY)
            })
        } else if (sponsorSegments.isEmpty()) {
            detailsContainer.addView(TextView(context).apply {
                text = strings.playerSponsorEmpty
                typeface = fonts.body
                setTextColor(TabTubeColors.TEXT_SECONDARY)
            })
        } else {
            for (seg in sponsorSegments) {
                val segRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
                    setCompatBackground(context.focusableCardDrawable(TabTubeColors.SURFACE_ELEVATED, TabTubeColors.BORDER_SUBTLE, TabTubeColors.SIGNAL_RED, 8))
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, context.dp(6)) }
                    setOnClickListener {
                        if (isPrepared || (usingVlc && vlcController.isActive)) {
                            seekToPositionMs((seg.endSec * 1000f).toLong())
                        }
                    }
                }
                segRow.addView(TextView(context).apply {
                    text = categoryLabel(seg.category)
                    typeface = fonts.body
                    setTextColor(if (seg.category == "sponsor") TabTubeColors.SPONSOR_GREEN else TabTubeColors.AMBER_SPONSOR)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                segRow.addView(TextView(context).apply {
                    text = "${PipedStreamResolver.formatDuration(seg.startSec.toLong())} – ${
                        PipedStreamResolver.formatDuration(seg.endSec.toLong())
                    }"
                    typeface = fonts.mono
                    setTextColor(TabTubeColors.TEXT_PRIMARY)
                })
                detailsContainer.addView(segRow)
            }
        }
    }

    private fun categoryLabel(category: String): String {
        val strings = AppStrings.of(currentSettings.appLanguage)
        return when (category) {
            "sponsor" -> strings.sponsorCatSponsor
            "intro" -> strings.sponsorCatIntro
            "outro" -> strings.sponsorCatOutro
            "selfpromo" -> strings.sponsorCatSelfPromo
            else -> category
        }
    }
}
