# AndroidRewindRecorder

> Save the screen you **just saw** with a single shortcut when a bug occurs.

A rewind screen recorder for Android debugging.
Continuously captures Android screen via ADB and saves the last N seconds/minutes as video on demand.

## Features

- **Ring Buffer**: Store recent screens in memory (up to 10 minutes)
- **Instant Save**: Save video or screenshot with shortcuts
- **High FPS Mode**: 30-60fps via screenrecord dual stream
- **Timestamp Overlay**: Display capture time on saved video
- **Touch Pointer**: Show touch location on screen during recording

## Requirements

- **macOS** (Apple Silicon / Intel)
- **Java 17+**
- **ADB** & **FFmpeg**
  ```bash
  brew install android-platform-tools ffmpeg
  ```
- **Android 4.4+** device with USB debugging enabled

## Installation

### Option 1: Download DMG (Recommended)
1. Download `AndroidRewindRecorder-1.1.0.dmg` from Releases
2. Open DMG and drag app to Applications
3. Run the following command to allow unsigned app:
   ```bash
   xattr -cr /Applications/AndroidRewindRecorder.app
   ```
4. Launch the app

### Option 2: Run JAR (Cross-platform)
1. Download `AndroidRewindRecorder-<os>-<arch>.jar` from Releases
2. Run with Java 17+:
   ```bash
   java -jar AndroidRewindRecorder-macos-arm64-1.0.1.jar
   ```

### Option 3: Build from Source
```bash
# Run directly
./gradlew run

# Create DMG installer (macOS only, requires JDK with jpackage)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew packageDmg
# Output: build/compose/binaries/main/dmg/AndroidRewindRecorder-1.1.0.dmg

# Create JAR (cross-platform)
./gradlew packageUberJarForCurrentOS
# Output: build/compose/jars/AndroidRewindRecorder-<os>-<arch>-1.0.1.jar
```

## Usage

1. Connect Android device with USB debugging enabled
2. Launch app → Click **Start** when device detected
3. Press shortcut when bug occurs

### Shortcuts

| Shortcut | Action |
|----------|--------|
| `⌘R` | Start/Stop recording |
| `⌘P` | Take screenshot |
| `⌘S` | Save last 30 seconds |
| `⌘⇧S` | Save custom duration |

### Settings

- **Buffer Duration**: Memory storage time (10s ~ 10min)
- **FPS**: Capture frame rate (1 ~ 60fps)
- **Output Directory**: Video/screenshot save location
- **Touch Pointer**: Show touch location on screen (default: ON)

## Output

Default save location: `~/Desktop/AndroidRecordings/`

- Videos: `recording_YYYY-MM-DD_HH-mm-ss.mp4`
- Screenshots: `screenshot_YYYY-MM-DD_HH-mm-ss.png`

## Troubleshooting

### "No device connected"
```bash
adb kill-server && adb start-server
adb devices
```

### Video not saved
```bash
ffmpeg -version  # Check FFmpeg installation
ls ~/Desktop/AndroidRecordings/  # Check folder
```

## Tech Stack

- Kotlin 1.9.21
- Compose Multiplatform 1.5.11
- ADB + FFmpeg

## License

MIT License
