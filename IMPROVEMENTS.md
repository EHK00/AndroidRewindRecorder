# Improvement Plan

## Summary

| Severity | Count | Description |
|----------|-------|-------------|
| Critical | 3 | Data loss, wrong output on every use, major performance block |
| High | 8 | Silent data loss, process leaks, hangs, crashes in specific scenarios |
| Medium | 10 | Partial functionality, missing feedback, workarounds exist |
| Low | 8 | Cosmetic, rare edge cases, architecture concerns |
| **Total** | **29** | |

### Priority Changes from Previous Assessment

| ID | Previous | New | Reason |
|----|----------|-----|--------|
| F-2 | High | **Critical** | Affects **every recording** on every device — most visible bug |
| F-3 | High | Medium | Rare trigger (only when user changes output path) |
| R-4 | Medium | **High** | Causes cascading failures on every Stop→Start cycle |
| M-5 | Medium | **High** | Thread safety bug can cause data corruption or crash |
| C-2 | Critical | High | Resource leak, not data loss — serious but not critical |
| C-3 | Critical | High | Rare trigger (only when ADB daemon is unresponsive) |
| C-4 | Critical | High | Requires specific timing to trigger |
| R-2 | Medium | Low | Cosmetic only — timestamp overlay doesn't break functionality |
| H-1 | High | Medium | Low probability edge case |
| H-2 | High | Low | Minimal functional impact |
| H-6 | High | Low | Architecture concern, not user-facing |
| M-1 | Medium | Low | Negligible with typical segment counts |
| M-7 | Medium | Low | Purely cosmetic |

---

## Critical

Issues that cause wrong output, data loss, or severely degrade core functionality on every use.

### 1. Video recorded as square with black bars

- **ID**: F-2
- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:201`
- **Repro**: Record on any non-square device (all phones)
- **Impact**: **Every single recording** has wrong aspect ratio. This is the most visible bug — 100% of users are affected.
- **Problem**: `--size ${size}x${size}` passes a square resolution (e.g., `1080x1080`) to `screenrecord`. On a 1080x2400 portrait device, the screen is scaled to fit in the square frame with black bars on both sides.

```kotlin
// Current: always square
"--size", "${size}x${size}",   // → --size 1080x1080

// Device 1080x2400 → recorded as 486x1080 centered in 1080x1080 with black bars
```

- **Fix**: Pass the device resolution scaled proportionally.

```kotlin
val (w, h) = resolution ?: Pair(size, size)
val scale = size.toDouble() / minOf(w, h)
val recW = (w * scale).toInt()
val recH = (h * scale).toInt()
"--size", "${recW}x${recH}",  // → --size 1080x2400 (proportional)
```

### 2. Stopping during save deletes segment files mid-read

- **ID**: F-1
- **File**: `src/main/kotlin/ui/App.kt:95-101`, `src/main/kotlin/recorder/AdbScreenCapture.kt:151-153`
- **Repro**: Press `Ctrl+S` to save, then `Ctrl+R` to stop before save completes
- **Impact**: FFmpeg fails or produces a corrupt/truncated video. User loses the recording.
- **Problem**: `stopCapturing()` calls `sampleBuffer.cleanup()` which deletes all segment files on disk while FFmpeg is still reading them.

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

### 3. `concatPrecise` uses libx264 re-encoding unnecessarily

- **ID**: C-1
- **File**: `src/main/kotlin/recorder/SegmentConcatenator.kt:143-144`
- **Impact**: Saving a 30-second video takes 30+ seconds with high CPU usage. Every save is ~30x slower than necessary.
- **Problem**: Segments are already H.264 encoded by `screenrecord`, but `concatPrecise` decodes and re-encodes them with `libx264`.

```kotlin
// Before (slow, re-encodes)
"-filter_complex", filterComplex,
"-map", "[outv]",
"-c:v", "libx264",
"-preset", "ultrafast",

// After (fast, stream copy — ~1 second)
"-f", "concat",
"-safe", "0",
"-i", concatListFile.absolutePath,
"-c", "copy",
```

- **Fix**: Replace `filter_complex` + `libx264` with `concat` protocol + `-c:v copy`. Handle overlap trimming by using `-ss` (seek) per input instead of trim filters.

---

## High

Significant bugs that cause data loss, process leaks, or hangs in specific but reproducible scenarios.

### 4. Touch pointer toggle during recording clears entire buffer

- **ID**: F-4
- **File**: `src/main/kotlin/ui/App.kt:68-70`
- **Repro**: Record for 5 minutes, then toggle Touch Pointer in settings
- **Impact**: All buffered segments silently deleted. Recording continues but buffer is empty.
- **Problem**: `showTouchPointer` is a key of `LaunchedEffect(isRecording, showTouchPointer)`. When it changes, the effect restarts and calls `sampleBuffer.clear()`.

```
User: records 5 minutes
User: toggles Touch Pointer
LaunchedEffect restarts →
  sampleBuffer.clear()         ← 5 minutes of data deleted!
  startCapturing()             ← isRunning=true → returns immediately (no-op)
  Recording continues, but buffer is empty
```

- **Fix**: Remove `showTouchPointer` from `LaunchedEffect` keys. Apply pointer setting separately.

```kotlin
LaunchedEffect(showTouchPointer) {
    if (isRecording) {
        adbCapture.setPointerLocation(showTouchPointer)
    }
}

LaunchedEffect(isRecording) {
    if (isRecording && connectedDevice != null) { ... }
}
```

### 5. `stop()` doesn't wait for running ADB processes to finish

- **ID**: R-4
- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:78-84`
- **Repro**: Click Stop while `screenrecord` or `adb pull` is in progress
- **Impact**: Device accumulates `screenrecord` processes. Next recording may fail or run concurrent with the previous one.
- **Problem**: `stop()` calls `scope?.cancel()` but doesn't wait for processes to finish. The device-side `screenrecord` keeps running.

```
Stop clicked →
  scope.cancel() → CancellationException thrown
  cleanupRemote("/sdcard/rec_A_5.mp4") → adb shell rm
  But device-side screenrecord is still running → recreates the file
  Next Start → new screenrecord starts → device has 2 concurrent screenrecord processes
```

- **Fix**: Kill the ADB process and wait for device-side cleanup before returning.

```kotlin
fun stop() {
    isRunning.set(false)
    slotAJob?.cancel()
    slotBJob?.cancel()
    runBlocking { scope?.coroutineContext?.job?.cancelAndJoin() }
    scope = null
}
```

### 6. Multiple devices connected causes all ADB commands to fail

- **ID**: R-1
- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:56`, `src/main/kotlin/recorder/DualSegmentRecorder.kt:196-207`
- **Repro**: Connect two Android devices (or device + emulator), then start recording
- **Impact**: All ADB commands fail silently. App shows device as connected but nothing works.
- **Problem**: `getConnectedDevice()` returns the first device serial, but no ADB command uses `-s <serial>`.

```
$ adb devices
DEVICE_A    device
DEVICE_B    device

$ adb shell screenrecord ...
error: more than one device/emulator    ← every command fails
```

- **Fix**: Pass `-s <serial>` to all ADB commands.

```kotlin
PathFinder.adbPath, "-s", connectedDeviceSerial, "shell", "screenrecord", ...
```

### 7. Corrupt/empty segment accepted into buffer after partial `adb pull`

- **ID**: R-3
- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:153-167`
- **Repro**: Disconnect USB cable briefly during recording (unstable connection)
- **Impact**: Corrupt segment in buffer causes FFmpeg concat to fail or produce corrupt video.
- **Problem**: Validation only checks `segmentFile.exists()`, not file size. A 0-byte file passes.

```kotlin
if (pullSuccess && segmentFile.exists()) {   // ← doesn't check file size
```

- **Fix**: Add minimum file size check.

```kotlin
if (pullSuccess && segmentFile.exists() && segmentFile.length() > 1024) {
```

### 8. `protectedSegments` not thread-safe

- **ID**: M-5
- **File**: `src/main/kotlin/recorder/SegmentRingBuffer.kt:15`
- **Impact**: Race condition can cause a protected segment to be deleted during save, leading to crash or corrupt output.
- **Problem**: `protectedSegments` is a plain `mutableSetOf()` while `segments` is `ConcurrentLinkedDeque`. `enforceConstraints()` reads `protectedSegments.contains()` outside the synchronized scope of `protectSegments()`.
- **Fix**: Use `ConcurrentHashMap.newKeySet()` or ensure all access is under the same synchronization.

### 9. No resource cleanup on app exit

- **ID**: C-2
- **File**: `src/main/kotlin/ui/App.kt:35`
- **Impact**: `screenrecord` processes persist on device after app close. Touch pointer stays enabled.
- **Problem**: `AdbScreenCapture` is created with `remember {}` but never cleaned up on unmount.

```kotlin
val adbCapture = remember { AdbScreenCapture() }

// Fix: Add DisposableEffect
DisposableEffect(Unit) {
    onDispose {
        adbCapture.stopCapturing()
        adbCapture.setPointerLocation(false)
    }
}
```

### 10. `getConnectedDevice()` can hang forever

- **ID**: C-3
- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:55-61`
- **Impact**: App freezes at startup when ADB daemon is unresponsive.
- **Problem**: `adb devices` is called with no timeout. `readText()` blocks indefinitely.

```kotlin
// Fix: Add timeout
if (!process.waitFor(5, TimeUnit.SECONDS)) {
    process.destroyForcibly()
    return@withContext null
}
```

### 11. Buffer overflow when protected segments block eviction

- **ID**: C-4
- **File**: `src/main/kotlin/recorder/SegmentRingBuffer.kt:199-216`
- **Impact**: Unbounded disk usage during save operations.
- **Problem**: `enforceConstraints()` stops removing segments if the oldest one is protected. New segments keep being added.

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

## Medium

Moderate impact issues with workarounds or limited scope. Functionality partially works.

### 12. Output path change only applies to concat, not screenshots or segments

- **ID**: F-3
- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:17-18`
- **Repro**: Change Output Path in settings, then take a screenshot
- **Impact**: Screenshots and segments go to the old path. Only concat output goes to the new path.
- **Problem**: `outputDir` and `segmentsDir` are `val` fields set at construction.

```kotlin
private val outputDir = File(AppSettings.outputPath)      // val — fixed at construction
private val segmentsDir = File(outputDir, ".segments")     // val — fixed at construction
```

- **Fix**: Make paths respond to changes, or recreate `AdbScreenCapture` when path changes.

### 13. `concatPrecise` fallback produces duplicate frames

- **ID**: F-5
- **File**: `src/main/kotlin/recorder/SegmentConcatenator.kt:155-156`
- **Repro**: `concatPrecise` fails (e.g., FFmpeg filter error)
- **Impact**: Output video has duplicate frames at overlap regions. A 30-second save may produce 45+ seconds.
- **Problem**: Fallback `concatSimple` does not trim overlaps.

```
concatPrecise: trims overlaps → [0s─5s][5s─7s][7s─9s] = 9s
concatSimple:  no trimming    → [0s─5s][2s─7s][4s─9s] = 15s (6s duplicated)
```

- **Fix**: Apply basic overlap trimming in `concatSimple` using `-ss`, or fail explicitly.

### 14. `screenrecord` exit code 1 accepted without file validation

- **ID**: H-1
- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:214-215`
- **Impact**: Corrupt segment may be pulled and added to buffer.
- **Problem**: Exit code 1 is treated as success, but the output file may not exist or be corrupt.

```kotlin
return@withContext (exitCode == 0 || exitCode == 1)
// Should verify remote file exists before pulling
```

### 15. Saved file path display broken on Windows

- **ID**: H-3
- **File**: `src/main/kotlin/ui/App.kt:125, 155`
- **Impact**: Full absolute path shown in status bar on Windows instead of just the filename.
- **Problem**: `substringAfterLast("/")` doesn't handle Windows `\` separator.

```kotlin
// Before
"Saved: ${outputPath.substringAfterLast("/")}"

// After
"Saved: ${File(outputPath).name}"
```

### 16. Internal recording errors only logged to console

- **ID**: H-4
- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:217, 237, 279`
- **Impact**: Users get no feedback when internal errors occur. Recording may silently fail.
- **Problem**: Errors are logged with `println` which is invisible in a desktop app.
- **Fix**: Propagate errors through `onErrorCallback` or a logging system visible to the UI.

### 17. Device only detected once at startup

- **ID**: H-5
- **File**: `src/main/kotlin/ui/App.kt:58-65`
- **Impact**: User must manually click "Refresh" if device is connected after launch.
- **Problem**: `LaunchedEffect(Unit)` runs once. No periodic polling.

```kotlin
// Fix: Add periodic polling
LaunchedEffect(Unit) {
    while (true) {
        connectedDevice = adbCapture.getConnectedDevice()
        statusMessage = if (connectedDevice != null)
            "Device: $connectedDevice" else "No device connected"
        delay(3000)
    }
}
```

### 18. `PathFinder` silently falls back to bare command name

- **ID**: M-2
- **File**: `src/main/kotlin/config/PathFinder.kt:67`
- **Impact**: Cryptic `IOException: Cannot run program "adb"` on first run without tools installed.
- **Problem**: If `adb`/`ffmpeg` is not found, `findExecutable` returns just `"adb"` without warning.
- **Fix**: Validate at app startup and show a clear error dialog.

### 19. No save duration validation against buffer

- **ID**: M-3
- **File**: `src/main/kotlin/ui/App.kt:148-149`
- **Impact**: User gets shorter video than expected with no explanation.
- **Problem**: User can request 120 seconds when buffer only has 30 seconds.

```kotlin
val availableDuration = adbCapture.sampleBuffer.getTotalDurationSeconds()
if (durationSeconds > availableDuration) {
    statusMessage = "Saving ${availableDuration.toInt()}s (requested ${durationSeconds}s)"
}
```

### 20. FFmpeg save progress not shown to user

- **ID**: M-4
- **File**: `src/main/kotlin/recorder/SegmentConcatenator.kt:149-151`
- **Impact**: User thinks app is frozen during long saves (30+ seconds with C-1 re-encoding).
- **Problem**: Only "Saving..." is shown with no progress indication.
- **Fix**: Parse FFmpeg's stderr progress output or show an indeterminate progress bar.

### 21. `getDeviceResolution()` blocks on IO without timeout

- **ID**: M-6
- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:77-98`
- **Impact**: Recording start delayed indefinitely if ADB is slow.
- **Problem**: Synchronous `ProcessBuilder` call with no timeout.
- **Fix**: Use `process.waitFor(timeout, unit)` and make the function `suspend`.

---

## Low

Cosmetic issues, near-impossible edge cases, or architecture concerns with no direct user impact.

### 22. `--bugreport` flag always on, `showTimestampOverlay` is dead code

- **ID**: R-2
- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:205`, `src/main/kotlin/config/AppSettings.kt:38`
- **Impact**: Timestamp overlay always appears on recordings (cosmetic).
- **Problem**: `--bugreport` is always added unconditionally. `AppSettings.showTimestampOverlay` is never read.
- **Fix**: Make it conditional or remove the unused setting.

### 23. Screenshot temp file path collision on device

- **ID**: F-6
- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:195`
- **Impact**: Screenshot could fail on rapid consecutive attempts. Near-impossible due to `isSaving` guard.
- **Problem**: Hardcoded `/sdcard/screenshot_temp.png` for all screenshots.
- **Fix**: Use unique temp filename per screenshot.

### 24. Screenshot cleanup process never awaited

- **ID**: H-2
- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:209-212`
- **Impact**: Zombie process, temp file may persist on device. Minimal functional impact.
- **Problem**: `adb shell rm` is launched but never waited for.

```kotlin
// Fix: Add .waitFor()
ProcessBuilder(PathFinder.adbPath, "shell", "rm", remotePath).start().waitFor()
```

### 25. `DualSegmentRecorder` uses manual Job management

- **ID**: H-6
- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:28-30`
- **Impact**: Potential scope/job leak if `stop()` is not called in all code paths.
- **Problem**: `slotAJob` and `slotBJob` are nullable vars managed manually.
- **Fix**: Use structured concurrency with `cancelChildren()`.

### 26. `SegmentRingBuffer.getTotalDurationSeconds()` re-sorts on every call

- **ID**: M-1
- **File**: `src/main/kotlin/recorder/SegmentRingBuffer.kt:169-174`
- **Impact**: O(n^2) in `enforceConstraints()`. Negligible with typical segment counts (~24 segments for 2 minutes).
- **Problem**: Called in a loop, each call re-sorts all segments.
- **Fix**: Cache the sorted list or calculate incrementally.

### 27. `.segments` folder visible on Windows

- **ID**: M-7
- **File**: `src/main/kotlin/recorder/AdbScreenCapture.kt:18`
- **Impact**: User sees temporary folder in output directory (cosmetic).
- **Problem**: `.` prefix doesn't hide folders on Windows.

```kotlin
if (System.getProperty("os.name").lowercase().contains("win")) {
    Runtime.getRuntime().exec(arrayOf("attrib", "+H", segmentsDir.absolutePath))
}
```

### 28. `startTime` records host time, not device recording start

- **ID**: R-5
- **File**: `src/main/kotlin/recorder/DualSegmentRecorder.kt:134`
- **Impact**: Minor trim inaccuracy (~200ms over USB, 1-2s over WiFi ADB).
- **Problem**: Host-side timestamps used for overlap calculation differ from actual video timing.
- **Fix**: Use `ffprobe` to get actual video duration for more accurate overlap trimming.

### 29. Frame count estimate assumes constant 30fps

- **ID**: L-1
- **File**: `src/main/kotlin/recorder/SegmentRingBuffer.kt:191`
- **Impact**: Misleading frame count in UI (cosmetic).
- **Problem**: `getFrameCount()` returns `(getTotalDurationSeconds() * 30).toInt()`. Actual frame rate varies (VFR).
- **Fix**: Display buffer duration in seconds instead of estimated frame count.
