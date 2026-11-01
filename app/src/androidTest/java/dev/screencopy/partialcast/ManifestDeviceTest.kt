package dev.screencopy.partialcast

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compile here; execute only on an explicitly authorized disposable emulator. */
@RunWith(AndroidJUnit4::class)
class ManifestDeviceTest {
    @Test fun onlyUpdateMetadataNeedsInternetWithoutInstallerOverlayOrCapturedAudioPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.screencopy.partialcast", context.packageName)
        assertEquals(37, context.applicationInfo.targetSdkVersion)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions?.toSet() ?: emptySet()
        assertTrue("android.permission.INTERNET" in permissions)
        for (forbidden in listOf("android.permission.ACCESS_LOCAL_NETWORK", "android.permission.RECORD_AUDIO",
            "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.SYSTEM_ALERT_WINDOW"))
            assertFalse(forbidden, forbidden in permissions)
    }
}
