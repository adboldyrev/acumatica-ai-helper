package com.acumatica.aihelper.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.acumatica.aihelper.data.local.ChatMessageEntity
import com.acumatica.aihelper.domain.MobileAiOrchestrator
import com.acumatica.aihelper.domain.models.ToolCall
import com.acumatica.aihelper.hardware.VoiceToTextManager
import com.acumatica.aihelper.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    orchestrator: MobileAiOrchestrator,
    voiceManager: VoiceToTextManager,
    isOnline: Boolean,
    onOpenEntityConfig: () -> Unit,
    onLogout: () -> Unit,
    onOpenChecklist: () -> Unit = {}
) {
    val messages by orchestrator.messagesFlow.collectAsState(initial = emptyList())
    var inputText by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AcumaticaAIHelperTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Acumatica Assistant", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isOnline) StatusGreen else StatusOrange)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isOnline) "Online" else "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenChecklist) { Text("📋") }
                        IconButton(onClick = onOpenEntityConfig) { Text("⚙️") }
                        IconButton(onClick = { scope.launch { orchestrator.clearHistory() } }) { Text("🗑️") }
                        IconButton(onClick = onLogout) { Text("🚪") }
                    }
                )
            },
            bottomBar = {
                ChatInputArea(
                    inputText = inputText,
                    onInputChanged = { inputText = it },
                    isListening = voiceManager.isListening,
                    onVoiceClick = {
                        if (voiceManager.isListening) {
                            voiceManager.stopListening()
                        } else {
                            voiceError = null
                            voiceManager.startListening(
                                languageCode = "en-US",
                                onSpeechRecognized = { transcribedText ->
                                    inputText = transcribedText
                                    scope.launch {
                                        orchestrator.processVoicePrompt(transcribedText, isOnline, { true })
                                    }
                                },
                                onError = { voiceError = it }
                            )
                        }
                    },
                    onSendClick = {
                        val text = inputText
                        inputText = ""
                        scope.launch {
                            orchestrator.processPrompt(text, isOnline, { true })
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).background(MaterialTheme.colorScheme.background)) {
                
                AnimatedVisibility(visible = voiceManager.isListening) {
                    Box(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFE91E63).copy(alpha = 0.1f)).padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🎙️ Listening...", color = Color(0xFFE91E63), fontWeight = FontWeight.Bold)
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(messages) { msg ->
                        ChatBubble(msg)
                    }
                }

                voiceError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp))
                }
                
                // Quick Actions
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QuickAction(text = "📄 OCR", onClick = { scope.launch { orchestrator.processOcrImage(ByteArray(0), isOnline, { true }) } })
                    QuickAction(text = "📷 Barcode", onClick = { scope.launch { orchestrator.processScannedBarcode("12345", isOnline) } })
                    QuickAction(text = "📎 Photo", onClick = { scope.launch { orchestrator.processImageAttachment("Item", "KEY", ByteArray(0)) } })
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessageEntity) {
    val isUser = msg.sender == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) UserBubble else BotBubble,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 0.dp,
                bottomEnd = if (isUser) 0.dp else 16.dp
            ),
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = msg.text,
                    color = if (isUser) UserText else BotText,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (msg.isVoiceInput) {
                    Text("🎙️ Voice Input", style = MaterialTheme.typography.labelSmall, color = if (isUser) UserText.copy(alpha = 0.7f) else BotText.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun ChatInputArea(
    inputText: String,
    onInputChanged: (String) -> Unit,
    isListening: Boolean,
    onVoiceClick: () -> Unit,
    onSendClick: () -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onVoiceClick) {
                Text(if (isListening) "⏹️" else "🎙️", color = if (isListening) Color.Red else MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask something...") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )
            IconButton(onClick = onSendClick, enabled = inputText.isNotBlank()) {
                Text("➡️", color = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray)
            }
        }
    }
}

@Composable
fun QuickAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
