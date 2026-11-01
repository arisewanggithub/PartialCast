package dev.screencopy.partialcast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Geometry only; never captures or reads screen pixels. */
internal class CropSketchView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val phoneLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(89, 89, 89)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val cropLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 107, 97)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val cropFill = Paint().apply { color = Color.argb(45, 0, 107, 97) }
    private val phoneBounds = RectF()
    private val cropBounds = RectF()
    private var phone: VideoSize? = null
    private var crop: LocalRect? = null

    fun show(natural: VideoSize?, selected: LocalRect?) {
        phone = natural
        crop = selected
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val natural = phone ?: return
        val inset = 8f * density
        val availableWidth = (width - inset * 2).coerceAtLeast(0f)
        val availableHeight = (height - inset * 2).coerceAtLeast(0f)
        val scale = minOf(availableWidth / natural.width, availableHeight / natural.height)
        val left = (width - natural.width * scale) / 2
        val top = (height - natural.height * scale) / 2
        phoneBounds.set(left, top, left + natural.width * scale, top + natural.height * scale)
        canvas.drawRect(phoneBounds, phoneLine)
        crop?.let {
            cropBounds.set(left + it.x * scale, top + it.y * scale,
                left + (it.x + it.width) * scale, top + (it.y + it.height) * scale)
            canvas.drawRect(cropBounds, cropFill)
            canvas.drawRect(cropBounds, cropLine)
        }
    }
}
