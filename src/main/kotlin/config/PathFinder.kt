package config

import java.io.File

/**
 * ADB, FFmpeg 등 외부 도구 경로 찾기
 */
object PathFinder {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    val adbPath: String by lazy { findExecutable("adb", ADB_PATHS) }
    val ffmpegPath: String by lazy { findExecutable("ffmpeg", FFMPEG_PATHS) }
    val ffprobePath: String by lazy { findExecutable("ffprobe", FFPROBE_PATHS) }

    private val userHome = System.getProperty("user.home")
    private val localAppData = System.getenv("LOCALAPPDATA") ?: ""

    private val ADB_PATHS = if (isWindows) listOf(
        "$localAppData\\Android\\Sdk\\platform-tools\\adb.exe",
        "$userHome\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe",
        "C:\\Program Files\\Android\\platform-tools\\adb.exe",
        "C:\\Program Files (x86)\\Android\\platform-tools\\adb.exe"
    ) else listOf(
        "/opt/homebrew/bin/adb",
        "/usr/local/bin/adb",
        "$userHome/Library/Android/sdk/platform-tools/adb",
        "$userHome/Android/Sdk/platform-tools/adb"
    )

    private val FFMPEG_PATHS = if (isWindows) listOf(
        "C:\\ffmpeg\\bin\\ffmpeg.exe",
        "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
        "$userHome\\scoop\\shims\\ffmpeg.exe"
    ) else listOf(
        "/opt/homebrew/bin/ffmpeg",
        "/usr/local/bin/ffmpeg",
        "/usr/bin/ffmpeg"
    )

    private val FFPROBE_PATHS = if (isWindows) listOf(
        "C:\\ffmpeg\\bin\\ffprobe.exe",
        "C:\\Program Files\\ffmpeg\\bin\\ffprobe.exe",
        "$userHome\\scoop\\shims\\ffprobe.exe"
    ) else listOf(
        "/opt/homebrew/bin/ffprobe",
        "/usr/local/bin/ffprobe",
        "/usr/bin/ffprobe"
    )

    private fun findExecutable(name: String, commonPaths: List<String>): String {
        // PATH에서 찾기 (Windows: where, Unix: which)
        try {
            val cmd = if (isWindows) "where" else "which"
            val process = ProcessBuilder(cmd, name).start()
            val path = process.inputStream.bufferedReader().readText().trim().lines().firstOrNull()?.trim() ?: ""
            process.waitFor()
            if (path.isNotEmpty() && File(path).exists()) return path
        } catch (_: Exception) {}

        // 일반적인 설치 경로 확인
        for (path in commonPaths) {
            if (File(path).exists()) return path
        }

        // 기본값 (PATH에서 찾기 시도)
        return name
    }
}
