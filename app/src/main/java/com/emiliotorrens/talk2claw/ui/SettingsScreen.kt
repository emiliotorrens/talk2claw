package com.emiliotorrens.talk2claw.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emiliotorrens.talk2claw.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var host by remember { mutableStateOf(settings.gatewayHost) }
    var port by remember { mutableStateOf(settings.gatewayPort.toString()) }
    var token by remember { mutableStateOf(settings.gatewayToken) }
    var gcloudKey by remember { mutableStateOf(settings.googleCloudApiKey) }
    var ttsVoice by remember { mutableStateOf(settings.ttsVoice) }
    var ttsLang by remember { mutableStateOf(settings.ttsLanguageCode) }
    var pushToTalk by remember { mutableStateOf(settings.usePushToTalk) }

    Scaffold(
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
                        onSave(settings.copy(
                            gatewayHost = host.trim(),
                            gatewayPort = port.toIntOrNull() ?: 18789,
                            gatewayToken = token.trim(),
                            googleCloudApiKey = gcloudKey.trim(),
                            ttsVoice = ttsVoice.trim(),
                            ttsLanguageCode = ttsLang.trim(),
                            usePushToTalk = pushToTalk,
                        ))
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

            HorizontalDivider()
            Text("Google Cloud TTS", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = gcloudKey,
                onValueChange = { gcloudKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = ttsVoice,
                onValueChange = { ttsVoice = it },
                label = { Text("Voice name") },
                placeholder = { Text("es-ES-Wavenet-B") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = ttsLang,
                onValueChange = { ttsLang = it },
                label = { Text("Language code") },
                placeholder = { Text("es-ES") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            HorizontalDivider()
            Text("Input", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Push to talk (mantener pulsado)")
                Switch(checked = pushToTalk, onCheckedChange = { pushToTalk = it })
            }
        }
    }
}
