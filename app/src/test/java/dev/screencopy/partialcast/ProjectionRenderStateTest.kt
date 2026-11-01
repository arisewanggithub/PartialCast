package dev.screencopy.partialcast

import org.junit.Assert.*
import org.junit.Test

class ProjectionRenderStateTest {
    private val portrait = VideoSize(1080, 2376)
    private val landscape = portrait.rotated()

    @Test fun delayedLandscapeCallbackDoesNotRetireReturnedPortraitSession() {
        val content = CaptureResizeState(true)
        content.report(portrait)
        assertTrue(content.reconcile(portrait, 0))
        content.report(landscape)
        assertFalse(content.reconcile(portrait, 10))
        assertFalse(content.ready)
        assertTrue(content.waiting)
        content.report(portrait)
        assertTrue(content.reconcile(portrait, 20))
        assertTrue(content.ready)
        assertFalse(content.waiting)
        assertTrue(content.reconcile(portrait, 10_000))
    }
    @Test fun phoneFirstAndContentFirstRotationBothReconcile() {
        val content = CaptureResizeState(true)
        content.report(portrait)
        assertTrue(content.reconcile(portrait, 0))
        assertFalse(content.reconcile(landscape, 10))
        content.report(landscape)
        assertTrue(content.reconcile(landscape, 100))
        content.report(portrait)
        assertFalse(content.reconcile(landscape, 110))
        assertTrue(content.reconcile(portrait, 200))
    }
    @Test fun newestQueuedResizeWinsEvenWhenRenderThreadResumesAfterOldDeadline() {
        val content = CaptureResizeState(true)
        content.report(landscape)
        assertFalse(content.reconcile(portrait, 0))
        content.report(landscape)
        content.report(portrait)
        assertFalse("Output remains paused until the queued reports are reconciled", content.ready)
        assertTrue(content.reconcile(portrait, CaptureResizeState.SETTLE_TIMEOUT_MS * 10))
        assertFalse(content.waiting)
    }
    @Test fun persistentIncompatibleContentFailsWithinBoundInsteadOfPlayingWrongCrop() {
        val content = CaptureResizeState(true)
        content.report(VideoSize(600, 600))
        assertFalse(content.reconcile(portrait, 0))
        assertFalse(content.reconcile(portrait, CaptureResizeState.SETTLE_TIMEOUT_MS - 1))
        content.report(VideoSize(700, 600))
        val error = assertThrows(IllegalStateException::class.java) {
            content.reconcile(portrait, CaptureResizeState.SETTLE_TIMEOUT_MS)
        }
        assertTrue(error.message.orEmpty().contains("full default phone"))
        assertFalse(content.ready)
    }
    @Test fun initialSizeIsRequiredOnlyOnAndroid14AndInvalidDimensionsAreRejected() {
        val modern = CaptureResizeState(true)
        assertFalse(modern.ready)
        assertFalse(modern.reconcile(portrait, 10_000))
        assertFalse(modern.waiting)
        val legacy = CaptureResizeState(false)
        assertTrue(legacy.ready)
        assertTrue(legacy.reconcile(landscape, 0))
        assertThrows(IllegalArgumentException::class.java) { modern.report(VideoSize(0, 1)) }
    }
    @Test fun staticImageRepaintsOnOutputResizeWithoutAnotherCaptureFrame() {
        val frames = ProjectionFrameState()
        frames.frameAvailable()
        assertTrue(frames.queueDraw())
        assertTrue(frames.beginDraw())
        assertTrue(frames.canPresent(true))
        assertTrue(frames.queueDraw())
        assertFalse("Repaint must reuse the existing texture", frames.beginDraw())
        assertTrue(frames.canPresent(true))
    }
    @Test fun outputResizeBeforeFirstFrameCannotPresentAnUninitializedTexture() {
        val frames = ProjectionFrameState()
        assertTrue(frames.queueDraw())
        assertFalse(frames.beginDraw())
        assertFalse(frames.canPresent(true))
    }
    @Test fun windowResizeAppliedDuringSwapGetsAnotherCachedViewportRepaint() {
        val frames = ProjectionFrameState()
        frames.frameAvailable()
        frames.queueDraw()
        frames.beginDraw()
        val oldWindow = VideoSize(1920, 1080)
        assertTrue(frames.changedAfterSwap(oldWindow, 1080, 1920))
        assertTrue(frames.queueDraw())
        assertFalse(frames.beginDraw())
        assertTrue(frames.canPresent(true))
        assertFalse(frames.changedAfterSwap(VideoSize(1080, 1920), 1080, 1920))
    }
    @Test fun frameEventsCoalesceAndSourceResizeInvalidatesOldImage() {
        val frames = ProjectionFrameState()
        frames.frameAvailable()
        assertTrue(frames.queueDraw())
        frames.frameAvailable()
        assertFalse(frames.queueDraw())
        assertTrue(frames.beginDraw())
        assertTrue(frames.canPresent(true))
        val original = LocalCaptureGeometry.calculate(portrait, 0, LocalCaptureOptions(null))
        val resized = LocalCaptureGeometry.calculate(landscape, 1, LocalCaptureOptions(null))
        assertTrue(frames.captureGeometryChanged(original, resized))
        assertTrue(frames.queueDraw())
        assertFalse(frames.beginDraw())
        assertFalse(frames.canPresent(true))
        frames.frameAvailable()
        assertTrue(frames.queueDraw())
        assertTrue(frames.beginDraw())
        assertTrue(frames.canPresent(true))
    }
    @Test fun pendingResizeBlanksOutputButRetainsTextureForUnchangedCaptureGeometry() {
        val frames = ProjectionFrameState()
        frames.frameAvailable()
        frames.queueDraw()
        frames.beginDraw()
        assertFalse(frames.canPresent(false))
        assertTrue(frames.canPresent(true))
    }
    @Test fun finalAcquired180DegreeFrameSurvivesCropOnlyReconciliationWithoutNewFrame() {
        val options = LocalCaptureOptions(LocalCrop(400, 800, 100, 200))
        val before = LocalCaptureGeometry.calculate(portrait, 0, options)
        val after = LocalCaptureGeometry.calculate(portrait, 2, options)
        assertNotEquals(before.crop, after.crop)
        assertEquals(before.buffer, after.buffer)
        assertEquals(LocalRect(580, 1376, 400, 800), after.crop)
        val frames = ProjectionFrameState()
        frames.frameAvailable()
        frames.queueDraw()
        assertTrue(frames.beginDraw())
        assertFalse(frames.captureGeometryChanged(before, after))
        assertTrue(frames.queueDraw())
        assertFalse("Reconciliation must reuse the final already-acquired frame", frames.beginDraw())
        assertTrue(frames.canPresent(true))
        val coordinates = FloatArray(8)
        after.textureCoordinates(coordinates)
        assertEquals(580f / 1080, coordinates[0], .00001f)
        assertEquals(1f - 2176f / 2376, coordinates[1], .00001f)
    }
    @Test fun finalPending180DegreeFrameIsNotDiscardedByCropOnlyReconciliation() {
        val options = LocalCaptureOptions(LocalCrop(400, 800, 100, 200))
        val before = LocalCaptureGeometry.calculate(portrait, 0, options)
        val after = LocalCaptureGeometry.calculate(portrait, 2, options)
        val frames = ProjectionFrameState()
        frames.frameAvailable()
        frames.queueDraw()
        assertFalse(frames.captureGeometryChanged(before, after))
        assertTrue("Pending final frame must still be acquired", frames.beginDraw())
        assertTrue(frames.canPresent(true))
    }
}
