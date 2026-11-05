package com.audiohub.data.music

import com.audiohub.data.db.StatsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StatsRepository(
    private val dao: StatsDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val favoriteIds: Flow<Set<Long>> = dao.observeFavoriteIds().map { it.toSet() }

    suspend fun recordPlay(trackId: Long) = dao.recordPlay(trackId, clock())

    suspend fun setFavorite(trackId: Long, favorite: Boolean) = dao.setFavorite(trackId, favorite, clock())
}
