package dev.screencopy.partialcast

import android.content.Context
import android.app.KeyguardManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

internal data class PhoneSnapshot(val size: VideoSize, val rotation: Int, val density: Int) {
    val naturalSize get() = if (rotation % 2 == 0) size else size.rotated()
}

/** No Activity resources/window metrics: each read uses a fresh default-display context. */
internal class PhoneGeometry(context: Context) {
    private val application = context.applicationContext

    @Suppress("DEPRECATION")
    fun read(): PhoneSnapshot {
        check(!application.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            "Phone is locked; capture stops. Unlock and press Play for new consent."
        }
        repeat(3) {
            val phone = checkNotNull(application.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)) { "Default phone display is unavailable" }
            check(phone.isValid) { "Default phone display is invalid" }
            val displayContext = application.createDisplayContext(phone)
            val geometryContext = if (Build.VERSION.SDK_INT >= 31)
                displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
                else displayContext
            val liveDisplay = checkNotNull(geometryContext.display) { "Phone geometry context has no display" }
            val rotation = liveDisplay.rotation
            val metrics = DisplayMetrics()
            liveDisplay.getRealMetrics(metrics)
            val size = if (Build.VERSION.SDK_INT >= 31) {
                val bounds = geometryContext.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                VideoSize(bounds.width(), bounds.height())
            } else VideoSize(metrics.widthPixels, metrics.heightPixels)
            if (rotation == liveDisplay.rotation) {
                check(size.width > 0 && size.height > 0 && metrics.densityDpi > 0) {
                    "Android reported invalid default-display geometry"
                }
                return PhoneSnapshot(size, rotation, metrics.densityDpi)
            }
        }
        error("Phone display changed during geometry query; press Play again.")
    }
}
