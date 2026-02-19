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

Open PowerShell with administrator privileges and paste the following commands in order.

**1. Install:**
```powershell
winget install Google.PlatformTools
winget install Gyan.FFmpeg
```

**2. Verify (restart terminal first):**
```powershell
adb version
ffmpeg -version
```

> **If commands are not recognized**, run the following to add winget Links to PATH:
> ```powershell
> $linksPath = "$env:LOCALAPPDATA\Microsoft\WinGet\Links"
> $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
> if ($userPath -notlike "*$linksPath*") {
>     [Environment]::SetEnvironmentVariable("Path", "$userPath;$linksPath", "User")
>     Write-Host "Added $linksPath to PATH. Restart terminal." -ForegroundColor Green
> }
> ```

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

### Option 1: Download JAR (Recommended)
1. Go to [Releases](../../releases) and download the JAR for your OS:
   - `AndroidRewindRecorder-<version>-macos.jar` (macOS)
   - `AndroidRewindRecorder-<version>-windows.jar` (Windows)
2. Run with Java 17+:
   ```bash
   java -jar AndroidRewindRecorder-<version>-macos.jar
   ```

### Option 2: Build from Source

**macOS / Linux:**
```bash
./gradlew run
```

**Windows:**
```cmd
gradlew.bat run
```

#### Package Options

```bash
# Uber JAR (recommended)
./gradlew packageUberJarForCurrentOS

# macOS DMG (requires JDK with jpackage)
./gradlew packageDmg

# Windows MSI/EXE (requires WiX Toolset for MSI)
gradlew.bat packageMsi
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
