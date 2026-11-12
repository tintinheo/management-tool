package com.audiohub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        PlayStatsEntity::class,
        PodcastEntity::class,
        EpisodeEntity::class,
        EpisodeNoteEntity::class,
        BookEntity::class,
        BookChapterEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3), // adds podcasts + episodes
        AutoMigration(from = 3, to = 4), // adds date-modified columns for sorting
        AutoMigration(from = 4, to = 5), // adds episode recall notes
        AutoMigration(from = 5, to = 6), // adds audiobooks
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun statsDao(): StatsDao
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeNoteDao(): EpisodeNoteDao
    abstract fun bookDao(): BookDao
}
