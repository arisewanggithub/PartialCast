package dev.screencopy.partialcast

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CropSketchViewTest {
    private class RecordingCanvas : Canvas() {
        val rectangles = ArrayList<RectF>()
        val colors = ArrayList<Int>()
        override fun drawRect(rect: RectF, paint: Paint) {
            rectangles.add(RectF(rect))
            colors.add(paint.color)
        }
    }
    @Test fun schematicFitsPhoneAspectAndMapsNaturalCropWithContrastingLines() {
        val view = CropSketchView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 400, 300)
        view.show(VideoSize(1000, 2000), LocalRect(100, 200, 300, 400))
        val canvas = RecordingCanvas()
        view.draw(canvas)
        assertEquals(3, canvas.rectangles.size)
        val phone = canvas.rectangles[0]
        val fill = canvas.rectangles[1]
        val crop = canvas.rectangles[2]
        assertEquals(.5f, phone.width() / phone.height(), .00001f)
        assertTrue(phone.left >= 0 && phone.top >= 0 && phone.right <= 400 && phone.bottom <= 300)
        assertEquals(phone.left + phone.width() * .1f, crop.left, .001f)
        assertEquals(phone.top + phone.height() * .1f, crop.top, .001f)
        assertEquals(phone.width() * .3f, crop.width(), .001f)
        assertEquals(phone.height() * .2f, crop.height(), .001f)
        assertEquals(fill, crop)
        assertNotEquals(canvas.colors[0], canvas.colors[2])
    }
    @Test fun invalidCropShowsOnlyPhoneAndUnavailableGeometryShowsNothing() {
        val view = CropSketchView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 300, 200)
        view.show(VideoSize(1000, 2000), null)
        val invalid = RecordingCanvas()
        view.draw(invalid)
        assertEquals(1, invalid.rectangles.size)
        view.show(null, null)
        val unavailable = RecordingCanvas()
        view.draw(unavailable)
        assertEquals(0, unavailable.rectangles.size)
    }
    @Test fun naturalDimensionsStayTheSameAcrossAllFourRotations() {
        val natural = VideoSize(1080, 2376)
        for (rotation in 0..3) {
            val logical = if (rotation % 2 == 0) natural else natural.rotated()
            assertEquals(natural, PhoneSnapshot(logical, rotation, 320).naturalSize)
        }
    }
}
