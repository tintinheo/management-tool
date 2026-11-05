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
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3), // adds podcasts + episodes
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun statsDao(): StatsDao
    abstract fun podcastDao(): PodcastDao
}
