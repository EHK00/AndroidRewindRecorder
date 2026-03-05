import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.21"
    id("org.jetbrains.compose") version "1.5.11"
}

group = "com.debugtool"
version = "1.0.3"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    // MP4 muxing with sample-level duration control
    implementation("org.mp4parser:isoparser:1.9.56")
    implementation("org.mp4parser:muxer:1.9.56")
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AndroidRewindRecorder"
            packageVersion = project.version.toString()

            macOS {
                bundleID = "com.debugtool.androidrewindrecorder"
            }

            windows {
                menuGroup = "AndroidRewindRecorder"
                upgradeUuid = "d3b07384-d9a0-4e6b-8b0d-5c6a7e8f9a0b"
            }
        }
    }
}
