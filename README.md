# AndroidRewindRecorder

> Save the screen you **just saw** with a single shortcut when a bug occurs.

A rewind screen recorder for Android debugging.
Continuously captures Android screen via ADB and saves the last N seconds/minutes as video on demand.

## Features

- **Ring Buffer**: Store recent screens in memory (up to 3 minutes)
- **Instant Save**: Save with button or shortcut
- **High FPS Mode**: 30-60fps via screenrecord dual stream
- **Timestamp Overlay**: Display time on saved video

## Requirements

- **macOS** (Apple Silicon / Intel)
- **Java 17+**
- **ADB** & **FFmpeg**
  ```bash
  brew install android-platform-tools ffmpeg
  ```
- **Android 4.4+** device with USB debugging enabled

## Quick Start

```bash
# Build & Run
./gradlew run

# Or create app
./gradlew createDistributable
```

## Usage

1. Connect Android device with USB debugging enabled
2. Launch app → Click **Start** when device detected
3. Press **Save** button or shortcut when bug occurs

### Shortcuts

| Shortcut | Action |
|----------|--------|
| `⌘+S` | Save last 30 seconds |
| `⌘+1` | Save last 1 minute |
| `⌘+2` | Save last 2 minutes |

### Settings

- **Buffer Duration**: Memory storage time (10s ~ 3min)
- **FPS**: Capture frame rate (1 ~ 60fps)
- **Capture Mode**: SCREENCAP (compatible) / SCREENRECORD (high performance)

## Output

Save location: `~/Desktop/AndroidRecordings/`

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
