package com.audiohub.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class StatsDao {

    @Query("INSERT OR IGNORE INTO play_stats(trackId, playCount, lastPlayedAt, favorite, favoritedAt) VALUES(:trackId, 0, 0, 0, 0)")
    protected abstract suspend fun ensure(trackId: Long)

    @Query("UPDATE play_stats SET playCount = playCount + 1, lastPlayedAt = :now WHERE trackId = :trackId")
    protected abstract suspend fun bump(trackId: Long, now: Long)

    @Query("UPDATE play_stats SET favorite = :favorite, favoritedAt = :now WHERE trackId = :trackId")
    protected abstract suspend fun updateFavorite(trackId: Long, favorite: Boolean, now: Long)

    @Query("SELECT trackId FROM play_stats WHERE favorite = 1")
    abstract fun observeFavoriteIds(): Flow<List<Long>>

    @Query("SELECT * FROM play_stats WHERE trackId = :trackId")
    abstract suspend fun get(trackId: Long): PlayStatsEntity?

    @Transaction
    open suspend fun recordPlay(trackId: Long, now: Long) {
        ensure(trackId)
        bump(trackId, now)
    }

    @Transaction
    open suspend fun setFavorite(trackId: Long, favorite: Boolean, now: Long) {
        ensure(trackId)
        updateFavorite(trackId, favorite, now)
    }
}
