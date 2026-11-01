package dev.screencopy.partialcast

import android.hardware.display.DisplayManager
import android.view.Display
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocalDisplayDiscoveryTest {
    @Test fun publicPresentationEligibilityExcludesDefaultPrivateOwnAndInvalidDisplays() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)
        assertFalse(LocalDisplayDiscovery.eligible(manager.getDisplay(Display.DEFAULT_DISPLAY), -1))
        val public = checkNotNull(manager.createVirtualDisplay("public", 1920, 1080, 320, null,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY))
        val private = checkNotNull(manager.createVirtualDisplay("private", 1920, 1080, 320, null,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION))
        assertTrue(LocalDisplayDiscovery.eligible(public.display, -1))
        assertFalse(LocalDisplayDiscovery.eligible(public.display, public.display.displayId))
        assertFalse(LocalDisplayDiscovery.eligible(private.display, -1))
        val old = public.display
        public.release()
        assertFalse(LocalDisplayDiscovery.eligible(old, -1))
        private.release()
    }
}
