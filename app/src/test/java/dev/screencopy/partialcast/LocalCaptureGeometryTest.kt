package dev.screencopy.partialcast

import org.junit.Assert.*
import org.junit.Test

class LocalCaptureGeometryTest {
    private val phone = VideoSize(1080, 2400)
    @Test fun wholeScreenIsFullResolutionWithoutAlignmentOrStretching() {
        val geometry = LocalCaptureGeometry.calculate(phone, 0, LocalCaptureOptions(null))
        assertEquals(phone, geometry.buffer)
        assertEquals(LocalRect(0, 0, 1080, 2400), geometry.crop)
        assertEquals(LocalRect(717, 0, 486, 1080), geometry.viewport(VideoSize(1920, 1080)))
    }
    @Test fun landscapeLetterboxAndOutputResize() {
        val geometry = LocalCaptureGeometry.calculate(phone.rotated(), 1, LocalCaptureOptions(null))
        assertEquals(phone.rotated(), geometry.buffer)
        assertEquals(LocalRect(0, 108, 1920, 864), geometry.viewport(VideoSize(1920, 1080)))
        assertEquals(LocalRect(0, 717, 1080, 486), geometry.viewport(VideoSize(1080, 1920)))
    }
    @Test fun naturalCropRotatesAtAllFourOrientations() {
        val options = LocalCaptureOptions(LocalCrop(400, 800, 100, 200))
        val expected = listOf(LocalRect(100, 200, 400, 800), LocalRect(200, 580, 800, 400),
            LocalRect(580, 1400, 400, 800), LocalRect(1400, 100, 800, 400))
        for (rotation in 0..3) {
            val source = if (rotation % 2 == 0) phone else phone.rotated()
            val geometry = LocalCaptureGeometry.calculate(source, rotation, options)
            assertEquals(expected[rotation], geometry.crop)
            assertEquals(source, geometry.buffer)
            val coordinates = FloatArray(8)
            geometry.textureCoordinates(coordinates)
            assertTrue(coordinates.all { it in 0f..1f })
            assertTrue(coordinates[2] > coordinates[0] && coordinates[5] > coordinates[1])
        }
    }
    @Test fun clipsBeforeRotatingWithoutOffsetOverflow() {
        for (rotation in 0..3) {
            val source = if (rotation % 2 == 0) phone else phone.rotated()
            val geometry = LocalCaptureGeometry.calculate(source, rotation,
                LocalCaptureOptions(LocalCrop(Int.MAX_VALUE, Int.MAX_VALUE, 1000, 2300)))
            assertEquals(if (rotation % 2 == 0) 80 else 100, geometry.crop.width)
            assertEquals(if (rotation % 2 == 0) 100 else 80, geometry.crop.height)
            assertEquals(source, geometry.buffer)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalCaptureGeometry.calculate(phone, 0, LocalCaptureOptions(LocalCrop(1, 1, Int.MAX_VALUE, 0)))
        }
    }
    @Test fun invalidAndOutsideBoundsAreRejectedButOnePixelIsPreserved() {
        assertThrows(IllegalArgumentException::class.java) { LocalCrop(0, 1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { LocalCrop(1, 1, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            LocalCaptureGeometry.calculate(VideoSize(0, 100), 0, LocalCaptureOptions(null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalCaptureGeometry.calculate(phone, 4, LocalCaptureOptions(null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalCaptureGeometry.calculate(phone, 0, LocalCaptureOptions(LocalCrop(1, 1, 1080, 0)))
        }
        val pixel = LocalCaptureGeometry.calculate(phone, 0, LocalCaptureOptions(LocalCrop(1, 1, 1079, 2399)))
        assertEquals(LocalRect(1079, 2399, 1, 1), pixel.crop)
        assertEquals(phone, pixel.buffer)
    }
    @Test fun coordinatesComposeWithSurfaceTextureTransform() {
        val geometry = LocalCaptureGeometry.calculate(VideoSize(1000, 2000), 0,
            LocalCaptureOptions(LocalCrop(300, 400, 100, 200)))
        val uv = FloatArray(8)
        geometry.textureCoordinates(uv)
        assertArrayEquals(floatArrayOf(.1f, .7f, .4f, .7f, .1f, .9f, .4f, .9f), uv, .00001f)
        // SurfaceTexture's vertical flip is applied after the crop coordinates in the vertex shader.
        val sampled = FloatArray(8) { index -> if (index % 2 == 0) uv[index] else 1f - uv[index] }
        assertArrayEquals(floatArrayOf(.1f, .3f, .4f, .3f, .1f, .1f, .4f, .1f), sampled, .00001f)
    }
}
