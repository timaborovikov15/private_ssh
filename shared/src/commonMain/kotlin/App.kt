package tim.private_sshs

import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Фирменная палитра Termius с добавлением градиентов для эффекта стекла
val TermiusBg = Color(0xFF13121A)
val TermiusTerminalBg = Color(0xFF07060A)
val TermiusText = Color(0xFFE2E1E9)
val TermiusGreen = Color(0xFF47D185)
val TermiusAccent = Color(0xFF7B61FF)

// iOS Glassmorphism цвета (полупрозрачные подложки)
val GlassSurface = Color(026f, 025f, 038f, 0.45f) // Основное матовое стекло (альфа 0.45)
val GlassCard = Color(038f, 037f, 054f, 0.35f)    // Внутренние стеклянные карточки
val GlassBorder = Color(1f, 1f, 1f, 0.15f)        // Блик на грани стекла (iOS стиль)

enum class AppState { SPLASH, AUTH, TERMINAL }

// Криптографический менеджер AES-256
object SecurityManager {
    private val configDir = File(System.getProperty("user.home"), ".private_ssh_terminal")
    private val vaultFile = File(configDir, "vault.aes")
    private val salt = byteArrayOf(1, 9, 8, 4, 0, 5, 2, 6, 7, 3, 5, 1, 4, 8, 2, 9)
    private val iv = byteArrayOf(7, 3, 2, 5, 8, 1, 4, 9, 6, 0, 2, 5, 1, 3, 8, 4)

    private fun deriveKey(password: String): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, 65536, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    fun isPasswordSet(): Boolean = vaultFile.exists() && vaultFile.readText().isNotEmpty()

    fun saveMasterPassword(password: String) {
        try {
            if (!configDir.exists()) configDir.mkdirs()
            val secretKey = deriveKey(password)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
            val encryptedBytes = cipher.doFinal("VAULT_OPENED".toByteArray(Charsets.UTF_8))
            vaultFile.writeText(Base64.getEncoder().encodeToString(encryptedBytes))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun verifyPassword(password: String): Boolean {
        if (!vaultFile.exists()) return false
        return try {
            val secretKey = deriveKey(password)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val encryptedBytes = Base64.getDecoder().decode(vaultFile.readText().trim())
            val decryptedText = String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
            decryptedText == "VAULT_OPENED"
        } catch (_: Exception) { false }
    }

    // Жесткая проверка пароля по корпоративным стандартам безопасности
    fun validatePasswordRequirements(password: String): String? {
        if (password.length < 8) return "The password must be at least 8 characters."
        if (!password.any { it.isDigit() }) return "The password must contain at least one number."
        if (!password.any { it.isUpperCase() }) return "Add at least one capital letter (A-Z)."
        if (!password.any { it.isLowerCase() }) return "Add at least one lowercase letter (a-z)."
        if (!password.any { !it.isLetterOrDigit() }) return "Add at least one special character (@, #, $, %, !)."
        return null // Ошибок нет, пароль идеален
    }

    fun checkStrength(password: String): Pair<Float, Color> {
        if (password.isEmpty()) return 0.0f to Color.Gray
        var score = 0f
        if (password.length >= 8) score += 0.2f
        if (password.any { it.isDigit() }) score += 0.2f
        if (password.any { it.isUpperCase() }) score += 0.2f
        if (password.any { it.isLowerCase() }) score += 0.2f
        if (password.any { !it.isLetterOrDigit() }) score += 0.2f

        return when {
            score <= 0.4f -> score to Color(0xFFE05656)  // Weak (Красный)
            score <= 0.8f -> score to Color(0xFFFFB020)  // Medium (Желтый)
            else -> score to TermiusGreen                // Strong (Зеленый)
        }
    }
}

class InteractiveSshSession {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var outputStream: OutputStream? = null
    private var listenJob: Job? = null

    suspend fun connect(host: String, user: String, pass: String, port: Int = 22, onOutputReceived: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
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

            listenJob = CoroutineScope(Dispatchers.IO).launch {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream, Charsets.UTF_8))
                val ansiRegex = "\\x1B\\[[?0-9;]*[a-zA-Z]".toRegex()
                val charBuffer = CharArray(1024)
                while (channel?.isConnected == true) {
                    if (reader.ready()) {
                        val read = reader.read(charBuffer)
                        if (read > 0) {
                            val cleanText = String(charBuffer, 0, read).replace(ansiRegex, "")
                            withContext(Dispatchers.Main) { onOutputReceived(cleanText) }
                        }
                    } else { Thread.sleep(30) }
                }
            }
            return@withContext true
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onOutputReceived("Connection error: ${e.localizedMessage}\n") }
            return@withContext false
        }
    }

    fun sendCommand(command: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                outputStream?.write((command + "\n").toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (e: Exception) { e.printStackTrace() }
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
    var currentState by remember { mutableStateOf(AppState.SPLASH) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = TermiusBg,
            surface = GlassSurface,
            primary = TermiusAccent,
            outline = GlassBorder
        )
    ) {
        AnimatedContent(
            targetState = currentState,
            transitionSpec = {
                (fadeIn(animationSpec = tween(500)) + scaleIn(initialScale = 0.97f, animationSpec = tween(500))) togetherWith
                        (fadeOut(animationSpec = tween(400)) + scaleOut(targetScale = 1.03f, animationSpec = tween(400)))
            }
        ) { state ->
            when (state) {
                AppState.SPLASH -> SplashScreen(onFinished = { currentState = AppState.AUTH })
                AppState.AUTH -> AuthScreen(onAuthSuccess = { currentState = AppState.TERMINAL })
                AppState.TERMINAL -> TerminalScreen()
            }
        }
    }
}

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var startAnim by remember { mutableStateOf(true) }
    val scale by animateFloatAsState(targetValue = if (startAnim) 1f else 0.4f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    val alpha by animateFloatAsState(targetValue = if (startAnim) 1f else 0f, animationSpec = tween(900))

    LaunchedEffect(Unit) {
        delay(1800)
        onFinished()
    }

    Box(modifier = Modifier.fillMaxSize().background(TermiusBg), contentAlignment = Alignment.Center) {
        // Задний светящийся неоновый круг для имитации глубины под стеклом
        Box(modifier = Modifier.size(350.dp).graphicsLayer(alpha = 0.15f).background(Brush.radialGradient(listOf(TermiusAccent, Color.Transparent))))

        // Исправленная строка: добавляем правильный модификатор к Column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(alpha)
        ) {
            Text(text = " ", fontSize = 76.sp, modifier = Modifier.padding(bottom = 16.dp).graphicsLayer(scaleX = scale, scaleY = scale))
            Text(
                text = "PRIVATE SSH TERMINAL",
                style = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "CRYPTO SYSTEM NODE // AES-256", color = TermiusAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}


@Composable
fun AuthScreen(onAuthSuccess: () -> Unit) {
    var isPasswordVisible by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordConfirmInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    val isFirstRun by remember { mutableStateOf(!SecurityManager.isPasswordSet()) }

    val (strengthProgress, strengthColor) = SecurityManager.checkStrength(passwordInput)

    Box(
        modifier = Modifier.fillMaxSize().background(TermiusBg),
        contentAlignment = Alignment.Center
    ) {
        // Задний размытый неоновый декор под стеклянным окном (iOS эффект матовости)
        Box(modifier = Modifier.size(500.dp).graphicsLayer(alpha = 0.12f).background(Brush.radialGradient(listOf(TermiusAccent, Color.Transparent))))

        // Основное окно с эффектом матового стекла (Glassmorphic Container)
        Box(
            modifier = Modifier
                .width(440.dp)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(GlassSurface)
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                .padding(32.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (isFirstRun) "Setup Master Key" else "Unlock Station",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (isFirstRun)
                        "The password must be at least 8 characters long and contain: a number, a special character, an uppercase and lowercase letter."
                    else
                        "Workstation locked. Input symmetric AES-256 key to decrypt environment.",
                    color = TermiusText.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it; errorMessage = "" },
                    label = { Text("Master Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    // Переключаем маскировку символов в зависимости от стейта
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    // Добавляем кликабельную иконку в конец поля
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Text(
                                text = if (isPasswordVisible) "👁" else "👁‍🗨",
                                color = TermiusText.copy(alpha = 0.5f),
                                fontSize = 18.sp
                            )
                        }
                    }
                )


                if (isFirstRun && passwordInput.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Password complexity:", color = TermiusText.copy(alpha = 0.5f), fontSize = 11.sp)
                            Text(
                                text = when(strengthProgress) {
                                    0.2f -> "Extremely weak"
                                    0.4f -> "Weak"
                                    0.6f -> "Average"
                                    0.8f -> "Good"
                                    else -> "Excellent (Protected)"
                                },
                                color = strengthColor, fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { strengthProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = strengthColor,
                            trackColor = Color(1f, 1f, 1f, 0.05f)
                        )
                    }
                }

                if (isFirstRun) {
                    OutlinedTextField(
                        value = passwordConfirmInput,
                        onValueChange = { passwordConfirmInput = it; errorMessage = "" },
                        label = { Text("Confirm Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                if (errorMessage.isNotEmpty()) {
                    Text(text = errorMessage, color = Color(0xFFE05656), fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }

                Button(
                    onClick = {
                        if (isFirstRun) {
                            val validationError = SecurityManager.validatePasswordRequirements(passwordInput)
                            if (validationError != null) {
                                errorMessage = validationError
                                return@Button // ВАЖНО: останавливаем выполнение, не давая создать базу!
                            }

                            if (passwordInput != passwordConfirmInput) {
                                errorMessage = "The passwords don't match. Check the integrity.."
                                return@Button // ВАЖНО: останавливаем выполнение, если подтверждение не совпало
                            }

                            // Если дошли сюда — пароль прошел все проверки
                            SecurityManager.saveMasterPassword(passwordInput)
                            onAuthSuccess()
                        } else {
                            if (SecurityManager.verifyPassword(passwordInput)) {
                                onAuthSuccess()
                            } else {
                                errorMessage = "Decryption error. Invalid access key."
                            }
                        }
                    }
                    ,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TermiusAccent)
                ) {
                    Text(if (isFirstRun) "Generate AES Vault" else "Decrypt Terminal", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun TerminalScreen() {
    val sshSession = remember { InteractiveSshSession() }
    val coroutineScope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }

    var isConnected by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    var terminalOutput by remember { mutableStateOf("Termius-Core Terminal Client build 3.0.12\nSecure node environment initialized.\n\n") }
    var inputCommand by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { sshSession.disconnect() }
    }

    Column(modifier = Modifier.fillMaxSize().background(TermiusBg).padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(GlassCard).border(1.dp, GlassBorder, RoundedCornerShape(8.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Hosts / Active Session", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (isConnected) TermiusGreen else if (isLoading) Color.Yellow else Color.Gray))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "CONNECTED" else if (isLoading) "CONNECTING" else "DISCONNECTED",
                    color = if (isConnected) TermiusGreen else TermiusText, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Стеклянная карточка ввода данных сервера
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IP Address / Host") }, modifier = Modifier.weight(2f), singleLine = true, enabled = !isConnected && !isLoading, shape = RoundedCornerShape(8.dp))
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, modifier = Modifier.weight(1f), singleLine = true, enabled = !isConnected && !isLoading, shape = RoundedCornerShape(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1.5f), singleLine = true, enabled = !isConnected && !isLoading, shape = RoundedCornerShape(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

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
                        val success = sshSession.connect(host.trim(), user.trim(), password, 22) { newText -> terminalOutput += newText }
                        isConnected = success
                        isLoading = false
                    }
                }
            },
            enabled = !isLoading && (host.isNotEmpty() || isConnected),
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (isConnected) Color(0xFFE05656) else TermiusAccent)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(text = if (isConnected) "Disconnect Session" else "Connect via SSH", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Окно Терминала
        Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(TermiusTerminalBg).border(androidx.compose.foundation.BorderStroke(1.dp, GlassBorder), RoundedCornerShape(8.dp)).padding(16.dp)) {
            val scrollState = rememberScrollState()
            LaunchedEffect(terminalOutput) { scrollState.animateScrollTo(scrollState.maxValue) }
            Text(text = terminalOutput, color = TermiusText, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.fillMaxSize().verticalScroll(scrollState))
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Командная строка
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = inputCommand,
                onValueChange = { inputCommand = it },
                placeholder = { Text("Type a command and press Enter...") },
                modifier = Modifier.weight(1f).onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                        if (inputCommand.isNotEmpty() && isConnected) { sshSession.sendCommand(inputCommand); inputCommand = "" }
                        true
                    } else false
                },
                singleLine = true,
                enabled = isConnected,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (inputCommand.isNotEmpty()) { sshSession.sendCommand(inputCommand); inputCommand = "" } }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TermiusAccent, unfocusedBorderColor = GlassBorder)
            )
            Button(onClick = { if (inputCommand.isNotEmpty()) { sshSession.sendCommand(inputCommand); inputCommand = "" } }, enabled = isConnected && inputCommand.isNotEmpty(), shape = RoundedCornerShape(8.dp), modifier = Modifier.height(44.dp)) {
                Text("Send", fontWeight = FontWeight.Medium)
            }
        }
    }
}
