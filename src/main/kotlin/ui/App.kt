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
import androidx.compose.ui.unit.dp
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
        var bufferDuration by remember { mutableStateOf(60) }
        var fps by remember { mutableStateOf(30) }
        var statusMessage by remember { mutableStateOf("Ready") }
        var connectedDevice by remember { mutableStateOf<String?>(null) }
        var isSaving by remember { mutableStateOf(false) }
        var frameCount by remember { mutableStateOf(0) }
        var currentMemoryMB by remember { mutableStateOf(0) }  // 버퍼가 점유 중인 메모리
        
        val frameBuffer = remember { FrameBuffer(maxDurationSeconds = bufferDuration, fps = fps) }
        val adbCapture = remember { AdbScreenCapture() }
        val videoEncoder = remember { VideoEncoder() }
        
        // 대화상자 상태
        var showSaveDialog by remember { mutableStateOf(false) }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var customDuration by remember { mutableStateOf("60") }
        
        // 설정 대화상자용 임시 값
        var tempBuffer by remember { mutableStateOf(bufferDuration.toString()) }
        var tempFps by remember { mutableStateOf(fps.toString()) }
        var tempOutputPath by remember { mutableStateOf(videoEncoder.getOutputDirectory()) }
        
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
        LaunchedEffect(isRecording) {
            if (isRecording && connectedDevice != null) {
                statusMessage = "Recording..."
                adbCapture.startCapturing(fps) { frame ->
                    frameBuffer.addFrame(frame)
                    frameCount = frameBuffer.getFrameCount()
                    currentMemoryMB = frameBuffer.getTotalMemoryMB()
                }
            } else {
                adbCapture.stopCapturing()
                if (!isSaving) {
                    statusMessage = if (connectedDevice != null) "Stopped" else "No device connected"
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
                    val outputPath = videoEncoder.encodeWithTimestamp(frames, actualFps, showTimestamp = true)
                    
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
            AlertDialog(
                onDismissRequest = { 
                    showSettingsDialog = false
                    focusRequester.requestFocus()
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Buffer와 FPS (한 줄)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = tempBuffer,
                                onValueChange = { tempBuffer = it.filter { c -> c.isDigit() } },
                                label = { Text("Buffer (s)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            
                            OutlinedTextField(
                                value = tempFps,
                                onValueChange = { tempFps = it.filter { c -> c.isDigit() } },
                                label = { Text("FPS") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                supportingText = {
                                    Text(
                                        text = "1 ~ 60 fps",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            )
                        }
                        
                        // 저장 경로
                        OutlinedTextField(
                            value = tempOutputPath,
                            onValueChange = { tempOutputPath = it },
                            label = { Text("Output Directory") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                Text(
                                    text = "Directory where videos will be saved",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            tempBuffer.toIntOrNull()?.let { value ->
                                if (value in 10..600) bufferDuration = value
                            }
                            tempFps.toIntOrNull()?.let { value ->
                                if (value in 1..60) fps = value
                            }
                            // 저장 경로 설정
                            videoEncoder.setOutputDirectory(tempOutputPath)
                            showSettingsDialog = false
                            focusRequester.requestFocus()
                        }
                    ) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        tempBuffer = bufferDuration.toString()
                        tempFps = fps.toString()
                        tempOutputPath = videoEncoder.getOutputDirectory()
                        showSettingsDialog = false
                        focusRequester.requestFocus()
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && !isSaving) {
                        when {
                            // Cmd/Ctrl + Shift + S: 커스텀 저장
                            event.key == Key.S && (event.isMetaPressed || event.isCtrlPressed) && event.isShiftPressed -> {
                                if (frameBuffer.getFrameCount() > 0) {
                                    showSaveDialog = true
                                }
                                true
                            }
                            // Cmd/Ctrl + S: 30초 저장
                            event.key == Key.S && (event.isMetaPressed || event.isCtrlPressed) -> {
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
