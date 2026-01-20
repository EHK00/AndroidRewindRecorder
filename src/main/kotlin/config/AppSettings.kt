package config

import java.util.prefs.Preferences

object AppSettings {
    private val prefs: Preferences = Preferences.userNodeForPackage(AppSettings::class.java)

    private const val KEY_BUFFER_DURATION = "bufferDuration"
    private const val KEY_FPS = "fps"
    private const val KEY_OUTPUT_PATH = "outputPath"
    private const val KEY_SHOW_TOUCH_POINTER = "showTouchPointer"
    private const val KEY_SHOW_TIMESTAMP_OVERLAY = "showTimestampOverlay"

    // 기본값
    private const val DEFAULT_BUFFER_DURATION = 60
    private const val DEFAULT_FPS = 30
    private val DEFAULT_OUTPUT_PATH = "${System.getProperty("user.home")}/Desktop/AndroidRecordings"
    private const val DEFAULT_SHOW_TOUCH_POINTER = true
    private const val DEFAULT_SHOW_TIMESTAMP_OVERLAY = true

    var bufferDuration: Int
        get() = prefs.getInt(KEY_BUFFER_DURATION, DEFAULT_BUFFER_DURATION)
        set(value) = prefs.putInt(KEY_BUFFER_DURATION, value)

    var fps: Int
        get() = prefs.getInt(KEY_FPS, DEFAULT_FPS)
        set(value) = prefs.putInt(KEY_FPS, value)

    var outputPath: String
        get() = prefs.get(KEY_OUTPUT_PATH, DEFAULT_OUTPUT_PATH)
        set(value) = prefs.put(KEY_OUTPUT_PATH, value)

    var showTouchPointer: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOUCH_POINTER, DEFAULT_SHOW_TOUCH_POINTER)
        set(value) = prefs.putBoolean(KEY_SHOW_TOUCH_POINTER, value)

    var showTimestampOverlay: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TIMESTAMP_OVERLAY, DEFAULT_SHOW_TIMESTAMP_OVERLAY)
        set(value) = prefs.putBoolean(KEY_SHOW_TIMESTAMP_OVERLAY, value)

    fun flush() {
        prefs.flush()
    }
}
