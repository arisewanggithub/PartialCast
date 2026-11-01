package dev.screencopy.partialcast

import android.app.AlertDialog
import android.hardware.display.DisplayManager
import android.content.res.Configuration
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class CropDialogLayoutTest {
    private val previousUpdateFactory = HomeActivity.updateFactory
    private lateinit var controller: ActivityController<HomeActivity>
    private lateinit var dialog: AlertDialog
    private lateinit var fields: List<EditText>
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun dp(value: Int) = (value * controller.get().resources.displayMetrics.density).toInt()

    @Before fun openEditor() {
        HomeActivity.updateFactory = { noUpdateCheck(it) }
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("crop", 0).edit().clear().commit()
        controller = Robolectric.buildActivity(HomeActivity::class.java)
        val activity = controller.get()
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        ReflectionHelpers.setField(activity, "mBase", activity.baseContext.createDisplayContext(display))
        controller.create().start().resume().visible()
        views(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Edit crop" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        dialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
        fields = views(checkNotNull(dialog.window).decorView).filterIsInstance<EditText>()
        root = (fields.first().parent as LinearLayout).parent as LinearLayout
        scroll = root.parent as ScrollView
    }
    @After fun cleanup() {
        dialog.dismiss()
        controller.pause().stop().destroy()
        HomeActivity.updateFactory = previousUpdateFactory
    }
    @Test fun contentIsPaddedAndFourCompactRowsKeepActionsOutsideScroll() {
        assertEquals(dp(24), root.paddingLeft)
        assertEquals(dp(24), root.paddingRight)
        assertEquals(4, fields.size)
        assertEquals(listOf("Crop Width", "Crop Height", "Crop X", "Crop Y"),
            fields.map { it.contentDescription.toString() })
        fields.forEachIndexed { index, field ->
            val row = field.parent as LinearLayout
            assertEquals(LinearLayout.HORIZONTAL, row.orientation)
            assertEquals(2, row.childCount)
            val parameters = field.layoutParams as LinearLayout.LayoutParams
            assertEquals(0, parameters.width)
            assertEquals(1f, parameters.weight, 0f)
            assertTrue(field.minHeight >= dp(48))
            assertEquals(if (index == 3) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT, field.imeOptions)
            assertEquals("0", field.text.toString())
        }
        assertFalse(views(scroll).contains(dialog.getButton(AlertDialog.BUTTON_POSITIVE)))
        assertFalse(views(scroll).contains(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)))
        val mode = checkNotNull(dialog.window).attributes.softInputMode
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN, mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
    }
    @Test fun shortLandscapeOrKeyboardViewportCanScrollToLastField() {
        for ((width, height) in listOf(320 to 800, 640 to 220, 320 to 160)) {
            scroll.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.AT_MOST))
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
            assertTrue(scroll.height <= dp(height))
            if (height < 300) {
                assertTrue("Form must scroll in $width x $height viewport", root.height > scroll.height)
                scroll.scrollTo(0, root.height - scroll.height)
                assertTrue(scroll.scrollY > 0)
                val lastRow = fields.last().parent as LinearLayout
                assertTrue("Y row is reachable", lastRow.bottom <= scroll.scrollY + scroll.height)
            }
        }
    }
    @Test fun invalidSaveShowsErrorWithoutDismissingOrRewritingDraft() {
        fields[0].setText("0")
        fields[1].setText("20")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing)
        assertEquals("20", fields[1].text.toString())
        val error = root.getChildAt(root.childCount - 1) as TextView
        assertEquals(View.VISIBLE, error.visibility)
        assertTrue(error.text.toString().contains("positive"))
        assertEquals(CropSettings(0, 0, 0, 0), CropStore(controller.get()).load())
    }
    @Test fun cancelDoesNotSaveEditedValues() {
        fields.first().setText("123")
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(CropSettings(0, 0, 0, 0), CropStore(controller.get()).load())
    }
    private fun preview() = root.findViewWithTag<TextView>("crop-preview").text.toString()
    private fun resolution() = root.findViewWithTag<TextView>("crop-resolution").text.toString()
    @Test fun initialPreviewUsesLiveNaturalResolutionAndFullScreenForAllZero() {
        val snapshot = PhoneGeometry(controller.get()).read()
        val natural = snapshot.naturalSize
        assertEquals(controller.get().getString(R.string.crop_natural_resolution, natural.width, natural.height), resolution())
        assertEquals(controller.get().getString(R.string.crop_effective_preview, natural.width, natural.height, 0, 0), preview())
        assertTrue(root.findViewWithTag<CropSketchView>("crop-sketch").contentDescription.toString().contains("natural orientation"))
    }
    @Test fun typingClipsPreviewWithoutRewritingOrSavingAndInvalidOriginClearsSelection() {
        val natural = PhoneGeometry(controller.get()).read().naturalSize
        fields[0].setText(Int.MAX_VALUE.toString())
        fields[1].setText(Int.MAX_VALUE.toString())
        fields[2].setText((natural.width - 10).toString())
        fields[3].setText((natural.height - 20).toString())
        assertEquals(controller.get().getString(R.string.crop_effective_preview, 10, 20, natural.width - 10, natural.height - 20), preview())
        assertEquals(Int.MAX_VALUE.toString(), fields[0].text.toString())
        assertEquals(CropSettings(0, 0, 0, 0), CropStore(controller.get()).load())
        fields[2].setText(natural.width.toString())
        assertEquals(controller.get().getString(R.string.crop_invalid_preview), preview())
        val error = root.getChildAt(root.childCount - 1) as TextView
        assertEquals(View.VISIBLE, error.visibility)
        assertTrue(error.text.toString().contains("outside"))
        fields[2].setText("0")
        assertEquals(View.GONE, error.visibility)
    }
    @Test fun defaultDisplayChangeUpdatesNaturalResolutionInTheOpenDialog() {
        RuntimeEnvironment.setQualifiers("w600dp-h1000dp-port-mdpi")
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "w600dp-h1000dp-port-mdpi")
        shadowOf(Looper.getMainLooper()).idle()
        controller.get().onConfigurationChanged(Configuration(RuntimeEnvironment.getApplication().resources.configuration))
        val natural = PhoneGeometry(controller.get()).read().naturalSize
        assertEquals(controller.get().getString(R.string.crop_natural_resolution, natural.width, natural.height), resolution())
        assertEquals(controller.get().getString(R.string.crop_effective_preview, natural.width, natural.height, 0, 0), preview())
    }
    @Test fun invalidTemporaryTextHasExplicitPreviewErrorAndDoesNotRewriteFields() {
        fields[0].setText("1.5")
        assertEquals("1.5", fields[0].text.toString())
        assertEquals(controller.get().getString(R.string.crop_invalid_preview), preview())
        fields[0].setText("")
        assertEquals("", fields[0].text.toString())
        assertEquals(controller.get().getString(R.string.crop_invalid_preview), preview())
    }
    @Test fun dismissedEditorRemovesPreviewCallbacksAndDoesNotReactToLateDraftChanges() {
        val before = preview()
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        fields[0].setText("")
        assertEquals(before, preview())
        assertNull(ReflectionHelpers.getField<Any?>(controller.get(), "refreshCropPhone"))
        assertNull(ReflectionHelpers.getField<Any?>(controller.get(), "cropDialog"))
    }
}
