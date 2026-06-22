package tim.private_sshs

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SSH Terminal Client"
    ) {
        App() // Вызов интерфейса из модуля shared
    }
}
