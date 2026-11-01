package dev.screencopy.partialcast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Looper
import android.view.Display
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeActivityTest {
    private val state = LocalDisplayState.shared
    private val previousUpdateFactory = HomeActivity.updateFactory
    private lateinit var controller: ActivityController<HomeActivity>
    private lateinit var output: VirtualDisplay
    private lateinit var consent: Intent
    private var consentCode = 0
    private lateinit var session: LocalDisplayState.Session
    private var destroyed = false
    private fun anotherHome() = Robolectric.buildActivity(HomeActivity::class.java).also {
        val activity = it.get()
        val manager = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)
        ReflectionHelpers.setField(activity, "mBase",
            activity.baseContext.createDisplayContext(manager.getDisplay(Display.DEFAULT_DISPLAY)))
        it.create().start().resume().visible()
    }
    private fun summary(activity: HomeActivity) =
        ReflectionHelpers.getField<TextView>(activity, "cropSummary").text.toString()

    private fun version(activity: HomeActivity) =
        activity.window.decorView.findViewWithTag<TextView>("app-version").text.toString()

    private fun saveWholeScreenThroughEditor(activity: HomeActivity) {
        ReflectionHelpers.getField<Button>(activity, "edit").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
        val matches = ArrayList<View>()
        checkNotNull(dialog.window).decorView.findViewsWithText(matches, "Crop ", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        val fields = matches.filterIsInstance<EditText>()
        assertEquals(4, fields.size)
        fields.forEach { it.setText("0") }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }

    @Before fun openHome() {
        HomeActivity.updateFactory = { noUpdateCheck(it) }
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("crop", 0).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(DisplayManager::class.java)
        output = checkNotNull(manager.createVirtualDisplay("Test presentation", 1920, 1080, 320, null,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY))
        controller = Robolectric.buildActivity(HomeActivity::class.java)
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "mBase",
            activity.baseContext.createDisplayContext(manager.getDisplay(Display.DEFAULT_DISPLAY)))
        controller.create().start().resume().visible()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        activity.beginPlay()
        session = checkNotNull(state.active)
        val request = checkNotNull(shadowOf(activity).nextStartedActivityForResult)
        consent = request.intent
        consentCode = request.requestCode
    }
    private fun grantConsent() {
        shadowOf(controller.get()).receiveResult(consent, Activity.RESULT_OK, Intent("test-consent"))
        val start = checkNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertEquals("start", start.action)
        assertEquals(session.token, start.getLongExtra("localSession", -1))
        state.playing(session, "Playing: simulated first frame")
    }
    @After fun cleanup() {
        if (!destroyed) controller.pause().stop().destroy()
        state.active?.let { state.finish(it, "Test cleanup") }
        state.updateTargets(emptyList())
        output.release()
        HomeActivity.updateFactory = previousUpdateFactory
    }
    @Test fun homeAndReturnKeepIdentityWithoutNewConsentOrServiceStart() {
        grantConsent()
        controller.pause().stop()
        assertSame(session, state.active)
        assertEquals(LocalDisplayState.Phase.PLAYING, session.phase)
        controller.start().resume().visible()
        assertSame(session, state.active)
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
    @Suppress("DEPRECATION")
    @Test fun homeDisplaysActualInstalledVersionNameWithoutBuildCode() {
        val activity = controller.get()
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
        assertEquals(activity.getString(R.string.app_version, installed.versionName), version(activity))
        assertFalse(version(activity).contains("(build"))
    }
    @Test fun versionIsInASeparateSectionAtTheBottomOfHomeContent() {
        val activity = controller.get()
        val section = activity.window.decorView.findViewWithTag<LinearLayout>("version-section")
        val root = section.parent as LinearLayout
        assertSame(section, root.getChildAt(root.childCount - 1))
        assertEquals(2, section.childCount)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, section.getChildAt(0).importantForAccessibility)
        val label = activity.window.decorView.findViewWithTag<TextView>("app-version")
        assertSame(section, label.parent)
        assertSame(label, section.getChildAt(1))
        assertTrue(section.paddingTop > 0)
        assertFalse(label.text.toString().contains("(build"))
    }
    @Suppress("DEPRECATION")
    @Test fun homeUsesReleaseMetadataRatherThanHardcodedDevelopmentVersion() {
        val context = RuntimeEnvironment.getApplication()
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        installed.versionName = "0.1.1"
        installed.longVersionCode = 10005
        shadowOf(context.packageManager).installPackage(installed)
        val release = anotherHome()
        try { assertEquals("Version 0.1.1", version(release.get())) }
        finally { release.pause().stop().destroy() }
    }
    @Suppress("DEPRECATION")
    @Test fun missingPackageVersionIsExplicitlyUnavailableNotAFalseDefault() {
        val context = RuntimeEnvironment.getApplication()
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        installed.versionName = null
        shadowOf(context.packageManager).installPackage(installed)
        val missing = anotherHome()
        try { assertEquals("Version unavailable", version(missing.get())) }
        finally { missing.pause().stop().destroy() }
    }
    @Test fun backgroundDenialReturnsToIdle() {
        controller.pause().stop()
        shadowOf(controller.get()).receiveResult(consent, Activity.RESULT_CANCELED, null)
        assertNull(state.active)
        controller.start().resume()
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
    @Test fun destructionStopsCapture() {
        grantConsent()
        controller.pause().stop().destroy()
        destroyed = true
        assertNull(state.active)
        assertTrue(state.message.contains("closed"))
    }
    @Test fun predictiveBackExplicitlyStopsInsteadOfBackgroundingRootActivity() {
        grantConsent()
        val callback = ReflectionHelpers.getField<android.window.OnBackInvokedCallback>(controller.get(), "backCallback")
        callback.onBackInvoked()
        assertTrue(controller.get().isFinishing)
        assertNull(state.active)
        assertTrue(state.message.contains("Back"))
    }
    @Test
    @Config(sdk = [30])
    fun legacyBackAlsoStopsAndFinishes() {
        grantConsent()
        controller.get().onBackPressed()
        assertTrue(controller.get().isFinishing)
        assertNull(state.active)
    }
    @Test fun disconnectWhileBackgroundedDoesNotRestartOnReturnOrReconnect() {
        grantConsent()
        controller.pause().stop()
        state.updateTargets(emptyList())
        assertNull(state.active)
        controller.start().resume()
        state.updateTargets(listOf(LocalDisplayTarget(output.display.displayId, "Reconnected")))
        assertNull(state.active)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
    }
    @Test fun grantedConsentWhileStoppedDefersForegroundStartUntilResume() {
        controller.pause().stop()
        shadowOf(controller.get()).receiveResult(consent, Activity.RESULT_OK, Intent("test-consent"))
        assertEquals(LocalDisplayState.Phase.STARTING, session.phase)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        controller.start().resume()
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        controller.pause().stop().start().resume()
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
    @Test fun stoppedPendingConsentCannotReviveCaptureOnReturn() {
        controller.pause().stop()
        LocalProjectionService.stop(session, "Stopped by user")
        shadowOf(controller.get()).receiveResult(consent, Activity.RESULT_OK, Intent("late-consent"))
        controller.start().resume()
        assertNull(state.active)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
    @Test fun lateConsentFromOldRequestDoesNotAuthorizeNewPlay() {
        LocalProjectionService.stop(session, "Stopped by user")
        controller.get().beginPlay()
        val replacement = checkNotNull(state.active)
        assertNotSame(session, replacement)
        val newConsent = checkNotNull(shadowOf(controller.get()).nextStartedActivityForResult)
        // Directly deliver the old request id; Intent equality is not a session identity.
        ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "onActivityResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, consentCode),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, Intent("late-consent")))
        assertSame(replacement, state.active)
        assertEquals(LocalDisplayState.Phase.CONSENT, replacement.phase)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        shadowOf(controller.get()).receiveResult(newConsent.intent, Activity.RESULT_OK, Intent("new-consent"))
        assertEquals(replacement.token, checkNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
            .getLongExtra("localSession", -1))
    }
    @Test fun destroyingOldHostDoesNotStopNewActivitysOwnedSession() {
        LocalProjectionService.stop(session, "Old session ended")
        controller.pause().stop()
        val replacement = anotherHome()
        try {
            replacement.get().beginPlay()
            val next = checkNotNull(state.active)
            val request = checkNotNull(shadowOf(replacement.get()).nextStartedActivityForResult).intent
            shadowOf(replacement.get()).receiveResult(request, Activity.RESULT_OK, Intent("new-consent"))
            assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
            state.playing(next, "Playing: replacement")
            controller.destroy()
            destroyed = true
            assertSame(next, state.active)
            assertSame(replacement.get(), HomeActivity.host(next))
        } finally { replacement.pause().stop().destroy() }
        assertNull(state.active)
    }
    @Test fun extraActivityDoesNotAdoptHostOrStopCaptureOnBackAndDestruction() {
        grantConsent()
        controller.pause().stop()
        val extra = anotherHome()
        try {
            assertSame(session, state.active)
            assertNull("Hidden true owner is not replaced by another Activity", HomeActivity.host(session))
            extra.get().onBackPressed()
            assertSame(session, state.active)
        } finally { extra.pause().stop().destroy() }
        assertSame(session, state.active)
        controller.start().resume()
        assertSame(controller.get(), HomeActivity.host(session))
    }
    @Test fun failedSaveCannotChangeTheNextPlaysFrozenCrop() {
        LocalProjectionService.stop(session, "Preparing another Play")
        val context = RuntimeEnvironment.getApplication()
        val failing = CropStore(FailingCropPreferences(context.getSharedPreferences("crop", 0), 1))
        assertThrows(IllegalStateException::class.java) {
            failing.save(CropSettings(100, 100, 0, 0), PhoneSnapshot(VideoSize(1080, 2376), 0, 320))
        }
        controller.get().beginPlay()
        assertNull(checkNotNull(state.active).options.crop)
    }
    @Test fun returningHomeShowsTheCropSavedByAnotherActivityBeforePlay() {
        LocalProjectionService.stop(session, "Preparing shared crop edit")
        val store = CropStore(RuntimeEnvironment.getApplication())
        val restricted = CropSettings(100, 120, 10, 20)
        store.save(restricted, PhoneSnapshot(VideoSize(1080, 2376), 0, 320))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(restricted.options().summary(), summary(controller.get()))
        controller.pause().stop()
        val other = anotherHome()
        try { saveWholeScreenThroughEditor(other.get()) }
        finally { other.pause().stop().destroy() }
        controller.start().resume()
        assertEquals("Whole phone screen", summary(controller.get()))
        controller.get().beginPlay()
        assertNull(checkNotNull(state.active).options.crop)
    }
    @Test fun simultaneouslyResumedHomeUpdatesWithoutAnotherResumeCallback() {
        LocalProjectionService.stop(session, "Preparing multi-resume edit")
        val store = CropStore(RuntimeEnvironment.getApplication())
        val phone = PhoneSnapshot(VideoSize(1080, 2376), 0, 320)
        val restricted = CropSettings(100, 120, 10, 20)
        store.save(restricted, phone)
        shadowOf(Looper.getMainLooper()).idle()
        val other = anotherHome()
        var closed = false
        try {
            saveWholeScreenThroughEditor(other.get())
            assertEquals("Whole phone screen", summary(controller.get()))
            val oldView = ReflectionHelpers.getField<TextView>(other.get(), "cropSummary")
            other.pause().stop().destroy()
            closed = true
            store.save(restricted, phone)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Destroyed Home must no longer observe changes", "Whole phone screen", oldView.text.toString())
            assertEquals(restricted.options().summary(), summary(controller.get()))
            store.save(CropSettings(0, 0, 0, 0), phone)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Whole phone screen", summary(controller.get()))
            controller.get().beginPlay()
            assertNull(checkNotNull(state.active).options.crop)
        } finally { if (!closed) other.pause().stop().destroy() }
    }
}
