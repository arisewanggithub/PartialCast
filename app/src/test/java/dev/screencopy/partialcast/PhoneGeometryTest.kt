package dev.screencopy.partialcast

import android.hardware.display.DisplayManager
import android.app.Activity
import android.content.res.Configuration
import android.view.Display
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33, 34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneGeometryTest {
    class FrozenActivity : Activity() {
        init { applyOverrideConfiguration(Configuration(RuntimeEnvironment.getApplication().resources.configuration)) }
    }
    @Test
    @Config(sdk = [33])
    fun lockedPhoneIsRejectedEvenWithoutScreenOffBroadcast() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        val error = assertThrows(IllegalStateException::class.java) { PhoneGeometry(app).read() }
        assertTrue(error.message.orEmpty().contains("locked"))
    }
    @Test fun stoppedActivityDoesNotOwnLiveDefaultDisplayBoundsOrRotation() {
        val app = RuntimeEnvironment.getApplication()
        RuntimeEnvironment.setQualifiers("w1080dp-h2376dp-port-mdpi")
        ShadowDisplayManager.changeDisplay(0, "w1080dp-h2376dp-port-mdpi")
        val manager = app.getSystemService(DisplayManager::class.java)
        shadowOf(manager.getDisplay(0)).setRotation(0)
        val controller = Robolectric.buildActivity(FrozenActivity::class.java)
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "mBase",
            activity.baseContext.createDisplayContext(manager.getDisplay(Display.DEFAULT_DISPLAY)))
        controller.create().start().resume().visible().pause().stop()
        val provider = PhoneGeometry(activity)
        val options = LocalCaptureOptions(LocalCrop(400, 800, 100, 200))
        try {
            for (rotation in listOf(0, 1, 2, 3, 0)) {
                val size = if (rotation % 2 == 0) VideoSize(1080, 2376) else VideoSize(2376, 1080)
                ShadowDisplayManager.changeDisplay(0, if (rotation % 2 == 0)
                    "w1080dp-h2376dp-port-mdpi" else "w2376dp-h1080dp-land-mdpi")
                // Robolectric's window-context server gets bounds from the runtime configuration.
                RuntimeEnvironment.setQualifiers(if (rotation % 2 == 0)
                    "w1080dp-h2376dp-port-mdpi" else "w2376dp-h1080dp-land-mdpi")
                shadowOf(manager.getDisplay(0)).setRotation(rotation)
                val snapshot = provider.read()
                if (android.os.Build.VERSION.SDK_INT == 33) {
                    val stale = activity.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                    assertEquals("Frozen hidden Activity still has portrait window bounds", 1080, stale.width())
                    assertEquals(2376, stale.height())
                }
                assertEquals("Live logical size at rotation $rotation", size, snapshot.size)
                assertEquals(rotation, snapshot.rotation)
                val geometry = LocalCaptureGeometry.calculate(snapshot.size, snapshot.rotation, options)
                assertEquals(size, geometry.buffer)
                assertEquals(if (rotation % 2 == 0) 400 else 800, geometry.crop.width)
                val output = geometry.viewport(VideoSize(1920, 1080))
                assertEquals(geometry.crop.width.toDouble() / geometry.crop.height,
                    output.width.toDouble() / output.height, .002)
            }
        } finally { controller.destroy() }
    }
}
