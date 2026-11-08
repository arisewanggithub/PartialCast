package dev.screencopy.partialcast

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppManifestTest {
    @Suppress("DEPRECATION")
    @Test fun sdk37RemainsLocalOnlyWithOneLauncherAndProjectionService() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("dev.screencopy.partialcast.debug", context.packageName)
        assertEquals("PartialCast Debug", context.getString(R.string.app_name))
        assertEquals(37, context.applicationInfo.targetSdkVersion)
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
        assertEquals(setOf("android.permission.INTERNET", "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION", "android.permission.POST_NOTIFICATIONS"),
            checkNotNull(info.requestedPermissions).toSet())
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val activity = checkNotNull(info.activities).single()
        assertEquals("dev.screencopy.partialcast.HomeActivity", activity.name)
        assertTrue(activity.exported)
        val service = checkNotNull(info.services).single()
        assertEquals("dev.screencopy.partialcast.LocalProjectionService", service.name)
        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, service.foregroundServiceType)
    }
}
