package ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import config.AppSettings
import kotlinx.coroutines.launch
import recorder.AdbScreenCapture
import recorder.FrameBuffer
import recorder.VideoEncoder
import ui.theme.AppTheme

@Composable
fun App() {
    AppTheme {
        val scope = rememberCoroutineScope()
        val focusRequester = remember { FocusRequester() }

        var isRecording by remember { mutableStateOf(false) }
        var bufferDuration by remember { mutableStateOf(AppSettings.bufferDuration) }
        var fps by remember { mutableStateOf(AppSettings.fps) }
        var statusMessage by remember { mutableStateOf("Ready") }
        var connectedDevice by remember { mutableStateOf<String?>(null) }
        var isSaving by remember { mutableStateOf(false) }
        var frameCount by remember { mutableStateOf(0) }
        var currentMemoryMB by remember { mutableStateOf(0) }  // 버퍼가 점유 중인 메모리

        val frameBuffer = remember { FrameBuffer(maxDurationSeconds = bufferDuration, fps = fps) }
        val adbCapture = remember { AdbScreenCapture() }
        val videoEncoder = remember { VideoEncoder().apply { setOutputDirectory(AppSettings.outputPath) } }

        // 대화상자 상태
        var showSaveDialog by remember { mutableStateOf(false) }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var customDuration by remember { mutableStateOf("60") }

        // 터치 포인터 표시 설정 (저장된 값 로드)
        var showTouchPointer by remember { mutableStateOf(AppSettings.showTouchPointer) }
        // 타임스탬프 오버레이 설정 (저장된 값 로드)
        var showTimestampOverlay by remember { mutableStateOf(AppSettings.showTimestampOverlay) }

        // 설정 대화상자용 임시 값
        var tempBuffer by remember { mutableStateOf(bufferDuration.toString()) }
        var tempFps by remember { mutableStateOf(fps.toString()) }
        var tempOutputPath by remember { mutableStateOf(videoEncoder.getOutputDirectory()) }
        var tempShowTouchPointer by remember { mutableStateOf(showTouchPointer) }
        var tempShowTimestamp by remember { mutableStateOf(showTimestampOverlay) }

        // 초기 포커스 요청
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        // 버퍼 설정 변경 시 업데이트
        LaunchedEffect(bufferDuration, fps) {
            frameBuffer.updateSettings(bufferDuration, fps)
        }

        // 디바이스 연결 확인
        LaunchedEffect(Unit) {
            connectedDevice = adbCapture.getConnectedDevice()
            statusMessage = if (connectedDevice != null) {
                "Device: $connectedDevice"
            } else {
                "No device connected"
            }
        }

        // 레코딩 루프
        LaunchedEffect(isRecording, showTouchPointer) {
            if (isRecording && connectedDevice != null) {
                frameBuffer.clear()  // 새 녹화 시작 시 버퍼 초기화
                frameCount = 0
                currentMemoryMB = 0
                adbCapture.setPointerLocation(showTouchPointer)  // 터치 포인터 설정
                statusMessage = "Recording..."
                adbCapture.startCapturing(fps) { frame ->
                    frameBuffer.addFrame(frame)
                    frameCount = frameBuffer.getFrameCount()
                    currentMemoryMB = frameBuffer.getTotalMemoryMB()
                }
            } else {
                adbCapture.stopCapturing()
                // isSaving 중이 아닐 때만 포인터 해제 (ADB 충돌 방지)
                if (!isSaving) {
                    adbCapture.setPointerLocation(false)
                    statusMessage = if (connectedDevice != null) "Stopped" else "No device connected"
                }
            }
        }

        // isSaving 종료 후 포인터 해제
        LaunchedEffect(isSaving) {
            if (!isSaving && !isRecording) {
                adbCapture.setPointerLocation(false)
            }
        }

        // 스크린샷 함수
        fun takeScreenshot() {
            if (isSaving) return
            if (connectedDevice == null) {
                statusMessage = "No device connected"
                return
            }

            scope.launch {
                isSaving = true
                statusMessage = "Taking screenshot..."
                try {
                    val process = ProcessBuilder(adbCapture.adbPath, "exec-out", "screencap", "-p")
                        .redirectErrorStream(false)
                        .start()
                    val bytes = process.inputStream.readBytes()
                    process.waitFor()

                    if (bytes.isNotEmpty() && process.exitValue() == 0) {
                        val outputPath = videoEncoder.saveScreenshot(bytes)
                        statusMessage = if (outputPath != null) {
                            "Screenshot: ${outputPath.substringAfterLast("/")}"
                        } else {
                            "Failed to save screenshot"
                        }
                    } else {
                        statusMessage = "Failed to capture screenshot"
                    }
                } catch (e: Exception) {
                    statusMessage = "Error: ${e.message}"
                } finally {
                    isSaving = false
                }
            }
        }

        // 저장 함수
        fun saveRecording(durationSeconds: Int) {
            if (isSaving) return

            scope.launch {
                val currentFrameCount = frameBuffer.getFrameCount()
                if (currentFrameCount == 0) {
                    statusMessage = "No frames to save"
                    return@launch
                }

                isSaving = true
                statusMessage = "Saving ${durationSeconds}s..."

                try {
                    val frames = frameBuffer.getFramesWithTimestamp(durationSeconds)
                    if (frames.isEmpty()) {
                        statusMessage = "No frames in range"
                        return@launch
                    }

                    val actualFps = frameBuffer.calculateActualFps(frames)
                    val outputPath = videoEncoder.encodeWithTimestamp(frames, actualFps, showTimestamp = showTimestampOverlay)

                    statusMessage = if (outputPath != null) {
                        "Saved: ${outputPath.substringAfterLast("/")}"
                    } else {
                        "Failed to save"
                    }
                } catch (e: Exception) {
                    statusMessage = "Error: ${e.message}"
                } finally {
                    isSaving = false
                }
            }
        }

        // 커스텀 저장 대화상자
        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = {
                    showSaveDialog = false
                    focusRequester.requestFocus()
                },
                title = { Text("Save Recording") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customDuration,
                            onValueChange = { customDuration = it.filter { c -> c.isDigit() } },
                            label = { Text("Duration (sec)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Buffer: ${frameBuffer.getFrameCount()} frames (~${frameBuffer.getFrameCount() / fps}s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            customDuration.toIntOrNull()?.let { duration ->
                                if (duration > 0) saveRecording(duration)
                            }
                            showSaveDialog = false
                            focusRequester.requestFocus()
                        },
                        enabled = customDuration.toIntOrNull()?.let { it > 0 } == true
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showSaveDialog = false
                        focusRequester.requestFocus()
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // 설정 대화상자 (컴팩트 버전)
        if (showSettingsDialog) {
            Dialog(
                onDismissRequest = {
                    showSettingsDialog = false
                    focusRequester.requestFocus()
                }
            ) {
                Card(
                    modifier = Modifier.width(280.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleSmall
                        )

                        // Buffer와 FPS (한 줄)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            OutlinedTextField(
                                value = tempBuffer,
                                onValueChange = { tempBuffer = it.filter { c -> c.isDigit() } },
                                label = { Text("Buffer (s)", style = MaterialTheme.typography.bodySmall) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f).height(56.dp),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = tempFps,
                                onValueChange = { tempFps = it.filter { c -> c.isDigit() } },
                                label = { Text("FPS", style = MaterialTheme.typography.bodySmall) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f).height(56.dp),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                        }

                        // 저장 경로
                        OutlinedTextField(
                            value = tempOutputPath,
                            onValueChange = { tempOutputPath = it },
                            label = { Text("Output Path", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // 토글 옵션들
                        SettingsToggleRow(
                            label = "Touch Pointer",
                            description = "Display touch location on screen",
                            checked = tempShowTouchPointer,
                            onCheckedChange = { tempShowTouchPointer = it }
                        )
                        SettingsToggleRow(
                            label = "Timestamp Overlay",
                            description = "Show capture time on saved video",
                            checked = tempShowTimestamp,
                            onCheckedChange = { tempShowTimestamp = it }
                        )

                        // 버튼
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    tempBuffer = bufferDuration.toString()
                                    tempFps = fps.toString()
                                    tempOutputPath = videoEncoder.getOutputDirectory()
                                    tempShowTouchPointer = showTouchPointer
                                    tempShowTimestamp = showTimestampOverlay
                                    showSettingsDialog = false
                                    focusRequester.requestFocus()
                                },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(
                                onClick = {
                                    tempBuffer.toIntOrNull()?.let { value ->
                                        if (value in 10..600) {
                                            bufferDuration = value
                                            AppSettings.bufferDuration = value
                                        }
                                    }
                                    tempFps.toIntOrNull()?.let { value ->
                                        if (value in 1..60) {
                                            fps = value
                                            AppSettings.fps = value
                                        }
                                    }
                                    videoEncoder.setOutputDirectory(tempOutputPath)
                                    AppSettings.outputPath = tempOutputPath
                                    showTouchPointer = tempShowTouchPointer
                                    AppSettings.showTouchPointer = tempShowTouchPointer
                                    showTimestampOverlay = tempShowTimestamp
                                    AppSettings.showTimestampOverlay = tempShowTimestamp
                                    AppSettings.flush()
                                    showSettingsDialog = false
                                    focusRequester.requestFocus()
                                },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("Apply", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when {
                            // Cmd/Ctrl + R: Start/Stop 토글
                            event.key == Key.R && (event.isMetaPressed || event.isCtrlPressed) -> {
                                if (connectedDevice != null) {
                                    isRecording = !isRecording
                                }
                                true
                            }
                            // Cmd/Ctrl + P: 스크린샷
                            event.key == Key.P && (event.isMetaPressed || event.isCtrlPressed) && !isSaving -> {
                                takeScreenshot()
                                true
                            }
                            // Cmd/Ctrl + Shift + S: 커스텀 저장
                            event.key == Key.S && (event.isMetaPressed || event.isCtrlPressed) && event.isShiftPressed && !isSaving -> {
                                if (frameBuffer.getFrameCount() > 0) {
                                    showSaveDialog = true
                                }
                                true
                            }
                            // Cmd/Ctrl + S: 30초 저장
                            event.key == Key.S && (event.isMetaPressed || event.isCtrlPressed) && !isSaving -> {
                                if (frameBuffer.getFrameCount() > 0) {
                                    saveRecording(30)
                                }
                                true
                            }
                            else -> false
                        }
                    } else false
                },
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 컨트롤 패널
                ControlPanel(
                    isRecording = isRecording,
                    bufferDuration = bufferDuration,
                    fps = fps,
                    connectedDevice = connectedDevice,
                    isSaving = isSaving,
                    onRecordingToggle = {
                        isRecording = !isRecording
                        focusRequester.requestFocus()
                    },
                    onShowSettings = {
                        tempBuffer = bufferDuration.toString()
                        tempFps = fps.toString()
                        tempOutputPath = videoEncoder.getOutputDirectory()
                        tempShowTouchPointer = showTouchPointer
                        tempShowTimestamp = showTimestampOverlay
                        showSettingsDialog = true
                    },
                    onRefreshDevice = {
                        scope.launch {
                            connectedDevice = adbCapture.getConnectedDevice()
                            statusMessage = if (connectedDevice != null) {
                                "Device: $connectedDevice"
                            } else {
                                "No device connected"
                            }
                        }
                        focusRequester.requestFocus()
                    }
                )

                Spacer(modifier = Modifier.weight(1f))

                // 상태 표시
                StatusBar(
                    statusMessage = statusMessage,
                    frameCount = frameCount,
                    isRecording = isRecording,
                    isSaving = isSaving,
                    memoryMB = currentMemoryMB
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Checkbox(
            modifier = Modifier.size(24.dp),
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
