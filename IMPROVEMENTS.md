# Improvement Plan

## Summary

| Category | Critical | High | Medium | Low | Total |
|----------|----------|------|--------|-----|-------|
| Functional Bug | 2 | 3 | 1 | - | 6 |
| Bug / Reliability | 3 | 2 | 1 | 1 | 7 |
| Performance | 1 | 1 | 1 | - | 3 |
| UX | - | 2 | 3 | - | 5 |
| Architecture | - | 1 | 2 | - | 3 |
| **Total** | **6** | **9** | **8** | **1** | **24** |

---

## Functional Bugs

Bugs that cause **incorrect behavior** — wrong output, data loss, or broken functionality during normal usage.

### F-1. Stopping during save deletes segment files mid-read (Critical)

- **File**: `src/main/kotlin/ui/App.kt:95-101`, `src/main/kotlin/recorder/AdbScreenCapture.kt:151-153`
- **Repro**: Press `Ctrl+S` to save, then `Ctrl+R` to stop before save completes
- **Problem**: `stopCapturing()` calls `sampleBuffer.cleanup()` which deletes **all segment files** on disk. But FFmpeg is still reading those files for the ongoing save operation. Result: FFmpeg fails or produces a corrupt/truncated video.
- **Root cause**: The `LaunchedEffect` else branch calls `stopCapturing()` without checking `isSaving`.

```
Timeline:
  Ctrl+S  → isSaving=true → FFmpeg starts reading seg_A_*.mp4, seg_B_*.mp4 ...
  Ctrl+R  → isRecording=false
         → LaunchedEffect else → stopCapturing()
         → sampleBuffer.cleanup() → deletes seg_A_*.mp4, seg_B_*.mp4
         → FFmpeg: file not found → FAIL
```

- **Fix**: Guard `stopCapturing()` to wait for save completion, or defer `cleanup()` until `isSaving` is false.

```kotlin
// App.kt LaunchedEffect else branch
} else {
    adbCapture.stopCapturing(keepSegments = isSaving) // don't cleanup while saving
    ...
}
```

### F-2. Video recorded as square with black bars (High)

- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:201`
- **Repro**: Record on any non-square device (all phones)
- **Problem**: `--size ${size}x${size}` passes a square resolution (e.g., `1080x1080`) to `screenrecord`. On a 1080x2400 portrait device, the screen is scaled to fit in the square frame with **black bars** on both sides. The output video is always square regardless of device orientation.

```kotlin
// Current: always square
"--size", "${size}x${size}",   // → --size 1080x1080

// Device 1080x2400 → recorded as 486x1080 centered in 1080x1080 with black bars
```

- **Fix**: Pass the device resolution scaled proportionally.

```kotlin
// Calculate proportional size
val (w, h) = resolution ?: Pair(size, size)
val scale = size.toDouble() / minOf(w, h)
val recW = (w * scale).toInt()
val recH = (h * scale).toInt()
"--size", "${recW}x${recH}",  // → --size 1080x2400 (proportional)
```

### F-3. Output path change only applies to concat, not screenshots or segments (High)

- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:17-18`
- **Repro**: Change Output Path in settings, then take a screenshot or save a recording
- **Problem**: `outputDir` and `segmentsDir` are `val` fields set at construction. Changing the path in settings only updates `concatenator.outputDir` via `setOutputDirectory()`. Afterwards:
  - **Screenshots** → saved to the **old** path (`AdbScreenCapture.outputDir`)
  - **New segments** → written to the **old** `.segments/` directory
  - **Concat output** → saved to the **new** path, but reads segment files from the old path (works by absolute path, but semantically wrong)

```kotlin
private val outputDir = File(AppSettings.outputPath)      // val — fixed at construction
private val segmentsDir = File(outputDir, ".segments")     // val — fixed at construction
```

- **Fix**: Make `outputDir` and `segmentsDir` respond to path changes, or recreate `AdbScreenCapture` when path changes.

### F-4. Touch pointer toggle during recording clears entire buffer (High)

- **File**: `src/main/kotlin/ui/App.kt:68-70`
- **Repro**: Record for 5 minutes, then toggle Touch Pointer in settings
- **Problem**: `showTouchPointer` is a key of `LaunchedEffect(isRecording, showTouchPointer)`. When the value changes, the effect restarts and calls `sampleBuffer.clear()`, which **deletes all 5 minutes of buffered segments**. Then `startCapturing()` is called, but since `isRunning` is already true, it returns immediately without restarting. Net effect: all buffered data is lost, recording continues from zero.

```
User: records 5 minutes
User: toggles Touch Pointer
LaunchedEffect restarts →
  sampleBuffer.clear()         ← 5 minutes of data deleted!
  startCapturing()             ← isRunning=true → returns immediately (no-op)
  Recording continues, but buffer is empty
```

- **Fix**: Remove `showTouchPointer` from `LaunchedEffect` keys. Apply pointer setting separately without restarting the effect.

```kotlin
// Separate effect for pointer setting only
LaunchedEffect(showTouchPointer) {
    if (isRecording) {
        adbCapture.setPointerLocation(showTouchPointer)
    }
}

// Recording effect — no longer depends on showTouchPointer
LaunchedEffect(isRecording) {
    if (isRecording && connectedDevice != null) {
        // Don't clear buffer here — only clear on explicit new recording start
        ...
    }
}
```

### F-5. concatPrecise fallback produces duplicate frames (Medium)

- **File**: `src/main/kotlin/recorder/SegmentConcatenator.kt:155-156`
- **Repro**: `concatPrecise` fails (e.g., FFmpeg filter error) and falls back to `concatSimple`
- **Problem**: The dual recorder creates overlapping segments (3-second overlap between Slot A and B). `concatPrecise` trims these overlaps, but the fallback `concatSimple` does **not** trim. All overlap regions appear twice in the output, causing the same scene to repeat. A 30-second save may produce 45+ seconds of video with duplicate frames.

```
Segment timeline (with 3s overlap):
  A1: [0s ─── 5s]
  B1:    [2s ─── 7s]
  A2:       [4s ─── 9s]

concatPrecise: trims overlaps → [0s─5s][5s─7s][7s─9s] = 9s ✓
concatSimple:  no trimming    → [0s─5s][2s─7s][4s─9s] = 15s (6s duplicated) ✗
```

- **Fix**: Apply basic overlap trimming in `concatSimple` using `-ss` (seek), or fail explicitly instead of silently producing wrong output.

### F-6. Screenshot temp file path collision on device (Low)

- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:195`
- **Repro**: Rapid consecutive screenshots (unlikely in practice due to `isSaving` guard)
- **Problem**: The device temp file is hardcoded as `/sdcard/screenshot_temp.png`. The cleanup process (`adb shell rm`) at line 210 is launched without `waitFor()`. If a second screenshot starts before the `rm` completes, the second screenshot's `screencap` may collide with the first's `rm`.

```kotlin
val remotePath = "/sdcard/screenshot_temp.png"   // fixed name for all screenshots
```

- **Fix**: Use a unique temp filename per screenshot (e.g., include timestamp).

```kotlin
val remotePath = "/sdcard/screenshot_${timestamp}.png"
```

---

## Reliability & Infrastructure

Issues that don't cause wrong output under normal use, but can cause hangs, crashes, or resource leaks.

## Critical

### C-1. `concatPrecise` uses libx264 re-encoding unnecessarily

- **File**: `src/main/kotlin/recorder/SegmentConcatenator.kt:143-144`
- **Problem**: Segments are already H.264 encoded by `screenrecord`, but `concatPrecise` decodes and re-encodes them with `libx264`. This makes saving a 30-second video take 30+ seconds with high CPU usage.
- **Fix**: Replace `filter_complex` + `libx264` approach with `concat` protocol + `-c:v copy`. Handle overlap trimming by using `-ss` (seek) per input instead of trim filters.

```kotlin
// Before (slow, re-encodes)
"-filter_complex", filterComplex,
"-map", "[outv]",
"-c:v", "libx264",
"-preset", "ultrafast",

// After (fast, stream copy)
"-f", "concat",
"-safe", "0",
"-i", concatListFile.absolutePath,
"-c", "copy",
```

### C-2. No resource cleanup on app exit

- **File**: `src/main/kotlin/ui/App.kt:35`
- **Problem**: `AdbScreenCapture` is created with `remember {}` but never cleaned up. When the app window closes, `DualSegmentRecorder`'s coroutine scope and ADB `screenrecord` processes on the device keep running.
- **Fix**: Add `DisposableEffect` to stop recording and clean up on unmount.

```kotlin
val adbCapture = remember { AdbScreenCapture() }

DisposableEffect(Unit) {
    onDispose {
        adbCapture.stopCapturing()
        adbCapture.setPointerLocation(false)
    }
}
```

### C-3. `getConnectedDevice()` can hang forever

- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:55-61`
- **Problem**: `adb devices` is called with no timeout. If ADB daemon is unresponsive, `readText()` blocks indefinitely, freezing the UI at startup.
- **Fix**: Add `withTimeout` or use `process.waitFor(timeout, unit)`.

```kotlin
val process = ProcessBuilder(PathFinder.adbPath, "devices")
    .redirectErrorStream(true).start()

if (!process.waitFor(5, TimeUnit.SECONDS)) {
    process.destroyForcibly()
    return@withContext null
}
```

### C-4. Buffer overflow when protected segments block eviction

- **File**: `src/main/kotlin/recorder/SegmentRingBuffer.kt:199-216`
- **Problem**: `enforceConstraints()` stops removing segments if the oldest one is protected. Meanwhile new segments keep being added, causing unbounded disk usage.
- **Fix**: Skip protected segments and continue evicting the next unprotected one.

```kotlin
// Before: breaks entirely
if (oldest != null && protectedSegments.contains(oldest)) {
    break
}

// After: skip protected, remove next unprotected
val removable = segments.firstOrNull { !protectedSegments.contains(it) }
if (removable == null) break
segments.remove(removable)
removable.file.delete()
```

---

## High

### H-1. `screenrecord` exit code 1 accepted without file validation

- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:214-215`
- **Problem**: Exit code 1 is treated as success, but the output file may not exist or be corrupt. No check on actual file existence or size before proceeding to `adb pull`.
- **Fix**: Verify the remote file exists before pulling.

```kotlin
return@withContext (exitCode == 0 || exitCode == 1)
// Add after: verify remote file
//   adb shell "ls -la $remotePath" and check output
```

### H-2. Screenshot cleanup process never awaited

- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:209-212`
- **Problem**: `adb shell rm` is launched via `ProcessBuilder.start()` but never waited for. The process becomes a zombie, and the temp file may remain on the device.
- **Fix**: Add `.waitFor()`.

```kotlin
// Before
ProcessBuilder(PathFinder.adbPath, "shell", "rm", remotePath).start()

// After
ProcessBuilder(PathFinder.adbPath, "shell", "rm", remotePath).start().waitFor()
```

### H-3. Saved file path display broken on Windows

- **File**: `src/main/kotlin/ui/App.kt:125, 155`
- **Problem**: `substringAfterLast("/")` is used to extract the filename, but Windows paths use `\`. On Windows, the entire absolute path is shown in the status bar.
- **Fix**: Use `File.name` or `substringAfterLast(File.separator)`.

```kotlin
// Before
"Saved: ${outputPath.substringAfterLast("/")}"

// After
"Saved: ${File(outputPath).name}"
```

### H-4. Internal recording errors only logged to console

- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:217, 237, 279`
- **Problem**: Errors during `recordSegment`, `pullSegment`, and `getVideoDurationMs` are logged with `println` which is invisible in a desktop app. Users see no feedback.
- **Fix**: Propagate errors through `onErrorCallback` or a logging system visible to the UI.

### H-5. Device only detected once at startup

- **File**: `src/main/kotlin/ui/App.kt:58-65`
- **Problem**: `LaunchedEffect(Unit)` runs once. If the device is connected after app launch, the user must manually click "Refresh".
- **Fix**: Add periodic polling (e.g., every 3 seconds) or use `adb track-devices`.

```kotlin
LaunchedEffect(Unit) {
    while (true) {
        connectedDevice = adbCapture.getConnectedDevice()
        statusMessage = if (connectedDevice != null)
            "Device: $connectedDevice" else "No device connected"
        delay(3000)
    }
}
```

### H-6. `DualSegmentRecorder` uses manual Job management

- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:28-30`
- **Problem**: `slotAJob` and `slotBJob` are nullable vars managed manually. If `stop()` is not called in all code paths, jobs and the scope leak.
- **Fix**: Use structured concurrency with a single `CoroutineScope` and `cancelChildren()`.

---

## Medium

### M-1. `SegmentRingBuffer.getTotalDurationSeconds()` re-sorts on every call

- **File**: `src/main/kotlin/recorder/SegmentRingBuffer.kt:169-174`
- **Problem**: Called inside `enforceConstraints()` which loops until constraints are met. Each iteration sorts all segments and recalculates overlaps. This is O(n^2).
- **Fix**: Cache the sorted list and effective duration, or calculate incrementally.

### M-2. `PathFinder` silently falls back to bare command name

- **File**: `src/main/kotlin/config/PathFinder.kt:67`
- **Problem**: If `adb` / `ffmpeg` is not found anywhere, `findExecutable` returns just `"adb"`. This causes a cryptic `IOException: Cannot run program "adb"` later during recording.
- **Fix**: Validate at app startup and show a clear error dialog if tools are missing.

### M-3. No save duration validation against buffer

- **File**: `src/main/kotlin/ui/App.kt:148-149`
- **Problem**: User can request 120 seconds of recording when buffer only has 30 seconds. The save completes but the output is shorter than expected with no explanation.
- **Fix**: Show the actual available duration in the save dialog, or warn the user.

```kotlin
val availableDuration = adbCapture.sampleBuffer.getTotalDurationSeconds()
if (durationSeconds > availableDuration) {
    statusMessage = "Saving ${availableDuration.toInt()}s (requested ${durationSeconds}s)"
}
```

### M-4. FFmpeg save progress not shown to user

- **File**: `src/main/kotlin/recorder/SegmentConcatenator.kt:149-151`
- **Problem**: During `concatPrecise`, the user sees "Saving..." with no progress indication. If re-encoding takes 30+ seconds, the user assumes the app is frozen.
- **Fix**: Parse FFmpeg's stderr progress output (`time=00:00:15.00`) and report to UI via callback, or show an indeterminate progress bar.

### M-5. `protectedSegments` not thread-safe

- **File**: `src/main/kotlin/recorder/SegmentRingBuffer.kt:15`
- **Problem**: `protectedSegments` is a plain `mutableSetOf()` while `segments` is `ConcurrentLinkedDeque`. The `@Synchronized` annotation protects `protectSegments()` / `unprotectSegments()`, but `enforceConstraints()` reads `protectedSegments.contains()` inside a different lock scope.
- **Fix**: Use `ConcurrentHashMap.newKeySet()` or ensure all access is under the same synchronization.

### M-6. `getDeviceResolution()` blocks on IO without timeout

- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:77-98`
- **Problem**: Synchronous `ProcessBuilder` call with no timeout. If ADB is slow, the recording start is delayed indefinitely.
- **Fix**: Use `process.waitFor(timeout, unit)` and make the function `suspend`.

### M-7. `.segments` folder visible on Windows

- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:18`
- **Problem**: Folders prefixed with `.` are hidden on macOS/Linux but visible on Windows.
- **Fix**: Set the hidden attribute on Windows after creating the directory.

```kotlin
if (System.getProperty("os.name").lowercase().contains("win")) {
    Runtime.getRuntime().exec(arrayOf("attrib", "+H", segmentsDir.absolutePath))
}
```

---

## Low

### L-1. Frame count estimate assumes constant 30fps

- **File**: `src/main/kotlin/recorder/SegmentRingBuffer.kt:191`
- **Problem**: `getFrameCount()` returns `(getTotalDurationSeconds() * 30).toInt()`. Actual frame rate may vary due to VFR. The number is misleading.
- **Fix**: Display buffer duration in seconds instead of estimated frame count.
