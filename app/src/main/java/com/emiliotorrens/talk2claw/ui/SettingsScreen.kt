package com.emiliotorrens.talk2claw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emiliotorrens.talk2claw.openclaw.GatewayNode
import com.emiliotorrens.talk2claw.settings.AppSettings
import com.emiliotorrens.talk2claw.voice.SpeakerVerification
import com.emiliotorrens.talk2claw.voice.VOICE_PRESETS
import com.emiliotorrens.talk2claw.voice.VoicePreset
import com.emiliotorrens.talk2claw.voice.findPresetByVoiceName
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    connectionState: GatewayNode.ConnectionState,
    onSave: (AppSettings) -> Unit,
    onReconnect: () -> Unit,
    onBack: () -> Unit,
    onPreviewVoice: (VoicePreset) -> Unit = {},
    onModelChanged: ((String) -> Unit)? = null,
    onThinkingChanged: ((Boolean) -> Unit)? = null,
    onEnrollSpeaker: (suspend (onPhrase: suspend (Int, Int, String) -> Unit, onRecord: suspend (Int, Int) -> Unit) -> String?)? = null,
    onClearEnrollment: (() -> Unit)? = null,
) {
    var host by remember { mutableStateOf(settings.gatewayHost) }
    var port by remember { mutableStateOf(settings.gatewayPort.toString()) }
    var token by remember { mutableStateOf(settings.gatewayToken) }
    var gcloudKey by remember { mutableStateOf(settings.googleCloudApiKey) }

    // Voice
    var selectedPreset by remember {
        mutableStateOf(findPresetByVoiceName(settings.ttsVoice))
    }
    var speakingRate by remember { mutableStateOf(settings.speakingRate) }

    // Model & thinking
    var modelAlias by remember { mutableStateOf(settings.modelAlias) }
    var thinkingEnabled by remember { mutableStateOf(settings.thinkingEnabled) }

    // Wake word
    var wakeWordEnabled by remember { mutableStateOf(settings.wakeWordEnabled) }
    var picovoiceAccessKey by remember { mutableStateOf(settings.picovoiceAccessKey) }

    // Voice match
    var voiceMatchEnabled by remember { mutableStateOf(settings.voiceMatchEnabled) }
    var voiceMatchThreshold by remember { mutableStateOf(settings.voiceMatchThreshold) }
    var enrolledEmbedding by remember { mutableStateOf(settings.enrolledEmbedding) }

    // Enrollment UI state
    var enrollmentInProgress by remember { mutableStateOf(false) }
    var enrollmentPhrase by remember { mutableStateOf(0) }      // 1-based, 0 = idle
    var enrollmentTotal by remember { mutableStateOf(3) }
    var enrollmentCurrentText by remember { mutableStateOf("") }
    var enrollmentRecording by remember { mutableStateOf(false) }  // true = recording, false = preparing
    var enrollmentError by remember { mutableStateOf<String?>(null) }

    // Interruption mode
    var interruptionMode by remember { mutableStateOf(settings.interruptionMode) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun buildSettings() = settings.copy(
        gatewayHost = host.trim(),
        gatewayPort = port.toIntOrNull() ?: 18789,
        gatewayToken = token.trim(),
        googleCloudApiKey = gcloudKey.trim(),
        ttsVoice = selectedPreset.voiceName,
        ttsLanguageCode = selectedPreset.languageCode,
        speakingRate = speakingRate,
        modelAlias = modelAlias,
        thinkingEnabled = thinkingEnabled,
        wakeWordEnabled = wakeWordEnabled,
        picovoiceAccessKey = picovoiceAccessKey.trim(),
        voiceMatchEnabled = voiceMatchEnabled,
        voiceMatchThreshold = voiceMatchThreshold,
        enrolledEmbedding = enrolledEmbedding,
        interruptionMode = interruptionMode,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        onSave(buildSettings())
                        onBack()
                    }) {
                        Text("Guardar")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Connection status card ─────────────────────────
            ConnectionStatusCard(connectionState = connectionState, onReconnect = onReconnect)

            // ── Gateway settings ───────────────────────────────
            Text("OpenClaw Gateway", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                placeholder = { Text("http://your-host.local") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Gateway Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )

            // ── Device pairing status ──────────────────────────
            if (settings.deviceToken.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF4CAF50), CircleShape)
                    )
                    Text(
                        "Dispositivo emparejado",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            HorizontalDivider()

            // ── Google Cloud TTS ───────────────────────────────
            Text("Google Cloud TTS", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = gcloudKey,
                onValueChange = { gcloudKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )

            HorizontalDivider()

            // ── Voz ───────────────────────────────────────────
            Text("Voz", style = MaterialTheme.typography.titleMedium)

            VoicePresetSelector(
                selectedPreset = selectedPreset,
                onPresetSelected = { selectedPreset = it },
                onPreview = { onPreviewVoice(it) },
            )

            SpeakingRateSlider(
                rate = speakingRate,
                onRateChanged = { speakingRate = it },
            )

            HorizontalDivider()

            // ── Interruption Mode ─────────────────────────────
            Text("Interrupción de voz", style = MaterialTheme.typography.titleMedium)

            InterruptionModeSelector(
                selectedMode = interruptionMode,
                onModeSelected = { interruptionMode = it },
            )

            HorizontalDivider()

            // ── Wake Word ─────────────────────────────────────
            Text("Wake Word", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("\"Oye Claw\" (Porcupine)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Activa la conversación por voz sin tocar. Consume batería en segundo plano.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = wakeWordEnabled,
                    onCheckedChange = { wakeWordEnabled = it },
                )
            }

            if (wakeWordEnabled) {
                OutlinedTextField(
                    value = picovoiceAccessKey,
                    onValueChange = { picovoiceAccessKey = it },
                    label = { Text("Picovoice Access Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Text("Obtener en console.picovoice.ai")
                    }
                )

                Text(
                    "⚠️ Desactiva la optimización de batería para Talk2Claw en los ajustes del sistema.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                )
            }

            HorizontalDivider()

            // ── Voice Match ───────────────────────────────────
            Text("Voice Match", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Verificación de hablante", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Solo responde a tu voz. Usa huella espectral de audio — sin modelo externo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = voiceMatchEnabled,
                    onCheckedChange = { voiceMatchEnabled = it },
                )
            }

            if (voiceMatchEnabled) {
                // Threshold slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Umbral de similitud", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${"%.2f".format(voiceMatchThreshold)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = voiceMatchThreshold,
                        onValueChange = { voiceMatchThreshold = it },
                        valueRange = 0.5f..0.90f,
                        steps = 7,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "0.50 (permisivo)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Text(
                            "0.90 (estricto)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }

                // ── Enrollment card ──────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Status row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (enrolledEmbedding.isNotBlank()) "✅" else "❌",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (enrolledEmbedding.isNotBlank())
                                        "Voz registrada" else "Sin registrar",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (enrollmentError != null) {
                                    Text(
                                        text = enrollmentError!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }

                        // Enrollment progress (shown while enrolling)
                        if (enrollmentInProgress) {
                            val progressFraction = if (enrollmentTotal > 0)
                                (enrollmentPhrase - 1).toFloat() / enrollmentTotal else 0f

                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            if (enrollmentPhrase > 0) {
                                Text(
                                    text = if (enrollmentRecording)
                                        "🎙️ Grabando frase $enrollmentPhrase de $enrollmentTotal..."
                                    else
                                        "📋 Frase $enrollmentPhrase de $enrollmentTotal — prepárate:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (enrollmentRecording)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                                if (enrollmentCurrentText.isNotBlank()) {
                                    Text(
                                        text = "\"$enrollmentCurrentText\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else {
                                Text(
                                    "Iniciando registro de voz...",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        // Action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Enroll button
                            Button(
                                onClick = {
                                    if (onEnrollSpeaker == null) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Enrollment no disponible",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                        return@Button
                                    }
                                    enrollmentInProgress = true
                                    enrollmentPhrase = 0
                                    enrollmentError = null
                                    scope.launch {
                                        val result = onEnrollSpeaker(
                                            { current, total, phrase ->
                                                enrollmentPhrase = current
                                                enrollmentTotal = total
                                                enrollmentCurrentText = phrase
                                                enrollmentRecording = false
                                            },
                                            { current, total ->
                                                enrollmentPhrase = current
                                                enrollmentTotal = total
                                                enrollmentRecording = true
                                            },
                                        )
                                        enrollmentInProgress = false
                                        enrollmentPhrase = 0
                                        enrollmentCurrentText = ""
                                        enrollmentRecording = false
                                        if (result != null) {
                                            enrolledEmbedding = result
                                            enrollmentError = null
                                            onSave(buildSettings())
                                            snackbarHostState.showSnackbar(
                                                "✅ Voz registrada correctamente",
                                                duration = SnackbarDuration.Short,
                                            )
                                        } else {
                                            enrollmentError =
                                                "Error al registrar — habla más alto e intenta de nuevo"
                                        }
                                    }
                                },
                                enabled = !enrollmentInProgress,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    if (enrolledEmbedding.isNotBlank())
                                        "Volver a registrar" else "Registrar voz",
                                )
                            }

                            // Clear button (only shown if enrolled)
                            if (enrolledEmbedding.isNotBlank() && !enrollmentInProgress) {
                                OutlinedButton(
                                    onClick = {
                                        enrolledEmbedding = ""
                                        enrollmentError = null
                                        onClearEnrollment?.invoke()
                                        onSave(buildSettings())
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Registro de voz eliminado",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                ) {
                                    Text("Borrar")
                                }
                            }
                        }

                        // Guidance text
                        if (!enrollmentInProgress) {
                            Text(
                                text = "Registra ${SpeakerVerification.ENROLLMENT_PHRASES.size} frases en voz alta. " +
                                    "Tendrás ${SpeakerVerification.PHRASE_PREP_DELAY_MS / 1000}s para prepararte antes de cada grabación.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Modelo ────────────────────────────────────────
            Text("Modelo", style = MaterialTheme.typography.titleMedium)

            ModelSelector(
                selectedAlias = modelAlias,
                onAliasSelected = { alias ->
                    modelAlias = alias
                    onModelChanged?.invoke(alias)
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val label = when (alias) {
                            "flash" -> "Flash (rápido)"
                            "sonnet" -> "Sonnet (equilibrado)"
                            "opus" -> "Opus (potente)"
                            else -> alias
                        }
                        snackbarHostState.showSnackbar(
                            "Modelo: $label",
                            duration = SnackbarDuration.Short
                        )
                    }
                },
            )

            HorizontalDivider()

            // ── Thinking mode ─────────────────────────────────
            Text("IA", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Modo Thinking", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "El modelo razona antes de responder (más lento, más preciso)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = thinkingEnabled,
                    onCheckedChange = { enabled ->
                        thinkingEnabled = enabled
                        onThinkingChanged?.invoke(enabled)
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                if (enabled) "Thinking: activado" else "Thinking: desactivado",
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                )
            }
        }
    }
}

// ── Interruption mode selector ─────────────────────────────────────────────

private data class InterruptionOption(
    val mode: String,
    val label: String,
    val description: String,
)

private val INTERRUPTION_OPTIONS = listOf(
    InterruptionOption("auto", "Automático", "Interrupción con auriculares, sin interrupción con altavoz"),
    InterruptionOption("always", "Siempre", "Siempre permitir interrumpir (comportamiento actual)"),
    InterruptionOption("never", "Nunca", "Esperar a que Claw termine antes de escuchar"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterruptionModeSelector(
    selectedMode: String,
    onModeSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = INTERRUPTION_OPTIONS.find { it.mode == selectedMode } ?: INTERRUPTION_OPTIONS[0]

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Modo de interrupción") },
            supportingText = { Text(selected.description, style = MaterialTheme.typography.labelSmall) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            singleLine = true,
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            INTERRUPTION_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    },
                    onClick = {
                        onModeSelected(option.mode)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

// ── Voice preset dropdown ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePresetSelector(
    selectedPreset: VoicePreset,
    onPresetSelected: (VoicePreset) -> Unit,
    onPreview: (VoicePreset) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selectedPreset.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Voz") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                singleLine = true,
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                supportingText = {
                    Text(
                        text = selectedPreset.tier,
                        style = MaterialTheme.typography.labelSmall,
                        color = tierColor(selectedPreset.tier),
                    )
                },
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                val tiers = listOf("Chirp3 HD", "Chirp HD", "Studio", "Neural2", "Wavenet", "Standard")
                tiers.forEach { tier ->
                    val presets = VOICE_PRESETS.filter { it.tier == tier }
                    if (presets.isNotEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = tier,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = tierColor(tier),
                                )
                            },
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(preset.name, style = MaterialTheme.typography.bodyMedium)
                                        TierBadge(tier = preset.tier)
                                    }
                                },
                                onClick = {
                                    onPresetSelected(preset)
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
            }
        }

        FilledTonalIconButton(
            onClick = { onPreview(selectedPreset) },
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Previsualizar voz")
        }
    }
}

@Composable
private fun TierBadge(tier: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = tierColor(tier).copy(alpha = 0.15f),
    ) {
        Text(
            text = tier,
            style = MaterialTheme.typography.labelSmall,
            color = tierColor(tier),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun tierColor(tier: String): Color = when (tier) {
    "Chirp3 HD" -> Color(0xFFE91E63)
    "Chirp HD"  -> Color(0xFFFF5722)
    "Studio"   -> Color(0xFF9C27B0)
    "Neural2"  -> Color(0xFF1976D2)
    "Wavenet"  -> Color(0xFF388E3C)
    else       -> Color(0xFF757575)
}

// ── Speaking rate slider ───────────────────────────────────────────────────

@Composable
private fun SpeakingRateSlider(
    rate: Float,
    onRateChanged: (Float) -> Unit,
) {
    val steps = 4
    val minRate = 0.8f
    val maxRate = 1.3f

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Velocidad de habla", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "×${"%.1f".format(rate)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = rate,
            onValueChange = { raw ->
                val snapped = (raw * 10).roundToInt() / 10f
                onRateChanged(snapped)
            },
            valueRange = minRate..maxRate,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("×0.8", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Text("Normal (×1.0)", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Text("×1.3", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

// ── Connection status card ─────────────────────────────────────────────────

@Composable
private fun ConnectionStatusCard(
    connectionState: GatewayNode.ConnectionState,
    onReconnect: () -> Unit,
) {
    val (dotColor, label, showReconnect) = when (connectionState) {
        GatewayNode.ConnectionState.Connected ->
            Triple(Color(0xFF4CAF50), "WebSocket conectado", false)
        GatewayNode.ConnectionState.Connecting ->
            Triple(Color(0xFFFF9800), "Conectando...", false)
        GatewayNode.ConnectionState.Disconnected ->
            Triple(Color.Gray, "Desconectado", true)
        is GatewayNode.ConnectionState.Error ->
            Triple(Color(0xFFF44336), "Error: ${connectionState.message}", true)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(dotColor, CircleShape)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (showReconnect) {
                TextButton(onClick = onReconnect) {
                    Text("Reconectar")
                }
            }
        }
    }
}

// ── Model selector ──────────────────────────────────────────────────

private data class ModelOption(
    val alias: String,
    val label: String,
    val description: String,
)

private val MODEL_OPTIONS = listOf(
    ModelOption("flash",  "Flash",  "Rápido — ideal para conversación de voz"),
    ModelOption("sonnet", "Sonnet", "Equilibrado — buena calidad y velocidad"),
    ModelOption("opus",   "Opus",   "Potente — mejor razonamiento, más lento"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    selectedAlias: String,
    onAliasSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = MODEL_OPTIONS.find { it.alias == selectedAlias } ?: MODEL_OPTIONS[1]

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Modelo") },
            supportingText = { Text(selected.description, style = MaterialTheme.typography.labelSmall) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            singleLine = true,
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MODEL_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    },
                    onClick = {
                        onAliasSelected(option.alias)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
