package dev.screencopy.partialcast

import android.app.AlertDialog
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

internal class ImmediateUpdateWorker : AbstractExecutorService() {
    private var closed = false
    override fun execute(command: Runnable) { command.run() }
    override fun shutdown() { closed = true }
    override fun shutdownNow(): MutableList<Runnable> { closed = true; return mutableListOf() }
    override fun isShutdown() = closed
    override fun isTerminated() = closed
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
}
internal fun noUpdateCheck(version: String) = LaunchUpdateCheck(object : UpdateSource {
    override fun check(installedVersion: String): ReleaseUpdate? = null
    override fun cancel() = Unit
}, version, ImmediateUpdateWorker())

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class LaunchUpdateTest {
    private val previousFactory = HomeActivity.updateFactory
    private lateinit var home: ActivityController<HomeActivity>
    private var checks = 0
    private var cancelled = 0
    private var result: ReleaseUpdate? = ReleaseUpdate("0.2.0", "https://github.com/arisewanggithub/PartialCast/releases/tag/v0.2.0")
    private var failure = false
    private var output: android.hardware.display.VirtualDisplay? = null
    private fun open() {
        home = Robolectric.buildActivity(HomeActivity::class.java)
        val display = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        ReflectionHelpers.setField(home.get(), "mBase", home.get().baseContext.createDisplayContext(display))
        home.create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Before fun configure() {
        HomeActivity.updateFactory = { version ->
            LaunchUpdateCheck(object : UpdateSource {
                override fun check(installedVersion: String): ReleaseUpdate? {
                    checks++
                    if (failure) error("Fixture metadata request failed")
                    return result
                }
                override fun cancel() { cancelled++ }
            }, version, ImmediateUpdateWorker())
        }
    }
    @After fun cleanup() {
        if (::home.isInitialized) home.pause().stop().destroy()
        LocalDisplayState.shared.active?.let { LocalDisplayState.shared.finish(it) }
        LocalDisplayState.shared.updateTargets(emptyList())
        output?.release()
        HomeActivity.updateFactory = previousFactory
    }
    @Test fun newerReleasePromptsOnceAndLaterDoesNotCheckAgainOnResume() {
        open()
        val dialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        home.pause().stop().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, checks)
        assertFalse(dialog.isShowing)
    }
    @Test fun currentVersionDoesNotShowDialog() {
        result = null
        open()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(1, checks)
    }
    @Test fun configurationRecreationKeepsTheSingleCheckAndLaterChoice() {
        open()
        checkNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        home.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, checks)
        assertFalse(checkNotNull(ShadowAlertDialog.getLatestAlertDialog()).isShowing)
    }
    @Test fun resultBeforeResumeWaitsForForegroundWithoutNewRequest() {
        home = Robolectric.buildActivity(HomeActivity::class.java)
        val display = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        ReflectionHelpers.setField(home.get(), "mBase", home.get().baseContext.createDisplayContext(display))
        home.create().start()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        home.resume().visible()
        assertTrue(checkNotNull(ShadowAlertDialog.getLatestAlertDialog()).isShowing)
        assertEquals(1, checks)
    }
    @Test fun failedCheckIsSilentAndNeverRetriesOnOrdinaryResume() {
        failure = true
        open()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        home.pause().stop().start().resume()
        assertEquals(1, checks)
    }
    @Test fun activeCaptureIsNotStoppedAndPromptWaitsUntilIdle() {
        val state = LocalDisplayState.shared
        openIdleWithNoUpdate()
        val manager = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)
        val display = checkNotNull(manager.createVirtualDisplay("Fixture", 1920, 1080, 320, null,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY))
        output = display
        state.updateTargets(listOf(LocalDisplayTarget(display.display.displayId, "Fixture")))
        val session = state.reserve(display.display.displayId, LocalCaptureOptions(null))
        state.consent(session, true)
        state.playing(session, "Playing fixture")
        result = ReleaseUpdate("0.2.0", "https://github.com/arisewanggithub/PartialCast/releases/tag/v0.2.0")
        home.pause().stop().destroy()
        open()
        assertSame(session, state.active)
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        state.finish(session)
        assertTrue(checkNotNull(ShadowAlertDialog.getLatestAlertDialog()).isShowing)
    }
    private fun openIdleWithNoUpdate() {
        result = null
        open()
    }
    @Test fun upgradeOpensReleasePageOnlyAfterExplicitPressWithoutInstaller() {
        open()
        assertNull(shadowOf(home.get()).nextStartedActivity)
        checkNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val intent = checkNotNull(shadowOf(home.get()).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(result?.page, intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }
    @Test fun retainedLaunchDoesNotRunAnotherCheckWhenReattached() {
        val retained = HomeActivity.updateFactory("0.1.2")
        var notifications = 0
        retained.attach { notifications++ }
        retained.start()
        shadowOf(Looper.getMainLooper()).idle()
        retained.detach()
        retained.attach { notifications++ }
        retained.start()
        assertEquals(1, checks)
        assertEquals(1, notifications)
        assertNotNull(retained.available)
        retained.close()
        assertEquals(1, cancelled)
    }
    @Test fun destroyedLaunchRejectsLateResultAndReleasesListener() {
        val retained = HomeActivity.updateFactory("0.1.2")
        var notifications = 0
        retained.attach { notifications++ }
        retained.start()
        retained.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, notifications)
        assertNull(retained.available)
    }
}
