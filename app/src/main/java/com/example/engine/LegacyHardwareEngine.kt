package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.example.data.model.DeviceTelemetry
import com.example.data.model.LegacyTuningSettings
import com.example.data.remote.LegacyHttpClient
import java.util.concurrent.Executors

object LegacyHardwareEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageExecutor = Executors.newFixedThreadPool(2)

    private var bitmapCache: LruCache<String, Bitmap> = createBitmapCache(4)
    private var currentCacheMb: Int = 4

    private fun createBitmapCache(cacheMb: Int): LruCache<String, Bitmap> {
        val maxBytes = cacheMb.coerceIn(2, 16) * 1024 * 1024
        return object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return if (Build.VERSION.SDK_INT >= 19) {
                    value.allocationByteCount
                } else {
                    value.rowBytes * value.height
                }
            }
        }
    }

    fun configureCache(settings: LegacyTuningSettings) {
        val targetMb = settings.ramBufferProfile.cacheMb
        if (targetMb != currentCacheMb) {
            bitmapCache.evictAll()
            bitmapCache = createBitmapCache(targetMb)
            currentCacheMb = targetMb
        }
    }

    fun clearImageCache() {
        bitmapCache.evictAll()
    }

    fun loadThumbnailInto(
        imageView: ImageView,
        url: String,
        rgb565: Boolean
    ) {
        imageView.tag = url
        if (url.isBlank()) {
            imageView.setImageBitmap(null)
            return
        }
        val cacheKey = "${if (rgb565) "565" else "8888"}_$url"
        val cached = bitmapCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageBitmap(null)

        imageExecutor.execute {
            val bytes = LegacyHttpClient.getBytes(url, timeoutMs = 6000) ?: return@execute
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = if (rgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                inPurgeable = true
                inInputShareable = true
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@execute
            bitmapCache.put(cacheKey, bitmap)

            mainHandler.post {
                if (imageView.tag == url) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun collectDeviceTelemetry(context: Context): DeviceTelemetry {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        val runtime = Runtime.getRuntime()
        val usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxHeapMb = runtime.maxMemory() / (1024L * 1024L)

        val h264HwDecoders = mutableListOf<String>()
        var vp9HwSupported = false

        runCatching {
            val codecInfos: Array<MediaCodecInfo> = if (Build.VERSION.SDK_INT >= 21) {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            } else {
                val count = MediaCodecList.getCodecCount()
                Array(count) { idx -> MediaCodecList.getCodecInfoAt(idx) }
            }

            for (info in codecInfos) {
                if (info.isEncoder) continue
                val name = info.name
                val isSoftware = name.startsWith("OMX.google.", ignoreCase = true) ||
                    name.startsWith("c2.android.", ignoreCase = true)
                val types = info.supportedTypes
                if (!isSoftware && types.any { it.equals("video/avc", ignoreCase = true) }) {
                    h264HwDecoders.add(name)
                }
                if (!isSoftware && types.any { it.equals("video/x-vnd.on2.vp9", ignoreCase = true) }) {
                    vp9HwSupported = true
                }
            }
        }

        val isLowRam = if (Build.VERSION.SDK_INT >= 19) {
            activityManager?.isLowRamDevice == true
        } else {
            (memInfo.totalMem / (1024L * 1024L)) <= 1536L
        }

        val abi = if (Build.VERSION.SDK_INT >= 21) {
            Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a"
        } else {
            Build.CPU_ABI ?: "armeabi-v7a"
        }

        return DeviceTelemetry(
            totalRamMb = memInfo.totalMem / (1024L * 1024L),
            availableRamMb = memInfo.availMem / (1024L * 1024L),
            appHeapUsedMb = usedHeapMb,
            appHeapMaxMb = maxHeapMb,
            androidVersionLabel = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            cpuAbi = abi,
            isLowRamClass = isLowRam || (memInfo.totalMem / (1024L * 1024L)) <= 1536L,
            h264HardwareDecoders = h264HwDecoders.distinct().take(4),
            supportsVp9Hardware = vp9HwSupported
        )
    }
}
