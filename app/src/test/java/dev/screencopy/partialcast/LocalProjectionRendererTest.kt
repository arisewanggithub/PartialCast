package dev.screencopy.partialcast

import android.opengl.EGL14
import org.junit.Assert.*
import org.junit.Test

class LocalProjectionRendererTest {
    @Test fun unavailableNativeWindowIsRecoverableAndHasNoPartialSurfaceToRelease() {
        var releases = 0
        val error = assertThrows(OutputSurfaceException::class.java) {
            bindOutputSurface(
                create = {
                    checkOutputResult(false, "create output surface") { EGL14.EGL_BAD_NATIVE_WINDOW }
                    1
                },
                bind = {},
                release = { releases++ })
        }
        assertTrue(error.message.orEmpty().contains("create output surface"))
        assertEquals(0, releases)
    }
    @Test fun failedBindingReleasesPartialSurfaceBeforeRecoveryAndRetry() {
        val releases = ArrayList<Int>()
        assertThrows(OutputSurfaceException::class.java) {
            bindOutputSurface(
                create = { 1 },
                bind = { checkOutputResult(false, "make current") { EGL14.EGL_BAD_SURFACE } },
                release = releases::add)
        }
        assertEquals(listOf(1), releases)
        val replacement = bindOutputSurface(create = { 2 }, bind = {}, release = releases::add)
        assertEquals(2, replacement)
        assertEquals("The successfully bound replacement remains owned", listOf(1), releases)
    }
    @Test fun unrelatedEglFailuresRemainTerminalButReleasePartialResources() {
        val releases = ArrayList<Int>()
        val error = assertThrows(IllegalStateException::class.java) {
            bindOutputSurface(
                create = { 1 },
                bind = { checkOutputResult(false, "make current") { EGL14.EGL_BAD_ALLOC } },
                release = releases::add)
        }
        assertFalse(error is OutputSurfaceException)
        assertEquals(listOf(1), releases)
    }
    @Test fun cleanupFailureIsTerminalAndPreservesOriginalOutputLoss() {
        val cleanup = IllegalStateException("Could not park the context")
        val error = assertThrows(IllegalStateException::class.java) {
            bindOutputSurface(
                create = { 1 },
                bind = { checkOutputResult(false, "make current") { EGL14.EGL_BAD_SURFACE } },
                release = { throw cleanup })
        }
        assertSame(cleanup, error)
        assertEquals(1, error.suppressed.size)
        assertTrue(error.suppressed.single() is OutputSurfaceException)
    }
}
