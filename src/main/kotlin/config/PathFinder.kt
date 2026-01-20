package config

import java.io.File

/**
 * ADB, FFmpeg 등 외부 도구 경로 찾기
 */
object PathFinder {

    val adbPath: String by lazy { findExecutable("adb", ADB_PATHS) }
    val ffmpegPath: String by lazy { findExecutable("ffmpeg", FFMPEG_PATHS) }

    private val ADB_PATHS = listOf(
        "/opt/homebrew/bin/adb",           // Homebrew (Apple Silicon)
        "/usr/local/bin/adb",              // Homebrew (Intel)
        "${System.getProperty("user.home")}/Library/Android/sdk/platform-tools/adb"  // Android Studio
    )

    private val FFMPEG_PATHS = listOf(
        "/opt/homebrew/bin/ffmpeg",        // Homebrew (Apple Silicon)
        "/usr/local/bin/ffmpeg",           // Homebrew (Intel)
        "/usr/bin/ffmpeg"                  // Linux 기본
    )

    private fun findExecutable(name: String, commonPaths: List<String>): String {
        // 먼저 PATH에서 찾기
        try {
            val process = ProcessBuilder("which", name).start()
            val path = process.inputStream.bufferedReader().readText().trim()
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
