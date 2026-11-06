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
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3), // adds podcasts + episodes
        AutoMigration(from = 3, to = 4), // adds date-modified columns for sorting
        AutoMigration(from = 4, to = 5), // adds episode recall notes
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun statsDao(): StatsDao
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeNoteDao(): EpisodeNoteDao
}
