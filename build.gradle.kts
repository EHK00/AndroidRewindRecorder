import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.21"
    id("org.jetbrains.compose") version "1.5.11"
}

group = "com.debugtool"
version = "1.0.0"

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
            targetFormats(TargetFormat.Dmg)
            packageName = "AndroidRewindRecorder"
            packageVersion = "1.0.1"

            macOS {
                bundleID = "com.debugtool.androidrewindrecorder"
            }
        }
    }
}
