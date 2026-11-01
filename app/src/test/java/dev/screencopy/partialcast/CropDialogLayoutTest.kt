package dev.screencopy.partialcast

import android.app.AlertDialog
import android.hardware.display.DisplayManager
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class CropDialogLayoutTest {
    private lateinit var controller: ActivityController<HomeActivity>
    private lateinit var dialog: AlertDialog
    private lateinit var fields: List<EditText>
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun dp(value: Int) = (value * controller.get().resources.displayMetrics.density).toInt()

    @Before fun openEditor() {
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
}
