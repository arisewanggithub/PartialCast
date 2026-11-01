package dev.screencopy.partialcast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Looper
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Display
import android.view.Surface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Real service/window lifecycles, injected GPU boundary; no JVM or physical screen capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [LocalProjectionServiceTest.ConsentManager::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalProjectionServiceTest {
    @Implements(MediaProjectionManager::class)
    class ConsentManager {
        @Implementation
        fun getMediaProjection(resultCode: Int, data: Intent): MediaProjection? =
            if (resultCode == Activity.RESULT_OK && data.action == "fixture-consent")
                Shadow.newInstanceOf(MediaProjection::class.java) else null
    }
    private class Output : ProjectionOutput {
        var closeCalls = 0
        var starts = 0
        val outputSizes = ArrayList<VideoSize>()
        var completed: ((String?) -> Unit)? = null
        override fun start(surface: Surface, width: Int, height: Int) { starts++; outputSizes.add(VideoSize(width, height)) }
        override fun resizeOutput(width: Int, height: Int) { outputSizes.add(VideoSize(width, height)) }
        override fun phoneChanged() = Unit
        override fun close(done: (String?) -> Unit) { closeCalls++; completed = done }
    }
    private val state = LocalDisplayState.shared
    private val defaultFactory = LocalProjectionService.rendererFactory
    private lateinit var activity: ActivityController<HomeActivity>
    private lateinit var service: ServiceController<LocalProjectionService>
    private lateinit var display: VirtualDisplay
    private lateinit var session: LocalDisplayState.Session
    private lateinit var output: Output
    private lateinit var progress: (String) -> Unit
    private lateinit var failed: (String) -> Unit
    private lateinit var start: Intent
    private var serviceDestroyed = false
    private var factoryCalls = 0

    @Before fun startOwnedService() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("crop", 0).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(DisplayManager::class.java)
        display = checkNotNull(manager.createVirtualDisplay("Fixture", 1920, 1080, 320, null,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY))
        activity = Robolectric.buildActivity(HomeActivity::class.java)
        ReflectionHelpers.setField(activity.get(), "mBase",
            activity.get().baseContext.createDisplayContext(manager.getDisplay(Display.DEFAULT_DISPLAY)))
        activity.create().start().resume().visible()
        activity.get().beginPlay()
        session = checkNotNull(state.active)
        val consent = checkNotNull(shadowOf(activity.get()).nextStartedActivityForResult).intent
        shadowOf(activity.get()).receiveResult(consent, Activity.RESULT_OK, Intent("fixture-consent"))
        start = checkNotNull(shadowOf(app).nextStartedService)
        output = Output()
        LocalProjectionService.rendererFactory = { _, _, _, failure, detail, _ ->
            factoryCalls++
            failed = failure
            progress = detail
            output
        }
        service = Robolectric.buildService(LocalProjectionService::class.java).create()
        service.get().onStartCommand(start, 0, 1)
        assertTrue("Service must reach injected GPU boundary: ${state.message}", ::progress.isInitialized)
    }
    private fun frame() {
        progress("Playing: simulated frame")
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun complete(error: String? = null) {
        checkNotNull(output.completed)(error)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @After fun cleanup() {
        if (!serviceDestroyed) service.destroy()
        if (::output.isInitialized && output.completed != null) complete()
        activity.pause().stop().destroy()
        // Reset only the test singleton after a deliberately failed cleanup gate.
        state.active?.let { ReflectionHelpers.setField(it, "cleanupFailed", false); state.finish(it, "Test cleanup") }
        state.updateTargets(emptyList())
        display.release()
        LocalProjectionService.rendererFactory = defaultFactory
    }
    @Test fun backgroundAndReturnDoNotStopServiceAndStaleStopIsRejected() {
        frame()
        activity.pause().stop()
        assertEquals(0, output.closeCalls)
        assertSame(session, state.active)
        service.get().onStartCommand(Intent().setAction("stop").putExtra("localSession", session.token + 1), 0, 2)
        assertEquals(0, output.closeCalls)
        activity.start().resume()
        assertSame(session, state.active)
        assertNull(shadowOf(activity.get()).nextStartedActivityForResult)
    }
    @Test fun taskRemovalHoldsGateUntilOwnedCleanupAndRejectsLateFrame() {
        frame()
        service.get().onTaskRemoved(Intent())
        assertEquals(1, output.closeCalls)
        assertSame(session, state.active)
        assertEquals(LocalDisplayState.Phase.STOPPING, session.phase)
        frame()
        assertEquals(LocalDisplayState.Phase.STOPPING, session.phase)
        LocalProjectionService.stop(session, "repeated")
        assertEquals(1, output.closeCalls)
        complete()
        assertNull(state.active)
    }
    @Test fun predictiveBackStopsButKeepsGateUntilGpuCleanupCompletes() {
        frame()
        val callback = ReflectionHelpers.getField<android.window.OnBackInvokedCallback>(activity.get(), "backCallback")
        callback.onBackInvoked()
        assertTrue(activity.get().isFinishing)
        assertEquals(1, output.closeCalls)
        assertSame(session, state.active)
        assertEquals(LocalDisplayState.Phase.STOPPING, session.phase)
        assertThrows(IllegalStateException::class.java) { state.reserve(session.target.id, session.options) }
        complete()
        assertNull(state.active)
    }
    @Test fun serviceDestructionRetiresAndWaitsForAsyncCleanup() {
        frame()
        service.destroy()
        serviceDestroyed = true
        assertEquals(1, output.closeCalls)
        assertSame(session, state.active)
        LocalProjectionService.stop(session, "Host closed after service destruction")
        assertSame("Destroyed service still owns pending GPU cleanup", session, state.active)
        assertThrows(IllegalStateException::class.java) { state.reserve(session.target.id, session.options) }
        complete()
        assertNull(state.active)
    }
    @Test fun destroyingExtraControlActivityDoesNotRetireThePresentationOwnersRenderer() {
        frame()
        activity.pause().stop()
        val extra = Robolectric.buildActivity(HomeActivity::class.java)
        val manager = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)
        ReflectionHelpers.setField(extra.get(), "mBase",
            extra.get().baseContext.createDisplayContext(manager.getDisplay(Display.DEFAULT_DISPLAY)))
        extra.create().start().resume().visible().pause().stop().destroy()
        assertSame(session, state.active)
        assertEquals(0, output.closeCalls)
        activity.start().resume()
        assertSame(activity.get(), HomeActivity.host(session))
        assertEquals(1, factoryCalls)
    }
    @Test fun projectionStopCallbackAndCleanupErrorVisiblyBlockNewPlay() {
        frame()
        failed("Android stopped screen capture")
        shadowOf(Looper.getMainLooper()).idle()
        complete("EGL cleanup failed")
        assertTrue(session.cleanupFailed)
        assertSame(session, state.active)
        assertTrue(state.message.contains("Restart the app"))
        assertThrows(IllegalStateException::class.java) { state.reserve(session.target.id, session.options) }
    }
    @Test fun noFirstFrameIsActionableAndCleanupTimeoutKeepsGate() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(1, output.closeCalls)
        assertTrue(state.message.contains("surface") || state.message.contains("frame"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(session.cleanupFailed)
        assertTrue(state.message.contains("cleanup"))
        complete()
        assertSame(session, state.active)
    }
    @Test fun presentationDismissalRetiresWindowAndOnlyOneCleanupRuns() {
        frame()
        val window = ReflectionHelpers.getField<android.app.Presentation>(service.get(), "presentation")
        window.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, output.closeCalls)
        complete()
        assertNull(state.active)
    }
    @Test fun screenOffStopsOwnedCaptureWithoutRestartOnScreenOn() {
        frame()
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, output.closeCalls)
        assertTrue(state.message.contains("locked"))
        complete()
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(state.active)
    }
    @Test fun outputSurfaceLossIsTerminal() {
        frame()
        val presentation = ReflectionHelpers.getField<android.app.Presentation>(service.get(), "presentation")
        val surface = presentation.findViewById<android.view.ViewGroup>(android.R.id.content)
            .getChildAt(0) as android.view.SurfaceView
        val callbacks = shadowOf(surface).fakeSurfaceHolder.callbacks
        callbacks.toList().forEach { it.surfaceDestroyed(surface.holder) }
        assertEquals(1, output.closeCalls)
        assertTrue(state.message.contains("surface lost"))
        complete()
        assertNull(state.active)
    }
    @Test fun outputResizesRouteToSameOwnerAndDuplicateStartupDoesNotReuseConsent() {
        val presentation = ReflectionHelpers.getField<android.app.Presentation>(service.get(), "presentation")
        val view = presentation.findViewById<android.view.ViewGroup>(android.R.id.content)
            .getChildAt(0) as android.view.SurfaceView
        val texture = android.graphics.SurfaceTexture(0)
        val surface = Surface(texture)
        val holder = object : android.view.SurfaceHolder by view.holder {
            override fun getSurface() = surface
        }
        try {
            val callbacks = shadowOf(view).fakeSurfaceHolder.callbacks
            for (size in listOf(VideoSize(1920, 1080), VideoSize(1080, 1920), VideoSize(1920, 1080)))
                callbacks.forEach { it.surfaceChanged(holder, 0, size.width, size.height) }
            service.get().onStartCommand(start, 0, 2)
            assertEquals(1, factoryCalls)
            assertEquals(1, output.starts)
            assertEquals(listOf(VideoSize(1920, 1080), VideoSize(1080, 1920), VideoSize(1920, 1080)), output.outputSizes)
            assertSame(session, state.active)
            assertNull(shadowOf(activity.get()).nextStartedActivityForResult)
        } finally { surface.release(); texture.release() }
    }
}
