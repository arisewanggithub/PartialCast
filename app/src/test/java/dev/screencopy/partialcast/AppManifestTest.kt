package dev.screencopy.partialcast

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.VectorDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppManifestTest {
    @Test fun launcherPadsForegroundWithoutScalingTheNotificationDrawable() {
        val context = RuntimeEnvironment.getApplication()
        val icon = checkNotNull(context.getDrawable(context.applicationInfo.icon) as? LayerDrawable)
        icon.setBounds(0, 0, 1000, 1000)
        assertEquals(Rect(0, 0, 1000, 1000), icon.getDrawable(0).bounds)
        val foreground = checkNotNull(icon.getDrawable(1) as? InsetDrawable)
        assertEquals(Rect(70, 70, 930, 930), checkNotNull(foreground.drawable).bounds)
        val notification = checkNotNull(context.getDrawable(R.drawable.ic_partialcast_foreground) as? VectorDrawable)
        notification.setBounds(0, 0, 1000, 1000)
        assertEquals(Rect(0, 0, 1000, 1000), notification.bounds)
    }

    @Test fun launcherUsesRedBackgroundAndWhiteTransparentNotificationForeground() {
        val context = RuntimeEnvironment.getApplication()
        val icon = checkNotNull(context.getDrawable(context.applicationInfo.icon) as? LayerDrawable)
        assertEquals(2, icon.numberOfLayers)
        assertEquals(Color.rgb(159, 32, 54), checkNotNull(icon.getDrawable(0) as? ColorDrawable).color)
        val foregroundColors = mutableListOf<Int>()
        context.resources.getXml(R.drawable.ic_partialcast_foreground).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "path") {
                    foregroundColors += Color.parseColor(checkNotNull(
                        xml.getAttributeValue("http://schemas.android.com/apk/res/android", "fillColor")))
                }
            }
        }
        assertEquals(listOf(Color.WHITE, Color.WHITE), foregroundColors)
    }

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
