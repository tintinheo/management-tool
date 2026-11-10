package com.myguitarplayer.data

import com.myguitarplayer.platform.Platform

/** App-wide repositories, built on first use from the installed [Platform]. */
object Repos {
    val progress: ProgressRepo by lazy { ProgressRepo(Platform.current.db, Curriculum.load()) }
    val songs: SongRepo by lazy { SongRepo(Platform.current.db.songs()) }
    val practice: PracticeRepo by lazy { PracticeRepo(Platform.current.db.practice()) }
    val changes: ChangeDao get() = Platform.current.db.changes()
}
