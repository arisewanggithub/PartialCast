package dev.screencopy.partialcast

internal class CaptureResizeState(private val requiresContentSize: Boolean) {
    private var reported: VideoSize? = null
    private var mismatchStarted: Long? = null
    var ready = !requiresContentSize
        private set
    val waiting get() = mismatchStarted != null

    fun report(size: VideoSize) {
        require(size.width > 0 && size.height > 0) { "Android reported invalid captured-content dimensions" }
        reported = size
        ready = false
    }

    fun reconcile(phone: VideoSize, now: Long): Boolean {
        if (!requiresContentSize) return true
        val size = reported ?: return false
        if (size == phone) {
            mismatchStarted = null
            ready = true
            return true
        }
        ready = false
        val started = mismatchStarted ?: now.also { mismatchStarted = it }
        check(now - started < SETTLE_TIMEOUT_MS) {
            "Captured content did not settle to the full default phone display. Phone-coordinate sharing is unsupported; Stop and press Play again."
        }
        return false
    }

    companion object {
        const val SETTLE_TIMEOUT_MS = 1500L
        const val RETRY_INTERVAL_MS = 100L
    }
}

internal class ProjectionFrameState {
    private var pending = false
    private var imageTimestamp: Long? = null
    private var queued = false
    private var acquired = false
    fun frameAvailable() { pending = true }
    fun queueDraw(): Boolean {
        if (queued) return false
        queued = true
        return true
    }
    fun beginDraw(): Boolean {
        queued = false
        return takePendingFrame()
    }
    fun takePendingFrame(): Boolean {
        val update = pending
        pending = false
        return update
    }
    fun imageUpdated(timestamp: Long) {
        if (imageTimestamp != timestamp) acquired = true
        imageTimestamp = timestamp
    }
    fun imageDiscarded(timestamp: Long) {
        imageTimestamp = timestamp
        acquired = false
    }
    fun invalidateImage() {
        acquired = false
    }
    fun captureGeometryChanged(previous: LocalGeometry?, next: LocalGeometry): Boolean {
        val changed = previous?.source != next.source || previous?.buffer != next.buffer
        if (changed) invalidateImage()
        return changed
    }
    fun canPresent(contentReady: Boolean) = acquired && contentReady
    fun changedAfterSwap(drawn: VideoSize, width: Int, height: Int) = drawn.width != width || drawn.height != height
}
