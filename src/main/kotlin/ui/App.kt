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
import ui.theme.AppTheme

@Composable
fun App() {
    AppTheme {
        val scope = rememberCoroutineScope()
        val focusRequester = remember { FocusRequester() }

        var isRecording by remember { mutableStateOf(false) }
        var bufferDuration by remember { mutableStateOf(AppSettings.bufferDuration) }
        var statusMessage by remember { mutableStateOf("Ready") }
        var connectedDevice by remember { mutableStateOf<String?>(null) }
        var isSaving by remember { mutableStateOf(false) }
        var frameCount by remember { mutableStateOf(0) }
        var currentMemoryMB by remember { mutableStateOf(0) }

        val adbCapture = remember { AdbScreenCapture() }

        // 대화상자 상태
        var showSaveDialog by remember { mutableStateOf(false) }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var customDuration by remember { mutableStateOf("60") }

        // 터치 포인터 표시 설정
        var showTouchPointer by remember { mutableStateOf(AppSettings.showTouchPointer) }

        // 설정 대화상자용 임시 값
        var tempBuffer by remember { mutableStateOf(bufferDuration.toString()) }
        var tempOutputPath by remember { mutableStateOf(adbCapture.muxer.getOutputDirectory()) }
        var tempShowTouchPointer by remember { mutableStateOf(showTouchPointer) }

        // 초기 포커스 요청
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        // 버퍼 설정은 SampleRingBuffer에서 고정값 사용 (120s, 200MB)

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
                adbCapture.sampleBuffer.clear()
                frameCount = 0
                currentMemoryMB = 0
                adbCapture.setPointerLocation(showTouchPointer)
                statusMessage = "Recording..."

                // 디바이스 해상도 기반 maxSize 계산
                val resolution = adbCapture.getDeviceResolution()
                val maxSize = if (resolution != null) {
                    minOf(resolution.first, resolution.second).coerceAtMost(1280)
                } else {
                    1280
                }

                adbCapture.startCapturing(
                    maxSize = maxSize,
                    onSampleReceived = {
                        frameCount = adbCapture.sampleBuffer.getFrameCount()
                        currentMemoryMB = adbCapture.sampleBuffer.getTotalMemoryMB()
                    },
                    onError = { error ->
                        statusMessage = error
                        isRecording = false
                    }
                )
            } else {
                adbCapture.stopCapturing()
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
                    val outputPath = adbCapture.saveScreenshot()
                    statusMessage = if (outputPath != null) {
                        "Screenshot: ${outputPath.substringAfterLast("/")}"
                    } else {
                        "Failed to save screenshot"
                    }
                } catch (e: Exception) {
                    statusMessage = "Error: ${e.message}"
                } finally {
                    isSaving = false
                }
            }
        }

        // 저장 함수 (Sample → MP4)
        fun saveRecording(durationSeconds: Int) {
            if (isSaving) return

            scope.launch {
                val currentFrameCount = adbCapture.sampleBuffer.getFrameCount()
                if (currentFrameCount == 0) {
                    statusMessage = "No frames to save"
                    return@launch
                }

                isSaving = true
                statusMessage = "Saving ${durationSeconds}s..."

                try {
                    val outputPath = adbCapture.saveRecording(durationSeconds)

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
                            text = "Buffer: ${adbCapture.sampleBuffer.getFrameCount()} frames, ${adbCapture.sampleBuffer.getTotalMemoryMB()}MB",
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

        // 설정 대화상자
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

                        // Buffer 설정
                        OutlinedTextField(
                            value = tempBuffer,
                            onValueChange = { tempBuffer = it.filter { c -> c.isDigit() } },
                            label = { Text("Buffer size(s)", style = MaterialTheme.typography.bodySmall) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )

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

                        // 터치 포인터 토글
                        SettingsToggleRow(
                            label = "Touch Pointer",
                            description = "Display touch location on screen",
                            checked = tempShowTouchPointer,
                            onCheckedChange = { tempShowTouchPointer = it }
                        )

                        // 버튼
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    tempBuffer = bufferDuration.toString()
                                    tempOutputPath = adbCapture.muxer.getOutputDirectory()
                                    tempShowTouchPointer = showTouchPointer
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
                                    adbCapture.muxer.setOutputDirectory(tempOutputPath)
                                    AppSettings.outputPath = tempOutputPath
                                    showTouchPointer = tempShowTouchPointer
                                    AppSettings.showTouchPointer = tempShowTouchPointer
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
                                if (adbCapture.sampleBuffer.getFrameCount() > 0) {
                                    showSaveDialog = true
                                }
                                true
                            }
                            // Cmd/Ctrl + S: 30초 저장
                            event.key == Key.S && (event.isMetaPressed || event.isCtrlPressed) && !isSaving -> {
                                if (adbCapture.sampleBuffer.getFrameCount() > 0) {
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
                    fps = 30,  // NAL 방식에서는 고정 (screenrecord 기본값)
                    connectedDevice = connectedDevice,
                    isSaving = isSaving,
                    onRecordingToggle = {
                        isRecording = !isRecording
                        focusRequester.requestFocus()
                    },
                    onShowSettings = {
                        tempBuffer = bufferDuration.toString()
                        tempOutputPath = adbCapture.muxer.getOutputDirectory()
                        tempShowTouchPointer = showTouchPointer
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
