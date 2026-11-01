package dev.screencopy.partialcast

import android.content.SharedPreferences
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Simulates Android committing memory before an unconfirmed/failed disk result. */
internal class FailingCropPreferences(private val delegate: SharedPreferences, var failures: Int) : SharedPreferences by delegate {
    val written = ArrayList<CropSettings>()
    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                editor.putInt(key, value)
                return this
            }
            override fun commit(): Boolean {
                val result = editor.commit()
                written.add(CropSettings(delegate.getInt("width", 0), delegate.getInt("height", 0),
                    delegate.getInt("x", 0), delegate.getInt("y", 0)))
                if (failures > 0) { failures--; return false }
                return result
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CropStoreTest {
    private val phone = PhoneSnapshot(VideoSize(1080, 2400), 0, 320)
    @Test fun allZeroOnlyMeansWholePhone() {
        assertNull(CropSettings.parse(listOf("0", "0", "0", "0")).options().crop)
        for (values in listOf(listOf("0", "1", "0", "0"), listOf("1", "0", "0", "0"),
            listOf("0", "0", "1", "0"), listOf("-1", "1", "0", "0"),
            listOf("1.5", "1", "0", "0"), listOf("2147483648", "1", "0", "0"),
            listOf("", "1", "0", "0"))) {
            assertThrows(IllegalArgumentException::class.java) { CropSettings.parse(values) }
        }
    }
    @Test fun saveClipsForPreviewButDoesNotRewriteSettingsAndInvalidSavePreservesOldValues() {
        val store = CropStore(RuntimeEnvironment.getApplication())
        val saved = CropSettings(Int.MAX_VALUE, 200, 1000, 2300)
        val geometry = store.save(saved, phone)
        assertEquals(LocalRect(1000, 2300, 80, 100), geometry.crop)
        assertEquals(saved, store.load())
        assertThrows(IllegalArgumentException::class.java) { store.save(CropSettings(1, 1, 1080, 0), phone) }
        assertEquals(saved, store.load())
    }
    @Test fun onePixelAndNaturalLandscapeAreValid() {
        val crop = CropSettings(1, 1, 1079, 2399)
        assertEquals(LocalRect(2399, 0, 1, 1),
            crop.validate(PhoneSnapshot(phone.size.rotated(), 1, 320)).crop)
    }
    @Test fun failedCommitRestoresOldCropForEveryStoreInstanceBeforeReportingFailure() {
        val context = RuntimeEnvironment.getApplication()
        val previous = CropSettings(100, 200, 10, 20)
        CropStore(context).save(previous, phone)
        val preferences = FailingCropPreferences(context.getSharedPreferences("crop", 0), 1)
        val store = CropStore(preferences)
        val draft = CropSettings(200, 400, 20, 30)
        val error = assertThrows(IllegalStateException::class.java) { store.save(draft, phone) }
        assertTrue(error.message.orEmpty().contains("Previous values"))
        assertEquals(listOf(draft, previous), preferences.written)
        assertEquals(previous, store.load())
        assertEquals(previous, CropStore(context).load())
        store.save(draft, phone)
        assertEquals(draft, CropStore(context).load())
    }
    @Test fun failedDiskRollbackStillRestoresPreviousEffectiveMemoryAndReportsSaveFailure() {
        val context = RuntimeEnvironment.getApplication()
        val previous = CropSettings(100, 200, 10, 20)
        CropStore(context).save(previous, phone)
        val preferences = FailingCropPreferences(context.getSharedPreferences("crop", 0), 2)
        assertThrows(IllegalStateException::class.java) {
            CropStore(preferences).save(CropSettings(200, 400, 20, 30), phone)
        }
        assertEquals(previous, CropStore(context).load())
        assertEquals(previous, preferences.written.last())
    }
    @Test fun observationSeesAtomicCropAndClearIgnoresOtherKeysAndCanBeRemoved() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("crop", 0)
        val store = CropStore(preferences)
        val changes = ArrayList<CropSettings>()
        val stop = store.observe { changes.add(store.load()) }
        val crop = CropSettings(100, 200, 10, 20)
        store.save(crop, phone)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.all { it == crop })
        preferences.edit().clear().commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CropSettings(0, 0, 0, 0), changes.last())
        val count = changes.size
        preferences.edit().putInt("unrelated", 1).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count, changes.size)
        stop()
        store.save(crop, phone)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count, changes.size)
    }
}
