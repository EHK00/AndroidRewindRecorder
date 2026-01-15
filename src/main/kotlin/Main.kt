import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "AndroidRewindRecorder",
        state = rememberWindowState(width = 320.dp, height = 420.dp)
    ) {
        App()
    }
}
