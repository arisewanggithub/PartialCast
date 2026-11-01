package dev.screencopy.partialcast

import kotlin.math.roundToInt

internal data class LocalCrop(val width: Int, val height: Int, val x: Int, val y: Int) {
    init {
        require(width > 0 && height > 0 && x >= 0 && y >= 0) {
            "Crop must have positive width/height and nonnegative offsets"
        }
    }
}

internal data class LocalCaptureOptions(val crop: LocalCrop?) {
    fun summary() = crop?.let { "Crop ${it.width} x ${it.height} at (${it.x}, ${it.y}); natural phone coordinates" }
        ?: "Whole phone screen"
}

internal data class VideoSize(val width: Int, val height: Int) {
    fun rotated() = VideoSize(height, width)
    override fun toString() = "$width x $height"
}

internal data class LocalRect(val x: Int, val y: Int, val width: Int, val height: Int)
internal data class LocalGeometry(val source: VideoSize, val crop: LocalRect, val buffer: VideoSize) {
    fun textureCoordinates(out: FloatArray) {
        require(out.size == 8)
        val left = crop.x.toFloat() / source.width
        val right = (crop.x + crop.width).toFloat() / source.width
        val bottom = 1f - (crop.y + crop.height).toFloat() / source.height
        val top = 1f - crop.y.toFloat() / source.height
        out[0] = left; out[1] = bottom
        out[2] = right; out[3] = bottom
        out[4] = left; out[5] = top
        out[6] = right; out[7] = top
    }
    fun viewport(output: VideoSize): LocalRect {
        require(output.width > 0 && output.height > 0)
        val scale = minOf(output.width.toDouble() / crop.width, output.height.toDouble() / crop.height)
        val width = (crop.width * scale).roundToInt().coerceIn(1, output.width)
        val height = (crop.height * scale).roundToInt().coerceIn(1, output.height)
        return LocalRect((output.width - width) / 2, (output.height - height) / 2, width, height)
    }
}

internal object LocalCaptureGeometry {
    fun calculate(source: VideoSize, rotation: Int, options: LocalCaptureOptions): LocalGeometry {
        require(source.width > 0 && source.height > 0) { "Captured content has invalid dimensions" }
        require(rotation in 0..3) { "Invalid phone display rotation" }
        val natural = if (rotation % 2 == 0) source else source.rotated()
        val rect = options.crop?.let {
            require(it.x < natural.width && it.y < natural.height) { "Crop is outside the phone screen" }
            val w = minOf(it.width.toLong(), natural.width.toLong() - it.x).toInt()
            val h = minOf(it.height.toLong(), natural.height.toLong() - it.y).toInt()
            // Clip in natural coordinates before rotating the rectangle.
            when (rotation) {
                0 -> LocalRect(it.x, it.y, w, h)
                1 -> LocalRect(it.y, natural.width - it.x - w, h, w)
                2 -> LocalRect(natural.width - it.x - w, natural.height - it.y - h, w, h)
                else -> LocalRect(natural.height - it.y - h, it.x, h, w)
            }
        } ?: LocalRect(0, 0, source.width, source.height)
        return LocalGeometry(source, rect, source)
    }
}
