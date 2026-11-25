package com.example.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.data.model.BookmarkEntry
import com.example.data.model.ChannelSubscription
import com.example.data.model.WatchHistoryEntry

class TabTubeDbHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        createTablesIfMissing(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        migrateOrRebuildTables(db)
        if (oldVersion < 5) {
            clearUntouchedLegacyTechDefaults(db)
        }
        if (oldVersion < 6) {
            clearUntouchedToddlerDefaults(db)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        migrateOrRebuildTables(db)
    }

    private fun clearUntouchedLegacyTechDefaults(db: SQLiteDatabase) {
        runCatching {
            db.execSQL(
                """
                DELETE FROM subscriptions
                WHERE added_at <= 10
                  AND channel_id IN (
                    'UC5I2hjZYiW9gZPVkvzM8_Cw',
                    'UCLx053rWZxCiYWsBETgdKrQ',
                    'UCsXVk37bltHxD1rDPwtNM8Q',
                    'UCHnyfMqiRRG1u-2MsSQLbXA',
                    'UCLA_DiR1FfKNvjuUpBHmylQ',
                    'UCBJycsmduvYEL83R_U4JriQ'
                  )
                """.trimIndent()
            )
        }
    }

    private fun clearUntouchedToddlerDefaults(db: SQLiteDatabase) {
        runCatching {
            db.execSQL(
                """
                DELETE FROM subscriptions
                WHERE added_at <= 10
                  AND channel_id IN (
                    'UCKvUKaPt2KzudgmnvGewQcQ',
                    'UClGH2tkl7zft73H2Z3iWLZg',
                    'UCVzLLZkDuFGAE2BGdBuBNBg',
                    'UCAOtE1V7Ots4DjM8JLlrYgg',
                    'UCLsooMJoIpl_7ux2jvdPB-Q',
                    'UCbCmjCuTUZos6Inko4u57UQ'
                  )
                """.trimIndent()
            )
        }
    }

    private fun createTablesIfMissing(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subscriptions (
                channel_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS watch_history (
                video_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                channel_title TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                thumbnail_url TEXT NOT NULL,
                direct_stream_url TEXT,
                last_position_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                watched_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
                video_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                channel_title TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                thumbnail_url TEXT NOT NULL,
                direct_stream_url TEXT,
                saved_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun migrateOrRebuildTables(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            migrateSubscriptionsIfNeeded(db)
            migrateWatchHistoryIfNeeded(db)
            migrateBookmarksIfNeeded(db)
            db.execSQL("DROP TABLE IF EXISTS room_master_table")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun migrateSubscriptionsIfNeeded(db: SQLiteDatabase) {
        val cols = getTableColumns(db, "subscriptions")
        if (cols.containsAll(listOf("channel_id", "name", "category", "added_at"))) return

        val existing = mutableListOf<ChannelSubscription>()
        if (cols.containsAll(listOf("channelId", "name", "category", "addedAt"))) {
            runCatching {
                db.rawQuery("SELECT channelId, name, category, addedAt FROM subscriptions", null).use { c ->
                    while (c.moveToNext()) {
                        existing.add(
                            ChannelSubscription(
                                channelId = c.getString(0),
                                name = c.getString(1),
                                category = c.getString(2),
                                addedAt = c.getLong(3)
                            )
                        )
                    }
                }
            }
        }
        db.execSQL("DROP TABLE IF EXISTS subscriptions")
        createTablesIfMissing(db)
        for (item in existing) {
            val cv = ContentValues().apply {
                put("channel_id", item.channelId)
                put("name", item.name)
                put("category", item.category)
                put("added_at", item.addedAt)
            }
            db.insertWithOnConflict("subscriptions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun migrateWatchHistoryIfNeeded(db: SQLiteDatabase) {
        val cols = getTableColumns(db, "watch_history")
        if (cols.containsAll(
                listOf(
                    "video_id", "title", "channel_title", "channel_id",
                    "thumbnail_url", "direct_stream_url", "last_position_ms", "duration_ms", "watched_at"
                )
            )
        ) {
            return
        }

        val existing = mutableListOf<WatchHistoryEntry>()
        if (cols.containsAll(
                listOf(
                    "videoId", "title", "channelTitle", "channelId",
                    "thumbnailUrl", "lastPositionMs", "durationMs", "watchedAt"
                )
            )
        ) {
            val streamCol = if (cols.contains("directStreamUrl")) "directStreamUrl" else "NULL"
            runCatching {
                db.rawQuery(
                    "SELECT videoId, title, channelTitle, channelId, thumbnailUrl, $streamCol, lastPositionMs, durationMs, watchedAt FROM watch_history",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        existing.add(
                            WatchHistoryEntry(
                                videoId = c.getString(0),
                                title = c.getString(1),
                                channelTitle = c.getString(2),
                                channelId = c.getString(3),
                                thumbnailUrl = c.getString(4),
                                directStreamUrl = c.getString(5),
                                lastPositionMs = c.getLong(6),
                                durationMs = c.getLong(7),
                                watchedAt = c.getLong(8)
                            )
                        )
                    }
                }
            }
        }
        db.execSQL("DROP TABLE IF EXISTS watch_history")
        createTablesIfMissing(db)
        for (entry in existing) {
            val cv = ContentValues().apply {
                put("video_id", entry.videoId)
                put("title", entry.title)
                put("channel_title", entry.channelTitle)
                put("channel_id", entry.channelId)
                put("thumbnail_url", entry.thumbnailUrl)
                put("direct_stream_url", entry.directStreamUrl)
                put("last_position_ms", entry.lastPositionMs)
                put("duration_ms", entry.durationMs)
                put("watched_at", entry.watchedAt)
            }
            db.insertWithOnConflict("watch_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun migrateBookmarksIfNeeded(db: SQLiteDatabase) {
        val cols = getTableColumns(db, "bookmarks")
        if (cols.containsAll(
                listOf(
                    "video_id", "title", "channel_title", "channel_id",
                    "thumbnail_url", "direct_stream_url", "saved_at"
                )
            )
        ) {
            return
        }

        val existing = mutableListOf<BookmarkEntry>()
        if (cols.containsAll(
                listOf("videoId", "title", "channelTitle", "channelId", "thumbnailUrl", "savedAt")
            )
        ) {
            val streamCol = if (cols.contains("directStreamUrl")) "directStreamUrl" else "NULL"
            runCatching {
                db.rawQuery(
                    "SELECT videoId, title, channelTitle, channelId, thumbnailUrl, $streamCol, savedAt FROM bookmarks",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        existing.add(
                            BookmarkEntry(
                                videoId = c.getString(0),
                                title = c.getString(1),
                                channelTitle = c.getString(2),
                                channelId = c.getString(3),
                                thumbnailUrl = c.getString(4),
                                directStreamUrl = c.getString(5),
                                savedAt = c.getLong(6)
                            )
                        )
                    }
                }
            }
        }
        db.execSQL("DROP TABLE IF EXISTS bookmarks")
        createTablesIfMissing(db)
        for (entry in existing) {
            val cv = ContentValues().apply {
                put("video_id", entry.videoId)
                put("title", entry.title)
                put("channel_title", entry.channelTitle)
                put("channel_id", entry.channelId)
                put("thumbnail_url", entry.thumbnailUrl)
                put("direct_stream_url", entry.directStreamUrl)
                put("saved_at", entry.savedAt)
            }
            db.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun getTableColumns(db: SQLiteDatabase, tableName: String): Set<String> {
        val cols = HashSet<String>()
        db.rawQuery("PRAGMA table_info($tableName)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0) cols.add(c.getString(nameIdx))
            }
        }
        return cols
    }

    fun getSubscriptionCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM subscriptions", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getSubscriptions(): List<ChannelSubscription> {
        val list = mutableListOf<ChannelSubscription>()
        readableDatabase.rawQuery(
            "SELECT channel_id, name, category, added_at FROM subscriptions ORDER BY added_at ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    ChannelSubscription(
                        channelId = c.getString(0),
                        name = c.getString(1),
                        category = c.getString(2),
                        addedAt = c.getLong(3)
                    )
                )
            }
        }
        return list
    }

    fun upsertSubscription(item: ChannelSubscription) {
        val cv = ContentValues().apply {
            put("channel_id", item.channelId)
            put("name", item.name)
            put("category", item.category)
            put("added_at", item.addedAt)
        }
        writableDatabase.insertWithOnConflict("subscriptions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteSubscription(channelId: String) {
        writableDatabase.delete("subscriptions", "channel_id = ?", arrayOf(channelId))
    }

    fun getHistory(): List<WatchHistoryEntry> {
        val list = mutableListOf<WatchHistoryEntry>()
        readableDatabase.rawQuery(
            "SELECT video_id, title, channel_title, channel_id, thumbnail_url, direct_stream_url, last_position_ms, duration_ms, watched_at FROM watch_history ORDER BY watched_at DESC LIMIT 50",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    WatchHistoryEntry(
                        videoId = c.getString(0),
                        title = c.getString(1),
                        channelTitle = c.getString(2),
                        channelId = c.getString(3),
                        thumbnailUrl = c.getString(4),
                        directStreamUrl = c.getString(5),
                        lastPositionMs = c.getLong(6),
                        durationMs = c.getLong(7),
                        watchedAt = c.getLong(8)
                    )
                )
            }
        }
        return list
    }

    fun getResumePosition(videoId: String): Long {
        readableDatabase.rawQuery(
            "SELECT last_position_ms FROM watch_history WHERE video_id = ? LIMIT 1",
            arrayOf(videoId)
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun upsertHistory(entry: WatchHistoryEntry) {
        val cv = ContentValues().apply {
            put("video_id", entry.videoId)
            put("title", entry.title)
            put("channel_title", entry.channelTitle)
            put("channel_id", entry.channelId)
            put("thumbnail_url", entry.thumbnailUrl)
            put("direct_stream_url", entry.directStreamUrl)
            put("last_position_ms", entry.lastPositionMs)
            put("duration_ms", entry.durationMs)
            put("watched_at", entry.watchedAt)
        }
        writableDatabase.insertWithOnConflict("watch_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearHistory() {
        writableDatabase.delete("watch_history", null, null)
    }

    fun getBookmarks(): List<BookmarkEntry> {
        val list = mutableListOf<BookmarkEntry>()
        readableDatabase.rawQuery(
            "SELECT video_id, title, channel_title, channel_id, thumbnail_url, direct_stream_url, saved_at FROM bookmarks ORDER BY saved_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    BookmarkEntry(
                        videoId = c.getString(0),
                        title = c.getString(1),
                        channelTitle = c.getString(2),
                        channelId = c.getString(3),
                        thumbnailUrl = c.getString(4),
                        directStreamUrl = c.getString(5),
                        savedAt = c.getLong(6)
                    )
                )
            }
        }
        return list
    }

    fun getBookmarkIds(): Set<String> {
        val ids = HashSet<String>()
        readableDatabase.rawQuery("SELECT video_id FROM bookmarks", null).use { c ->
            while (c.moveToNext()) {
                ids.add(c.getString(0))
            }
        }
        return ids
    }

    fun upsertBookmark(entry: BookmarkEntry) {
        val cv = ContentValues().apply {
            put("video_id", entry.videoId)
            put("title", entry.title)
            put("channel_title", entry.channelTitle)
            put("channel_id", entry.channelId)
            put("thumbnail_url", entry.thumbnailUrl)
            put("direct_stream_url", entry.directStreamUrl)
            put("saved_at", entry.savedAt)
        }
        writableDatabase.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteBookmark(videoId: String) {
        writableDatabase.delete("bookmarks", "video_id = ?", arrayOf(videoId))
    }

    companion object {
        internal const val DB_NAME = "tabtube_lite.db"
        internal const val DB_VERSION = 6
    }
}
