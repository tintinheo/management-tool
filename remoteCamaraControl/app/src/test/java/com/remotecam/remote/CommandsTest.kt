package com.remotecam.remote

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {

    @Test fun playAndPauseBothFireShutter() {
        assertEquals(RemoteCommand.SHUTTER, CommandMapper.forSignal(SessionSignal.PLAY))
        assertEquals(RemoteCommand.SHUTTER, CommandMapper.forSignal(SessionSignal.PAUSE))
    }

    @Test fun otherButtonsMapToCameraActions() {
        assertEquals(RemoteCommand.SWITCH_CAMERA, CommandMapper.forSignal(SessionSignal.NEXT))
        assertEquals(RemoteCommand.TIMER_SHOT, CommandMapper.forSignal(SessionSignal.PREVIOUS))
        assertEquals(RemoteCommand.ZOOM_IN, CommandMapper.forSignal(SessionSignal.VOLUME_UP))
        assertEquals(RemoteCommand.ZOOM_OUT, CommandMapper.forSignal(SessionSignal.VOLUME_DOWN))
        assertNull(CommandMapper.forSignal(SessionSignal.STOP))
    }

    @Test fun mediaKeysMapToSignals() {
        assertEquals(SessionSignal.PAUSE, CommandMapper.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(SessionSignal.PAUSE, CommandMapper.forKeyCode(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(SessionSignal.NEXT, CommandMapper.forKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(SessionSignal.PREVIOUS, CommandMapper.forKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertNull(CommandMapper.forKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test fun debouncerDropsRepeatsInsideWindowOnly() {
        val debouncer = CommandDebouncer(windowMs = 400)
        assertTrue(debouncer.accept(RemoteCommand.SHUTTER, 1_000))
        assertFalse(debouncer.accept(RemoteCommand.SHUTTER, 1_300))
        assertTrue(debouncer.accept(RemoteCommand.SWITCH_CAMERA, 1_300))
        assertTrue(debouncer.accept(RemoteCommand.SHUTTER, 1_401))
    }
}
