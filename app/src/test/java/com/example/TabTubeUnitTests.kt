package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.LegacyTuningSettings
import com.example.data.model.RamBufferProfile
import com.example.data.model.ResolutionCap
import com.example.data.remote.LegacyHttpClient
import com.example.data.remote.PipedStreamResolver
import com.example.data.remote.PureBcHttpsTransport
import com.example.data.remote.YouTubeRssService
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.X509Extensions
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.x509.X509V3CertificateGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TabTubeUnitTests {

    @Test
    fun `extractVideoId handles standard short and raw youtube links`() {
        assertEquals("dQw4w9WgXcQ", PipedStreamResolver.extractVideoId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", PipedStreamResolver.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", PipedStreamResolver.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", PipedStreamResolver.extractVideoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertNull(PipedStreamResolver.extractVideoId("retro hardware review"))
    }

    @Test
    fun `default settings match galaxy tab 2 legacy preset`() {
        val settings = LegacyTuningSettings()
        assertTrue(settings.isGalaxyTab2Preset)
        assertEquals(ResolutionCap.SD_480P, settings.resolutionCap)
        assertEquals(RamBufferProfile.TAB2_4MB, settings.ramBufferProfile)
        assertTrue(settings.forceH264Avc)
        assertTrue(settings.rgb565Thumbnails)
        assertTrue(settings.disableAnimations)
        org.junit.Assert.assertFalse(settings.toddlerKidsMode)
        assertEquals(com.example.data.model.AppLanguage.DUTCH, settings.appLanguage)
    }

    @Test
    fun `rss parser extracts entries with lightweight mqdefault thumbnail`() {
        val sampleXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/">
              <entry>
                <yt:videoId>abc123XYZ00</yt:videoId>
                <title>Retro Tablet Teardown</title>
                <author><name>Techmoan</name></author>
                <published>2026-11-18T12:00:00+00:00</published>
              </entry>
            </feed>
        """.trimIndent()

        val service = YouTubeRssService()
        val items = service.parseAtomFeed(sampleXml, "UC5I2hjZYiW9gZPVkvzM8_Cw", "Fallback", "Tech & Retro")
        assertEquals(1, items.size)
        assertEquals("abc123XYZ00", items[0].id)
        assertEquals("Retro Tablet Teardown", items[0].title)
        assertEquals("Techmoan", items[0].channelTitle)
        assertEquals("https://i.ytimg.com/vi/abc123XYZ00/mqdefault.jpg", items[0].thumbnailUrl)
    }

    @Test
    fun `innertube search parser extracts videoRenderer and channel browseId`() {
        val json = """
            {
              "contents": {
                "twoColumnSearchResultsRenderer": {
                  "primaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "itemSectionRenderer": {
                            "contents": [
                              {
                                "videoRenderer": {
                                  "videoId": "dQw4w9WgXcQ",
                                  "title": { "runs": [{ "text": "Galaxy Tab 2 LineageOS & SmartTube" }] },
                                  "ownerText": {
                                    "runs": [{
                                      "text": "RetroTech NL",
                                      "navigationEndpoint": {
                                        "browseEndpoint": { "browseId": "UCretro12345" }
                                      }
                                    }]
                                  },
                                  "publishedTimeText": { "simpleText": "2 dagen geleden" },
                                  "lengthText": { "simpleText": "12:34" }
                                }
                              },
                              {
                                "compactVideoRenderer": {
                                  "videoId": "aqz-KE-bpKQ",
                                  "title": { "simpleText": "Big Buck Bunny H.264" },
                                  "shortBylineText": { "runs": [{ "text": "Blender" }] },
                                  "lengthText": { "simpleText": "9:56" }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val items = PipedStreamResolver().parseInnerTubeSearchResponse(json)
        assertEquals(2, items.size)
        assertEquals("dQw4w9WgXcQ", items[0].id)
        assertEquals("Galaxy Tab 2 LineageOS & SmartTube", items[0].title)
        assertEquals("RetroTech NL", items[0].channelTitle)
        assertEquals("UCretro12345", items[0].channelId)
        assertEquals("12:34", items[0].durationLabel)
        assertEquals("aqz-KE-bpKQ", items[1].id)
        assertEquals("Big Buck Bunny H.264", items[1].title)
    }

    @Test
    fun `legacy ssl initialization loads bundled root CAs and socket factory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LegacyHttpClient.init(context)
        val conn = LegacyHttpClient.openConfiguredConnection("https://www.youtube.com/youtubei/v1/search") as HttpsURLConnection
        assertNotNull(conn.sslSocketFactory)
        conn.disconnect()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `pureBcHttpsTransport verifies hostnames and completes TLS loopback POST with chunked response`() {
        val bcProvider = BouncyCastleProvider()
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val gen = X509V3CertificateGenerator().apply {
            setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            setIssuerDN(X500Principal("CN=127.0.0.1"))
            setSubjectDN(X500Principal("CN=127.0.0.1"))
            setNotBefore(Date(System.currentTimeMillis() - 60_000L))
            setNotAfter(Date(System.currentTimeMillis() + 86_400_000L))
            setPublicKey(keyPair.public)
            setSignatureAlgorithm("SHA256WithRSA")
            addExtension(
                X509Extensions.SubjectAlternativeName,
                false,
                GeneralNames(
                    arrayOf(
                        GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                        GeneralName(GeneralName.dNSName, "*.youtube.com")
                    )
                )
            )
        }
        val testCert: X509Certificate = gen.generate(keyPair.private, SecureRandom())

        // Verify hostname checks work for wildcard DNS & IP SANs, and reject mismatches
        PureBcHttpsTransport.verifyHostname("www.youtube.com", testCert)
        PureBcHttpsTransport.verifyHostname("127.0.0.1", testCert)
        try {
            PureBcHttpsTransport.verifyHostname("evil.example.org", testCert)
            fail("Expected SSLPeerUnverifiedException for mismatched hostname")
        } catch (_: SSLPeerUnverifiedException) {
            // expected
        }

        val serverSocket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val exec = Executors.newSingleThreadExecutor()
        val serverErr = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        try {
            exec.submit {
                try {
                    serverSocket.accept().use { client ->
                        val crypto = BcTlsCrypto(SecureRandom())
                        val tlsServer = TlsServerProtocol(client.getInputStream(), client.getOutputStream())
                        tlsServer.accept(object : DefaultTlsServer(crypto) {
                            override fun getSupportedVersions(): Array<org.bouncycastle.tls.ProtocolVersion> =
                                org.bouncycastle.tls.ProtocolVersion.TLSv12.only()

                            override fun getRSASignerCredentials(): TlsCredentialedSigner {
                                val bcCert = BcTlsCertificate(crypto, testCert.encoded)
                                val keyParam = PrivateKeyFactory.createKey(keyPair.private.encoded)
                                return BcDefaultTlsCredentialedSigner(
                                    TlsCryptoParameters(context),
                                    crypto,
                                    keyParam,
                                    Certificate(arrayOf(bcCert)),
                                    SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.rsa)
                                )
                            }
                        })
                        val reqBuf = ByteArray(1024)
                        tlsServer.inputStream.read(reqBuf)
                        val payload = """{"ok":true,"query":"tab2"}"""
                        val chunkHex = Integer.toHexString(payload.toByteArray(Charsets.UTF_8).size)
                        val httpResp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n$chunkHex\r\n$payload\r\n0\r\n\r\n"
                        tlsServer.outputStream.write(httpResp.toByteArray(Charsets.UTF_8))
                        tlsServer.outputStream.flush()
                        runCatching { tlsServer.close() }
                    }
                } catch (t: Throwable) {
                    serverErr.set(t)
                }
            }

            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    require(chain != null && chain.isNotEmpty() && chain[0].encoded.contentEquals(testCert.encoded))
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(testCert)
            }

            val resp = PureBcHttpsTransport.execute(
                urlString = "https://127.0.0.1:${serverSocket.localPort}/youtubei/v1/search",
                method = "POST",
                headers = mapOf("Accept-Encoding" to "identity"),
                bodyBytes = """{"query":"tab2"}""".toByteArray(Charsets.UTF_8),
                timeoutMs = 5000,
                trustManager = trustManager,
                bcProvider = bcProvider
            )
            serverErr.get()?.let { throw it }
            resp.use {
                assertEquals(200, it.code)
                val body = it.bodyStream.bufferedReader(Charsets.UTF_8).readText()
                assertEquals("""{"ok":true,"query":"tab2"}""", body)
            }
        } finally {
            runCatching { serverSocket.close() }
            exec.shutdownNow()
        }
    }

    @Test
    fun `tabTubeDbHelper migrates legacy Room v1 camelCase schema without SQLiteException`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("tabtube_lite.db")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val legacyDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        legacyDb.execSQL("CREATE TABLE subscriptions (channelId TEXT PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL, addedAt INTEGER NOT NULL)")
        legacyDb.execSQL("INSERT INTO subscriptions (channelId, name, category, addedAt) VALUES ('UCtest123', 'TestKanaal', 'Tech', 1000)")
        legacyDb.execSQL("CREATE TABLE watch_history (videoId TEXT PRIMARY KEY, title TEXT NOT NULL, channelTitle TEXT NOT NULL, channelId TEXT NOT NULL, thumbnailUrl TEXT NOT NULL, lastPositionMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, watchedAt INTEGER NOT NULL)")
        legacyDb.execSQL("CREATE TABLE bookmarks (videoId TEXT PRIMARY KEY, title TEXT NOT NULL, channelTitle TEXT NOT NULL, channelId TEXT NOT NULL, thumbnailUrl TEXT NOT NULL, savedAt INTEGER NOT NULL)")
        legacyDb.version = 1
        legacyDb.close()

        val helper = com.example.data.local.TabTubeDbHelper(context)
        val subs = helper.getSubscriptions()
        assertEquals(1, subs.size)
        assertEquals("UCtest123", subs[0].channelId)
        assertEquals("TestKanaal", subs[0].name)
        assertEquals(0, helper.getHistory().size)
        assertEquals(0, helper.getBookmarks().size)
        helper.close()
    }

    @Test
    fun `playerOverlayView opens audio-only benchmark video and releases cleanly without crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.data.local.TabTubeDbHelper(context)
        val repo = com.example.data.VideoRepository(db)
        val fonts = com.example.ui.TabTubeFonts(context)

        val player = com.example.ui.PlayerOverlayView(
            context = context,
            fonts = fonts,
            repository = repo,
            onToggleBookmark = { false },
            onToggleAudioOnly = {},
            onClose = {}
        )

        val audioSettings = LegacyTuningSettings(
            sponsorBlockEnabled = false,
            audioOnlyMode = true
        )
        player.openVideo(
            video = com.example.data.VideoRepository.HARDWARE_BENCHMARK_VIDEO,
            settings = audioSettings,
            isBookmarked = false
        )
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        player.releaseAll()
        db.close()
    }

    @Test
    fun `playerOverlayView opens video mode triggers vlc fallback cleanly and releases`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.data.local.TabTubeDbHelper(context)
        val repo = com.example.data.VideoRepository(db)
        val fonts = com.example.ui.TabTubeFonts(context)

        val player = com.example.ui.PlayerOverlayView(
            context = context,
            fonts = fonts,
            repository = repo,
            onToggleBookmark = { true },
            onToggleAudioOnly = {},
            onClose = {}
        )

        val videoSettings = LegacyTuningSettings(
            sponsorBlockEnabled = false,
            audioOnlyMode = false
        )
        player.openVideo(
            video = com.example.data.VideoRepository.HARDWARE_BENCHMARK_VIDEO,
            settings = videoSettings,
            isBookmarked = false
        )
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        player.releaseAll()
        db.close()
    }

    @Test
    fun `kidsContentFilter enriches generic queries filters unsafe titles and prioritizes toddler content`() {
        assertEquals("dieren voor peuters kids", com.example.data.remote.KidsContentFilter.enrichSearchQuery("dieren", true))
        assertEquals("Nijntje in de dierentuin", com.example.data.remote.KidsContentFilter.enrichSearchQuery("Nijntje in de dierentuin", true))
        assertEquals("dieren", com.example.data.remote.KidsContentFilter.enrichSearchQuery("dieren", false))
        assertEquals("dQw4w9WgXcQ", com.example.data.remote.KidsContentFilter.enrichSearchQuery("dQw4w9WgXcQ", true))

        val items = listOf(
            com.example.data.model.VideoItem("v1", "Treinen documentaire", "SpoorTV", "c1", "Zoekresultaat", "Nu", ""),
            com.example.data.model.VideoItem("v2", "Scary Horror Train Jumpscare", "CreepChannel", "c2", "Zoekresultaat", "Nu", ""),
            com.example.data.model.VideoItem("v3", "Nijntje en de trein - peuterliedje", "Nijntje (NL)", "c3", "Peuter NL", "Nu", "")
        )
        val filtered = com.example.data.remote.KidsContentFilter.filterAndRankResults(
            items = items,
            toddlerMode = true,
            isSearch = true
        )
        assertEquals(2, filtered.size)
        assertEquals("v3", filtered[0].id)
        assertEquals("v1", filtered[1].id)
    }

    @Test
    fun `tabTubeDbHelper v5 upgrade replaces untouched tech defaults and keeps custom channels`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("tabtube_lite.db")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val v4Db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        v4Db.execSQL("CREATE TABLE subscriptions (channel_id TEXT PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL, added_at INTEGER NOT NULL)")
        v4Db.execSQL("INSERT INTO subscriptions (channel_id, name, category, added_at) VALUES ('UC5I2hjZYiW9gZPVkvzM8_Cw', 'Techmoan', 'Tech & Retro', 1)")
        v4Db.execSQL("INSERT INTO subscriptions (channel_id, name, category, added_at) VALUES ('UCcustomToddler', 'Eigen Kanaal', 'Peuter NL', 9999)")
        v4Db.execSQL("CREATE TABLE watch_history (video_id TEXT PRIMARY KEY, title TEXT NOT NULL, channel_title TEXT NOT NULL, channel_id TEXT NOT NULL, thumbnail_url TEXT NOT NULL, direct_stream_url TEXT, last_position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, watched_at INTEGER NOT NULL)")
        v4Db.execSQL("CREATE TABLE bookmarks (video_id TEXT PRIMARY KEY, title TEXT NOT NULL, channel_title TEXT NOT NULL, channel_id TEXT NOT NULL, thumbnail_url TEXT NOT NULL, direct_stream_url TEXT, saved_at INTEGER NOT NULL)")
        v4Db.version = 4
        v4Db.close()

        val helper = com.example.data.local.TabTubeDbHelper(context)
        val subs = helper.getSubscriptions()
        assertEquals(1, subs.size)
        assertEquals("UCcustomToddler", subs[0].channelId)
        helper.close()
    }

    @Test
    fun `tabTubeDbHelper v6 upgrade replaces untouched toddler defaults and keeps custom channels`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("tabtube_lite.db")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val v5Db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        v5Db.execSQL("CREATE TABLE subscriptions (channel_id TEXT PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL, added_at INTEGER NOT NULL)")
        v5Db.execSQL("INSERT INTO subscriptions (channel_id, name, category, added_at) VALUES ('UCKvUKaPt2KzudgmnvGewQcQ', 'Nijntje (NL)', 'Peuter NL', 1)")
        v5Db.execSQL("INSERT INTO subscriptions (channel_id, name, category, added_at) VALUES ('UCcustomStandard', 'Eigen Kanaal', 'Tech & Retro', 9999)")
        v5Db.execSQL("CREATE TABLE watch_history (video_id TEXT PRIMARY KEY, title TEXT NOT NULL, channel_title TEXT NOT NULL, channel_id TEXT NOT NULL, thumbnail_url TEXT NOT NULL, direct_stream_url TEXT, last_position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, watched_at INTEGER NOT NULL)")
        v5Db.execSQL("CREATE TABLE bookmarks (video_id TEXT PRIMARY KEY, title TEXT NOT NULL, channel_title TEXT NOT NULL, channel_id TEXT NOT NULL, thumbnail_url TEXT NOT NULL, direct_stream_url TEXT, saved_at INTEGER NOT NULL)")
        v5Db.version = 5
        v5Db.close()

        val helper = com.example.data.local.TabTubeDbHelper(context)
        val subs = helper.getSubscriptions()
        assertEquals(1, subs.size)
        assertEquals("UCcustomStandard", subs[0].channelId)
        helper.close()
    }

    @Test
    fun `settingsStore switches language to English and resets legacy toddler flag to standard YouTube`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("tabtube_legacy_tuning", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("toddler_kids_mode", true)
            .commit()

        val store = com.example.data.local.SettingsStore(context)
        val initial = store.load()
        org.junit.Assert.assertFalse(initial.toddlerKidsMode)
        assertEquals(com.example.data.model.AppLanguage.DUTCH, initial.appLanguage)

        val updated = store.update { it.copy(appLanguage = com.example.data.model.AppLanguage.ENGLISH) }
        assertEquals(com.example.data.model.AppLanguage.ENGLISH, updated.appLanguage)
        assertEquals(com.example.data.model.AppLanguage.ENGLISH, store.load().appLanguage)

        val enStrings = com.example.ui.AppStrings.of(com.example.data.model.AppLanguage.ENGLISH)
        assertEquals("Channels", enStrings.navChannels)
        assertEquals("Library", enStrings.navLibrary)
        assertEquals("All", enStrings.categoryAll)
        assertEquals("Science", enStrings.formatCategory("Wetenschap"))
    }
}
