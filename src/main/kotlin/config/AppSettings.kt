package config

import java.io.File
import java.util.Properties

object AppSettings {
    private val configDir: File = run {
        val location = AppSettings::class.java.protectionDomain?.codeSource?.location
        if (location != null) {
            try {
                val file = File(location.toURI())
                if (file.isFile) file.parentFile else file
            } catch (e: Exception) {
                File(System.getProperty("user.dir"))
            }
        } else {
            File(System.getProperty("user.dir"))
        }
    }
    private val configFile = File(configDir, "settings.properties")

    private val props = Properties().also { p ->
        if (configFile.exists()) {
            configFile.inputStream().use { p.load(it) }
        }
    }

    private val DEFAULT_OUTPUT_PATH =
        File(System.getProperty("user.home"), "Desktop/AndroidRecordings").absolutePath

    var bufferDuration: Int
        get() = props.getProperty("bufferDuration")?.toIntOrNull() ?: 60
        set(value) { props.setProperty("bufferDuration", value.toString()) }

    var fps: Int
        get() = props.getProperty("fps")?.toIntOrNull() ?: 30
        set(value) { props.setProperty("fps", value.toString()) }

    var outputPath: String
        get() = props.getProperty("outputPath") ?: DEFAULT_OUTPUT_PATH
        set(value) { props.setProperty("outputPath", value) }

    var showTouchPointer: Boolean
        get() = props.getProperty("showTouchPointer")?.toBooleanStrictOrNull() ?: true
        set(value) { props.setProperty("showTouchPointer", value.toString()) }

    var showTimestampOverlay: Boolean
        get() = props.getProperty("showTimestampOverlay")?.toBooleanStrictOrNull() ?: true
        set(value) { props.setProperty("showTimestampOverlay", value.toString()) }

    fun flush() {
        configFile.parentFile?.mkdirs()
        configFile.outputStream().use {
            props.store(it, "AndroidRewindRecorder Settings")
        }
    }
}
