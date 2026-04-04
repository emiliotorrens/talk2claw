package com.emiliotorrens.talk2claw.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emiliotorrens.talk2claw.openclaw.OpenClawBridge
import com.emiliotorrens.talk2claw.ui.MainViewModel.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateSettings: () -> Unit,
) {
    val pipelineState by viewModel.pipelineState.collectAsState()
    val conversationActive by viewModel.conversationActive.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val partialText by viewModel.stt.partialText.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Talk2Claw 🐾") },
                actions = {
                    // Connection indicator
                    val connColor = when (connectionState) {
                        is OpenClawBridge.ConnectionState.Connected -> Color(0xFF4CAF50)
                        is OpenClawBridge.ConnectionState.Error -> Color(0xFFF44336)
                        is OpenClawBridge.ConnectionState.NotConfigured -> Color(0xFFFF9800)
                        else -> Color.Gray
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(connColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(onClick = { viewModel.clearTranscript() }) {
                        Icon(Icons.Default.DeleteSweep, "Clear")
                    }
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Transcript area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transcript) { entry ->
                    TranscriptBubble(entry)
                }

                // Show partial STT text while listening
                if (partialText.isNotBlank() && pipelineState == PipelineState.Listening) {
                    item {
                        Text(
                            text = partialText,
                            fontSize = 14.sp,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status text
            Text(
                text = pipelineState.displayText,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Conversation toggle button
            ConversationButton(
                isActive = conversationActive,
                pipelineState = pipelineState,
                onClick = { viewModel.toggleConversation() },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun ConversationButton(
    isActive: Boolean,
    pipelineState: PipelineState,
    onClick: () -> Unit,
) {
    val isListening = pipelineState == PipelineState.Listening
    val isThinking = pipelineState == PipelineState.Thinking
    val isSpeaking = pipelineState == PipelineState.Speaking

    // Animate button color
    val bgColor by animateColorAsState(
        targetValue = when {
            !isActive -> Color(0xFF2196F3)       // Blue — idle, tap to start
            isListening -> Color(0xFFF44336)      // Red — listening
            isThinking -> Color(0xFFFF9800)       // Orange — processing
            isSpeaking -> Color(0xFF4CAF50)       // Green — speaking
            else -> Color(0xFFF44336)
        },
        label = "buttonColor"
    )

    // Pulse animation while listening
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Outer glow ring when active
    if (isActive) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(pulseScale)
                .background(bgColor.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            ConversationButtonInner(bgColor, isActive, onClick)
        }
    } else {
        ConversationButtonInner(bgColor, isActive, onClick)
    }
}

@Composable
private fun ConversationButtonInner(bgColor: Color, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .background(bgColor, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isActive) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = if (isActive) "Stop conversation" else "Start conversation",
            tint = Color.White,
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
fun TranscriptBubble(entry: TranscriptEntry) {
    val isUser = entry is TranscriptEntry.User
    val isError = entry is TranscriptEntry.Error
    val text = when (entry) {
        is TranscriptEntry.User -> entry.text
        is TranscriptEntry.Claw -> entry.text
        is TranscriptEntry.Error -> entry.text
    }

    val bgColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Text(
            text = if (isUser) "Tú" else if (isError) "⚠️" else "🐾 Claw",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bgColor,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(12.dp),
                fontSize = 15.sp
            )
        }
    }
}
