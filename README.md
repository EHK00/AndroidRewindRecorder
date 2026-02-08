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

- **macOS** (Apple Silicon / Intel) or **Windows** 10/11
- **Java 17+**
- **ADB** (Android Debug Bridge)
- **FFmpeg**
- **Android 4.4+** device with USB debugging enabled

## Prerequisites: ADB & FFmpeg Installation

### macOS

**Using Homebrew (Recommended):**
```bash
brew install android-platform-tools ffmpeg
```

**Manual Installation:**

- **ADB**: Download [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) for macOS and add to PATH
- **FFmpeg**: Download from [ffmpeg.org](https://ffmpeg.org/download.html#build-mac) or install via [MacPorts](https://www.macports.org/): `sudo port install ffmpeg`

**Verify installation:**
```bash
adb version
ffmpeg -version
```

### Windows

#### ADB (Android Debug Bridge)

**Option A: Android Studio (Recommended)**
1. Install [Android Studio](https://developer.android.com/studio)
2. ADB is included automatically (default path: `%LOCALAPPDATA%\Android\Sdk\platform-tools\`)
3. Add to system PATH:
   - Press `Win + S` → Search "Environment Variables" → **Edit the system environment variables**
   - Click **Environment Variables** → Select **Path** → **Edit** → **New**
   - Enter `%LOCALAPPDATA%\Android\Sdk\platform-tools` → OK

**Option B: Standalone Platform-Tools**
1. Download [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) for Windows
2. Extract to a folder (e.g., `C:\platform-tools`)
3. Add that folder to system PATH

**Verify installation:**
```cmd
adb version
```

#### FFmpeg

**Option A: winget (Recommended)**
```cmd
winget install Gyan.FFmpeg
```

**Option B: Manual Install**
1. Download `ffmpeg-release-essentials.zip` from [gyan.dev/ffmpeg](https://www.gyan.dev/ffmpeg/builds/)
2. Extract to a folder (e.g., `C:\ffmpeg`)
3. Add `C:\ffmpeg\bin` to system PATH

**Option C: Scoop**
```cmd
scoop install ffmpeg
```

**Option D: Chocolatey**
```cmd
choco install ffmpeg
```

**Verify installation:**
```cmd
ffmpeg -version
ffprobe -version
```

### Android Device Setup

1. On your device: **Settings** → **About phone** → Tap **Build number** 7 times (enables Developer options)
2. **Settings** → **Developer options** → Enable **USB debugging**
3. Connect device to PC via USB cable
4. Tap **Allow** on the "Allow USB debugging?" dialog on your device
5. Verify connection:
   ```bash
   adb devices
   ```
   Your device serial should appear with `device` status

## Installation

### Option 1: Download DMG (macOS)
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
   java -jar AndroidRewindRecorder-<os>-<arch>-1.0.1.jar
   ```

### Option 3: Build from Source

**macOS / Linux:**
```bash
./gradlew run
```

**Windows:**
```cmd
gradlew.bat run
```

#### Package Installer

```bash
# macOS DMG (requires JDK with jpackage)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew packageDmg

# Windows MSI/EXE (requires WiX Toolset for MSI)
gradlew.bat packageMsi
gradlew.bat packageExe

# Cross-platform JAR
./gradlew packageUberJarForCurrentOS
```

## Usage

1. Connect Android device with USB debugging enabled
2. Launch app → Click **Start** when device detected
3. Press shortcut when bug occurs

### Shortcuts

| macOS | Windows | Action |
|-------|---------|--------|
| `⌘R` | `Ctrl+R` | Start/Stop recording |
| `⌘P` | `Ctrl+P` | Take screenshot |
| `⌘S` | `Ctrl+S` | Save last 30 seconds |
| `⌘⇧S` | `Ctrl+Shift+S` | Save custom duration |

### Settings

- **Buffer size**: Memory storage time (10s ~ 10min)
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
```
- macOS: `ls ~/Desktop/AndroidRecordings/`
- Windows: `dir %USERPROFILE%\Desktop\AndroidRecordings\`

### Windows: ADB/FFmpeg not found
Verify that ADB and FFmpeg are registered in the PATH environment variable:
```cmd
where adb
where ffmpeg
```
If the commands do not print a path, you need to add the installation directory to your system PATH.

## Tech Stack

- Kotlin 1.9.21
- Compose Multiplatform 1.5.11
- ADB + FFmpeg

## License

MIT License
