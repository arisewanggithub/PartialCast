package dev.screencopy.partialcast

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

internal data class CropSettings(val width: Int, val height: Int, val x: Int, val y: Int) {
    fun options(): LocalCaptureOptions = LocalCaptureOptions(
        if (width == 0 && height == 0 && x == 0 && y == 0) null else LocalCrop(width, height, x, y))

    fun validate(phone: PhoneSnapshot): LocalGeometry =
        LocalCaptureGeometry.calculate(phone.size, phone.rotation, options())

    companion object {
        fun parse(values: List<String>): CropSettings {
            require(values.size == 4) { "Enter all four crop values" }
            val names = listOf("Width", "Height", "X", "Y")
            val numbers = values.mapIndexed { index, text ->
                text.trim().toIntOrNull() ?: throw IllegalArgumentException(
                    "${names[index]} must be a whole number from 0 to ${Int.MAX_VALUE}")
            }
            return CropSettings(numbers[0], numbers[1], numbers[2], numbers[3]).also { it.options() }
        }
    }
}

internal class CropStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("crop", Context.MODE_PRIVATE))
    fun load() = CropSettings(preferences.getInt("width", 0), preferences.getInt("height", 0),
        preferences.getInt("x", 0), preferences.getInt("y", 0))

    fun observe(listener: () -> Unit): () -> Unit {
        val observer = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                null, "width", "height", "x", "y" -> listener()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(observer)
        return { preferences.unregisterOnSharedPreferenceChangeListener(observer) }
    }

    fun save(value: CropSettings, phone: PhoneSnapshot): LocalGeometry {
        val geometry = value.validate(phone)
        val previous = load()
        if (!write(value)) {
            // commit() changes memory even when its disk write fails.
            val restored = write(previous)
            check(load() == previous) { "Could not restore crop settings; restart the app before Play" }
            if (!restored) Log.e("PartialCast", "Crop save and disk rollback failed; previous in-memory crop restored")
            error("Could not save crop settings. Previous values remain active.")
        }
        return geometry
    }
    private fun write(value: CropSettings) = preferences.edit().putInt("width", value.width).putInt("height", value.height)
        .putInt("x", value.x).putInt("y", value.y).commit()
}
