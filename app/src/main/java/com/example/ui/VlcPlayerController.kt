package com.example.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.example.data.model.LegacyTuningSettings
import com.example.data.model.RamBufferProfile
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Built-in VideoLAN LibVLC playback engine tuned for legacy tablets (e.g. Galaxy Tab 2)
 * and modern Android devices alike. Falls back cleanly if running on API 16 or JVM tests.
 */
internal class VlcPlayerController(
    private val context: Context,
    private val viewport: FrameLayout,
    private val onPrepared: (durationMs: Long) -> Unit,
    private val onBufferingChanged: (isBuffering: Boolean) -> Unit,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onError: (message: String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var libVlc: LibVLC? = null
    private var vlcPlayer: VlcMediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var currentCacheMs: Int = -1
    private var notifiedPrepared = false

    val isActive: Boolean get() = vlcPlayer != null

    fun isSupportedOnDevice(): Boolean = Build.VERSION.SDK_INT >= 17

    fun ensureVideoLayoutAttached(audioOnlyMode: Boolean): VLCVideoLayout? {
        if (!isSupportedOnDevice()) return null
        val layout = videoLayout ?: runCatching {
            VLCVideoLayout(context).also { vl ->
                vl.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                viewport.addView(vl, 0)
                videoLayout = vl
            }
        }.getOrNull() ?: return null

        layout.visibility = if (audioOnlyMode) View.GONE else View.VISIBLE
        return layout
    }

    fun start(
        streamUrl: String,
        settings: LegacyTuningSettings
    ): Boolean {
        if (!isSupportedOnDevice()) return false
        val cacheMs = when (settings.ramBufferProfile) {
            RamBufferProfile.TAB2_4MB -> 1500
            RamBufferProfile.BALANCED_8MB -> 2500
            RamBufferProfile.STANDARD_16MB -> 4000
        }

        return try {
            stopMediaOnly()

            if (libVlc == null || currentCacheMs != cacheMs) {
                runCatching { libVlc?.release() }
                val options = arrayListOf(
                    "--network-caching=$cacheMs",
                    "--http-reconnect",
                    "--drop-late-frames",
                    "--skip-frames",
                    "--avcodec-threads=2",
                    "--no-stats"
                )
                libVlc = LibVLC(context.applicationContext, options).apply {
                    setUserAgent("TabTubeLite/1.1", "TabTubeLite/1.1 (LibVLC)")
                }
                currentCacheMs = cacheMs
            }

            val vlc = libVlc ?: return false
            val layout = ensureVideoLayoutAttached(settings.audioOnlyMode) ?: return false

            val player = VlcMediaPlayer(vlc)
            vlcPlayer = player
            notifiedPrepared = false

            player.setEventListener { event ->
                if (vlcPlayer !== player) return@setEventListener
                when (event.type) {
                    VlcMediaPlayer.Event.Opening -> {
                        mainHandler.post { onBufferingChanged(true) }
                    }
                    VlcMediaPlayer.Event.Buffering -> {
                        val pct = event.buffering
                        mainHandler.post { onBufferingChanged(pct < 99.5f) }
                    }
                    VlcMediaPlayer.Event.Playing -> {
                        mainHandler.post {
                            if (vlcPlayer !== player) return@post
                            onBufferingChanged(false)
                            onPlaybackStateChanged(true)
                            if (!notifiedPrepared) {
                                notifiedPrepared = true
                                onPrepared(player.length.coerceAtLeast(0L))
                            }
                        }
                    }
                    VlcMediaPlayer.Event.Paused,
                    VlcMediaPlayer.Event.Stopped,
                    VlcMediaPlayer.Event.EndReached -> {
                        mainHandler.post {
                            if (vlcPlayer !== player) return@post
                            onBufferingChanged(false)
                            onPlaybackStateChanged(false)
                        }
                    }
                    VlcMediaPlayer.Event.EncounteredError -> {
                        mainHandler.post {
                            if (vlcPlayer !== player) return@post
                            onBufferingChanged(false)
                            onPlaybackStateChanged(false)
                            onError("VLC kon deze stream niet afspelen.")
                        }
                    }
                }
            }

            if (!settings.audioOnlyMode) {
                player.attachViews(layout, null, false, false)
                player.videoScale = VlcMediaPlayer.ScaleType.SURFACE_BEST_FIT
            }

            val media = Media(vlc, Uri.parse(streamUrl)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=$cacheMs")
                if (settings.audioOnlyMode) {
                    addOption(":no-video")
                }
            }
            player.media = media
            media.release()

            player.play()
            true
        } catch (_: Throwable) {
            // UnsatisfiedLinkError on JVM/Robolectric or incompatible API 16 ROM -> caller falls back cleanly
            stopAndReleaseAll()
            false
        }
    }

    fun setAudioOnly(audioOnly: Boolean) {
        val layout = videoLayout
        if (layout != null) {
            layout.visibility = if (audioOnly) View.GONE else View.VISIBLE
        }
        val player = vlcPlayer ?: return
        runCatching {
            if (audioOnly) {
                player.detachViews()
                player.setVideoTrackEnabled(false)
            } else {
                layout?.let {
                    player.attachViews(it, null, false, false)
                    player.videoScale = VlcMediaPlayer.ScaleType.SURFACE_BEST_FIT
                }
                player.setVideoTrackEnabled(true)
            }
        }
    }

    fun isPlaying(): Boolean = runCatching { vlcPlayer?.isPlaying == true }.getOrDefault(false)

    fun getCurrentPositionMs(): Long = runCatching { vlcPlayer?.time ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)

    fun getDurationMs(): Long = runCatching { vlcPlayer?.length ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)

    fun seekToMs(ms: Long) {
        runCatching {
            val target = ms.coerceAtLeast(0L)
            vlcPlayer?.time = target
        }
    }

    fun togglePlayPause() {
        val player = vlcPlayer ?: return
        runCatching {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    fun setPlaybackRate(speed: Float) {
        runCatching { vlcPlayer?.rate = speed }
    }

    fun stopMediaOnly() {
        val player = vlcPlayer
        vlcPlayer = null
        notifiedPrepared = false
        if (player != null) {
            runCatching { player.setEventListener(null) }
            runCatching { player.stop() }
            runCatching { player.detachViews() }
            runCatching { player.release() }
        }
        videoLayout?.visibility = View.GONE
    }

    fun stopAndReleaseAll() {
        stopMediaOnly()
        val vlc = libVlc
        libVlc = null
        currentCacheMs = -1
        runCatching { vlc?.release() }
        videoLayout?.visibility = View.GONE
    }
}
