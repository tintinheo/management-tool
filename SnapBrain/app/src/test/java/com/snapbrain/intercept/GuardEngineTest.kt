package com.snapbrain.intercept

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardEngineTest {

    private val blocked = setOf("tiktok")
    private val engine = GuardEngine(ownPkg = "com.snapbrain")

    @Test
    fun opensBlockedApp_interceptsOnce() {
        engine.onForeground("tiktok")
        assertEquals(GuardEngine.Intercept("tiktok", expired = false), engine.tick(100, blocked, 0, coverVisible = false))
        // Cover is up / app forgotten: no second launch on the next ticks.
        assertNull(engine.tick(800, blocked, 0, coverVisible = true))
        assertNull(engine.tick(1500, blocked, 0, coverVisible = false))
    }

    @Test
    fun ownCoverScreenAndSystemUi_doNotCountAsLeaving() {
        engine.onForeground("tiktok")
        engine.tick(100, blocked, freeUntil = 1_000, coverVisible = false)
        engine.onForeground("com.snapbrain")
        engine.onForeground("com.android.systemui")
        assertEquals("tiktok", engine.current)
    }

    @Test
    fun comingBackAfterCover_interceptsAgain() {
        engine.onForeground("tiktok")
        engine.tick(100, blocked, 0, false)
        engine.onForeground("com.snapbrain")      // cover screen
        engine.onForeground("tiktok")             // user went back via Recents
        assertEquals(GuardEngine.Intercept("tiktok", expired = false), engine.tick(900, blocked, 0, false))
    }

    @Test
    fun freeTimeRunsOut_whileInsideApp_interceptsAsExpired() {
        engine.onForeground("tiktok")
        assertNull(engine.tick(100, blocked, freeUntil = 5_000, coverVisible = false))
        assertNull(engine.tick(4_900, blocked, freeUntil = 5_000, coverVisible = false))
        assertEquals(GuardEngine.Intercept("tiktok", expired = true), engine.tick(5_000, blocked, freeUntil = 5_000, coverVisible = false))
    }

    @Test
    fun otherApps_ignored() {
        engine.onForeground("maps")
        assertNull(engine.tick(100, blocked, 0, false))
        engine.onForeground("com.miui.home")
        assertNull(engine.tick(200, blocked, 0, false))
    }
}
