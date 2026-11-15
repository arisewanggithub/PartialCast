package dev.screencopy.partialcast

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

internal class LocalDisplayDiscovery(context: Context, private val changed: (List<LocalDisplayTarget>) -> Unit) {
    private val manager = context.getSystemService(DisplayManager::class.java)
    private var attached = false
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }
    fun start() {
        if (attached) return
        attached = true
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        refresh()
    }
    fun stop() {
        attached = false
        manager.unregisterDisplayListener(listener)
    }
    fun refresh() {
        if (attached) changed(manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { eligible(it, LocalProjectionService.captureDisplayId) }
            .map(::target))
    }
    companion object {
        @Suppress("DEPRECATION")
        fun target(display: Display): LocalDisplayTarget {
            val size = Point()
            display.getRealSize(size)
            return LocalDisplayTarget(display.displayId, display.name, display.flags,
                VideoSize(size.x, size.y), display.rotation)
        }
        fun eligible(display: Display, excluded: Int) = display.isValid &&
            display.displayId != Display.DEFAULT_DISPLAY && display.displayId != excluded &&
            display.flags and Display.FLAG_PRESENTATION != 0 &&
            display.flags and Display.FLAG_PRIVATE == 0
    }
}
