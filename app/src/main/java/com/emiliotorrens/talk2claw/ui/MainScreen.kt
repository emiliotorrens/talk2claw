package com.emiliotorrens.talk2claw.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emiliotorrens.talk2claw.openclaw.GatewayNode
import com.emiliotorrens.talk2claw.settings.AppSettings
import com.emiliotorrens.talk2claw.voice.findPresetByVoiceName
import com.emiliotorrens.talk2claw.ui.MainViewModel.*
import kotlinx.coroutines.launch

// ── State indicator colours ───────────────────────────────────────────────────
private val ListeningColor = Color(0xFF2196F3)  // Blue
private val ThinkingColor  = Color(0xFFFF9800)  // Amber
private val SpeakingColor  = Color(0xFF4CAF50)  // Green
private val IdleColor      = Color(0xFF9E9E9E)  // Grey

// ═════════════════════════════════════════════════════════════════════════════
// MainScreen
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateSettings: () -> Unit,
) {
    val pipelineState    by viewModel.pipelineState.collectAsState()
    val conversationActive by viewModel.conversationActive.collectAsState()
    val transcript       by viewModel.transcript.collectAsState()
    val partialText      by viewModel.stt.partialText.collectAsState()
    val connectionState  by viewModel.connectionState.collectAsState()
    val statusMessage    by viewModel.statusMessage.collectAsState()
    val singleShotMode   by viewModel.singleShotMode.collectAsState()
    val appSettings      by viewModel.settings.collectAsState()
    val listState        = rememberLazyListState()
    val snackbarState    = remember { SnackbarHostState() }
    val scope            = rememberCoroutineScope()
    val view             = LocalView.current

    // Auto-scroll to bottom on new transcript entry
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    // Medium haptic when user interrupts Claw mid-speech (Speaking → Thinking)
    var prevPipeline by remember { mutableStateOf(pipelineState) }
    LaunchedEffect(pipelineState) {
        if (prevPipeline == PipelineState.Speaking && pipelineState == PipelineState.Thinking) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        prevPipeline = pipelineState
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Talk2Claw 🐾")
                        // Live connection dot
                        val connColor = when (connectionState) {
                            is GatewayNode.ConnectionState.Connected    -> Color(0xFF4CAF50)
                            is GatewayNode.ConnectionState.Error        -> Color(0xFFF44336)
                            is GatewayNode.ConnectionState.Connecting   -> Color(0xFFFF9800)
                            is GatewayNode.ConnectionState.Disconnected -> Color.Gray
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(connColor, CircleShape)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearTranscript() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Borrar")
                    }
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Config chips ───────────────────────────────────────────────
            ConfigBar(appSettings = appSettings)

            Spacer(modifier = Modifier.height(4.dp))

            // ── Transcript ─────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transcript) { entry ->
                    AnimatedTranscriptBubble(entry)
                }

                // Partial STT preview while listening
                if (partialText.isNotBlank() && pipelineState == PipelineState.Listening) {
                    item {
                        Text(
                            text = partialText,
                            fontSize = 14.sp,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Status text ────────────────────────────────────────────────
            Text(
                text = pipelineState.displayText,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Animated state indicator ───────────────────────────────────
            StateIndicatorArea(pipelineState = pipelineState)

            Spacer(modifier = Modifier.height(8.dp))

            // ── Single-shot mode badge ─────────────────────────────────────
            AnimatedVisibility(visible = singleShotMode) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        text = "Modo single-shot",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Main button (tap = start/stop, long-press = toggle mode) ───
            ConversationButton(
                isActive = conversationActive,
                pipelineState = pipelineState,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.toggleConversation()
                },
                onLongPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewModel.toggleSingleShotMode()
                    val label = if (!singleShotMode) "Modo single-shot" else "Modo conversación"
                    scope.launch {
                        snackbarState.showSnackbar(label, duration = SnackbarDuration.Short)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Config bar — model + voice summary
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun ConfigBar(appSettings: AppSettings) {
    val modelLabel = appSettings.modelAlias.replaceFirstChar { it.uppercase() }
    val voicePreset = findPresetByVoiceName(appSettings.ttsVoice)
    val voiceLabel = voicePreset.name
    val speedLabel = if (appSettings.speakingRate != 1.0f)
        " · ${appSettings.speakingRate}x" else ""
    val thinkingLabel = if (appSettings.thinkingEnabled) " · 🧠" else ""

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "$modelLabel · $voiceLabel$speedLabel$thinkingLabel",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            maxLines = 1
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// State indicator
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun StateIndicatorArea(pipelineState: PipelineState) {
    Box(
        modifier = Modifier.size(100.dp),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = pipelineState, label = "stateIndicator",
            animationSpec = tween(300)) { state ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (state) {
                    PipelineState.Idle      -> IdleIndicator()
                    PipelineState.Listening -> ListeningIndicator()
                    PipelineState.Thinking  -> ThinkingIndicator()
                    PipelineState.Speaking  -> SpeakingIndicator()
                }
            }
        }
    }
}

/** Idle — subtle breathing grey circle with mic */
@Composable
private fun IdleIndicator() {
    val transition = rememberInfiniteTransition(label = "idle")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.06f,
        animationSpec = infiniteRepeatable(tween(2600, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "breath"
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .size(76.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(IdleColor.copy(alpha = 0.14f), CircleShape)
        )
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = IdleColor,
            modifier = Modifier.size(34.dp)
        )
    }
}

/** Listening — pulsing rings expanding outward + mic */
@Composable
private fun ListeningIndicator() {
    val transition = rememberInfiniteTransition(label = "listening")
    val p1 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), "p1"
    )
    val p2 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, delayMillis = 533), RepeatMode.Restart), "p2"
    )
    val p3 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, delayMillis = 1066), RepeatMode.Restart), "p3"
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center   = Offset(size.width / 2f, size.height / 2f)
            val maxR     = size.minDimension / 2f
            val strokePx = 2.5.dp.toPx()

            for (p in listOf(p1, p2, p3)) {
                drawCircle(
                    color  = ListeningColor.copy(alpha = (1f - p) * 0.5f),
                    radius = maxR * p,
                    center = center,
                    style  = Stroke(width = strokePx)
                )
            }
            // Solid centre
            drawCircle(
                color  = ListeningColor.copy(alpha = 0.16f),
                radius = 26.dp.toPx(),
                center = center
            )
        }
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = ListeningColor,
            modifier = Modifier.size(30.dp)
        )
    }
}

/** Thinking — three bouncing dots (typing indicator) */
@Composable
private fun ThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val y1 by transition.animateFloat(
        0f, -10f,
        infiniteRepeatable(tween(520, easing = EaseInOutSine), RepeatMode.Reverse),
        "y1"
    )
    val y2 by transition.animateFloat(
        0f, -10f,
        infiniteRepeatable(tween(520, delayMillis = 170, easing = EaseInOutSine), RepeatMode.Reverse),
        "y2"
    )
    val y3 by transition.animateFloat(
        0f, -10f,
        infiniteRepeatable(tween(520, delayMillis = 340, easing = EaseInOutSine), RepeatMode.Reverse),
        "y3"
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            for (y in listOf(y1, y2, y3)) {
                Box(
                    modifier = Modifier
                        .offset(y = y.dp)
                        .size(13.dp)
                        .background(ThinkingColor, CircleShape)
                )
            }
        }
    }
}

/** Speaking — 5-bar equalizer animation */
@Composable
private fun SpeakingIndicator() {
    val transition = rememberInfiniteTransition(label = "speaking")
    val h1 by transition.animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(430, easing = EaseInOutSine), RepeatMode.Reverse), "h1"
    )
    val h2 by transition.animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(430, delayMillis = 86, easing = EaseInOutSine), RepeatMode.Reverse), "h2"
    )
    val h3 by transition.animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(430, delayMillis = 172, easing = EaseInOutSine), RepeatMode.Reverse), "h3"
    )
    val h4 by transition.animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(430, delayMillis = 258, easing = EaseInOutSine), RepeatMode.Reverse), "h4"
    )
    val h5 by transition.animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(430, delayMillis = 344, easing = EaseInOutSine), RepeatMode.Reverse), "h5"
    )

    val maxBarHeight = 46.dp
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            for (h in listOf(h1, h2, h3, h4, h5)) {
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .height(maxBarHeight * h)
                        .background(SpeakingColor, RoundedCornerShape(5.dp))
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Transcript
// ═════════════════════════════════════════════════════════════════════════════

/** Wraps TranscriptBubble with a fade-in + slide-up entrance. */
@Composable
fun AnimatedTranscriptBubble(entry: TranscriptEntry) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 3 }
    ) {
        TranscriptBubble(entry)
    }
}

@Composable
fun TranscriptBubble(entry: TranscriptEntry) {
    val isUser  = entry is TranscriptEntry.User
    val isError = entry is TranscriptEntry.Error
    val text = when (entry) {
        is TranscriptEntry.User  -> entry.text
        is TranscriptEntry.Claw  -> entry.text
        is TranscriptEntry.Error -> entry.text
    }

    val bgColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isUser  -> MaterialTheme.colorScheme.primaryContainer
        else    -> MaterialTheme.colorScheme.secondaryContainer
    }

    val hAlign: Alignment.Horizontal = when {
        isUser  -> Alignment.End
        isError -> Alignment.CenterHorizontally
        else    -> Alignment.Start
    }

    Column(
        modifier           = Modifier.fillMaxWidth(),
        horizontalAlignment = hAlign
    ) {
        Text(
            text  = when {
                isUser  -> "Tú"
                isError -> "⚠️"
                else    -> "🐾 Claw"
            },
            fontSize = 11.sp,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Surface(
            shape    = RoundedCornerShape(12.dp),
            color    = bgColor,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text     = text,
                modifier = Modifier.padding(12.dp),
                fontSize = 15.sp,
                color    = if (isError)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Conversation button
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationButton(
    isActive    : Boolean,
    pipelineState: PipelineState,
    onClick     : () -> Unit,
    onLongPress : () -> Unit = {},
) {
    val isListening = pipelineState == PipelineState.Listening

    val bgColor by animateColorAsState(
        targetValue = when {
            !isActive                              -> Color(0xFF2196F3)
            isListening                            -> Color(0xFFF44336)
            pipelineState == PipelineState.Thinking -> Color(0xFFFF9800)
            pipelineState == PipelineState.Speaking -> Color(0xFF4CAF50)
            else                                   -> Color(0xFFF44336)
        },
        label = "btnColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "btnPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = if (isListening) 1.08f else 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = EaseInOutSine), RepeatMode.Reverse),
        label         = "pulse"
    )

    if (isActive) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(pulseScale)
                .background(bgColor.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            ButtonCore(bgColor, isActive, onClick, onLongPress)
        }
    } else {
        ButtonCore(bgColor, isActive, onClick, onLongPress)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ButtonCore(
    bgColor    : Color,
    isActive   : Boolean,
    onClick    : () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .background(bgColor, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector     = if (isActive) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = if (isActive) "Detener" else "Iniciar",
            tint            = Color.White,
            modifier        = Modifier.size(48.dp)
        )
    }
}
