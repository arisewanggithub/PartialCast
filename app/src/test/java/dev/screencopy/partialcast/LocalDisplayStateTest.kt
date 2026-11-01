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
}
