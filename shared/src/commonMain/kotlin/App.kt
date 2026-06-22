package tim.private_sshs

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

val TermiusBg = Color(0xFF17161F)
val TermiusCard = Color(0xFF21202E)
val TermiusAccent = Color(0xFF7B61FF)
val TermiusTerminalBg = Color(0xFF0F0E15)
val TermiusText = Color(0xFFE2E1E9)
val TermiusGreen = Color(0xFF47D185)
val TermiusBorder = Color(0xFF2D2B3F)

class InteractiveSshSession {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var outputStream: OutputStream? = null
    private var listenJob: Job? = null

    suspend fun connect(
        host: String, user: String, pass: String, port: Int = 22,
        onOutputReceived: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsch = JSch()
            session = jsch.getSession(user, host, port)
            session?.setPassword(pass)

            val config = java.util.Properties()
            config["StrictHostKeyChecking"] = "no"
            session?.setConfig(config)

            session?.connect(10000)

            channel = session?.openChannel("shell") as ChannelShell
            val inputStream: InputStream = channel!!.inputStream
            outputStream = channel!!.outputStream

            channel?.connect()

            // Запускаем чтение через InputStreamReader, который идеально собирает UTF-8 символы
            listenJob = CoroutineScope(Dispatchers.IO).launch {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream, Charsets.UTF_8))
                val ansiRegex = "\\x1B\\[[?0-9;]*[a-zA-Z]".toRegex()

                val charBuffer = CharArray(1024)

                while (channel?.isConnected == true) {
                    if (reader.ready()) {
                        val read = reader.read(charBuffer)
                        if (read > 0) {
                            val rawText = String(charBuffer, 0, read)
                            // Очищаем от ANSI кодов управления цветом
                            val cleanText = rawText.replace(ansiRegex, "")

                            withContext(Dispatchers.Main) {
                                onOutputReceived(cleanText)
                            }
                        }
                    } else {
                        withContext(Dispatchers.IO) { Thread.sleep(20) }
                    }
                }
            }
            return@withContext true
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onOutputReceived("Connection error: ${e.localizedMessage}\n")
            }
            return@withContext false
        }
    }

    fun sendCommand(command: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                outputStream?.write((command + "\n").toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun disconnect() {
        listenJob?.cancel()
        channel?.disconnect()
        session?.disconnect()
    }
}

@Composable
fun App() {
    val sshSession = remember { InteractiveSshSession() }
    val coroutineScope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }

    var isConnected by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    var terminalOutput by remember { mutableStateOf("Termius-Core Terminal Client build 2.0.26\nReady for new connection.\n\n") }
    var inputCommand by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { sshSession.disconnect() }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = TermiusBg,
            surface = TermiusCard,
            primary = TermiusAccent,
            outline = TermiusBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TermiusBg)
                .padding(24.dp)
        ) {
            // Верхняя плашка статуса
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hosts / Active Session",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.weight(1f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (isConnected) TermiusGreen else if (isLoading) Color.Yellow else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isConnected) "CONNECTED" else if (isLoading) "CONNECTING" else "DISCONNECTED",
                        color = if (isConnected) TermiusGreen else TermiusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Блок ввода данных
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = TermiusCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, TermiusBorder)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it },
                        label = { Text("IP Address / Host") }, modifier = Modifier.weight(2f),
                        singleLine = true, enabled = !isConnected && !isLoading,
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = user, onValueChange = { user = it },
                        label = { Text("Username") }, modifier = Modifier.weight(1f),
                        singleLine = true, enabled = !isConnected && !isLoading,
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1.5f), singleLine = true, enabled = !isConnected && !isLoading,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Кнопка подключения
            Button(
                onClick = {
                    if (isConnected) {
                        sshSession.disconnect()
                        isConnected = false
                        terminalOutput += "\n[Session terminated by user]\n"
                    } else {
                        isLoading = true
                        terminalOutput = "Connecting to ${host.trim()} via SSH on port 22...\n"
                        coroutineScope.launch {
                            val success = sshSession.connect(host.trim(), user.trim(), password, 22) { newText ->
                                terminalOutput += newText
                            }
                            isConnected = success
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && (host.isNotEmpty() || isConnected),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color(0xFFE05656) else TermiusAccent
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (isConnected) "Disconnect Session" else "Connect via SSH",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Окно Терминала с автоматическим растягиванием через weight(1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TermiusTerminalBg)
                    .border(androidx.compose.foundation.BorderStroke(1.dp, TermiusBorder), RoundedCornerShape(10.dp))
                    .padding(16.dp)
            ) {
                val scrollState = rememberScrollState()
                LaunchedEffect(terminalOutput) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }

                Text(
                    text = terminalOutput,
                    color = TermiusText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Командная строка
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputCommand,
                    onValueChange = { inputCommand = it },
                    placeholder = { Text("Type a command and press Enter...") },
                    modifier = Modifier
                        .weight(1f)
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                                if (inputCommand.isNotEmpty() && isConnected) {
                                    sshSession.sendCommand(inputCommand)
                                    inputCommand = ""
                                }
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = true,
                    enabled = isConnected,
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputCommand.isNotEmpty()) {
                                sshSession.sendCommand(inputCommand)
                                inputCommand = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TermiusAccent,
                        unfocusedBorderColor = TermiusBorder
                    )
                )

                Button(
                    onClick = {
                        if (inputCommand.isNotEmpty()) {
                            sshSession.sendCommand(inputCommand)
                            inputCommand = ""
                        }
                    },
                    enabled = isConnected && inputCommand.isNotEmpty(),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Text("Send", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
