package dev.screencopy.partialcast

import org.junit.Assert.*
import org.junit.Test

class LocalDisplayStateTest {
    private fun rig() = LocalDisplayState().apply {
        updateTargets(listOf(LocalDisplayTarget(2, "Presentation"), LocalDisplayTarget(3, "Other")))
    }
    private fun LocalDisplayState.play() = reserve(2, LocalCaptureOptions(null))
    @Test fun reservesPositiveIdentityBeforeConsentAndBlocksSecondPlay() {
        val state = rig()
        val session = state.play()
        assertTrue(session.token > 0)
        assertEquals(LocalDisplayState.Phase.CONSENT, session.phase)
        assertThrows(IllegalStateException::class.java) { state.play() }
        state.playing(session, "Frame before consent")
        assertEquals(LocalDisplayState.Phase.CONSENT, session.phase)
    }
    @Test fun denialAndLateConsentCannotStartReplacement() {
        val state = rig()
        val denied = state.play()
        assertFalse(state.consent(denied, false))
        assertNull(state.active)
        val next = state.play()
        assertNotEquals(denied.token, next.token)
        assertFalse(state.consent(denied, true))
        state.finish(denied)
        assertSame(next, state.active)
    }
    @Test fun consentSingleUseStopAndLateFrameResizeAreTerminal() {
        val state = rig()
        val session = state.play()
        assertTrue(state.consent(session, true))
        assertFalse(state.consent(session, true))
        assertTrue(state.stop(session))
        state.playing(session, "late frame")
        state.detail(session, "late resize")
        assertEquals(LocalDisplayState.Phase.STOPPING, session.phase)
        assertEquals("Stopped by user", state.message)
        assertFalse(state.stop(session))
        assertThrows(IllegalStateException::class.java) { state.play() }
        state.finish(session)
        assertNull(state.active)
    }
    @Test fun disconnectReconnectStaysIdleAndOldCleanupCannotReleaseNextOwner() {
        val state = rig()
        val old = state.play()
        state.consent(old, true)
        state.playing(old, "frame")
        state.updateTargets(emptyList())
        assertEquals(LocalDisplayState.Phase.STOPPING, old.phase)
        state.finish(old)
        state.updateTargets(listOf(LocalDisplayTarget(2, "Reconnected")))
        assertNull(state.active)
        val next = state.play()
        state.finish(old)
        state.playing(old, "obsolete")
        assertSame(next, state.active)
        state.finish(next)
        state.finish(next)
        assertNull(state.active)
    }
    @Test fun cleanupFailureRequiresProcessRestartEvenAfterLateSuccessfulCleanup() {
        val state = rig()
        val session = state.play()
        state.stop(session)
        state.finish(session, "EGL teardown failed", false)
        assertTrue(session.cleanupFailed)
        state.finish(session)
        assertSame(session, state.active)
        assertTrue(state.message.contains("Restart the app"))
        assertThrows(IllegalStateException::class.java) { state.play() }
    }
    @Test fun enabledReconnectRequiresStartedCaptureAndUniqueMatchingNameAndFlags() {
        val state = rig()
        val pending = state.reserve(2, LocalCaptureOptions(null), autoResume = true)
        state.updateTargets(emptyList())
        assertEquals(LocalDisplayState.Phase.STOPPING, pending.phase)
        state.finish(pending)
        state.updateTargets(listOf(LocalDisplayTarget(2, "Presentation", 2)))
        val session = state.reserve(2, LocalCaptureOptions(null), autoResume = true)
        state.consent(session, true)
        state.playing(session, "frame")
        state.updateTargets(emptyList())
        assertEquals(LocalDisplayState.Phase.WAITING_DISPLAY, session.phase)
        state.playing(session, "late frame")
        assertEquals(LocalDisplayState.Phase.WAITING_DISPLAY, session.phase)
        state.updateTargets(listOf(LocalDisplayTarget(2, "Other", 2), LocalDisplayTarget(4, "Presentation", 3)))
        assertNull(state.reconnectTarget(session))
        val target = LocalDisplayTarget(5, "Presentation", 2)
        state.updateTargets(listOf(target, LocalDisplayTarget(6, "Presentation", 2)))
        assertNull(state.reconnectTarget(session))
        state.updateTargets(listOf(target))
        assertTrue(state.reconnect(session, target))
        assertSame(session, state.active)
        assertEquals(5, session.target.id)
        assertEquals(LocalDisplayState.Phase.STARTING, session.phase)
        state.playing(session, "resumed")
        state.updateTargets(emptyList())
        state.stop(session)
        state.updateTargets(listOf(target))
        assertFalse(state.reconnect(session, target))
        assertEquals(LocalDisplayState.Phase.STOPPING, session.phase)
    }
}
