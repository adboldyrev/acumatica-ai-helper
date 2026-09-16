package com.acumatica.aihelper.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.items
import com.acumatica.aihelper.domain.MobileAiOrchestrator
import com.acumatica.aihelper.domain.models.ToolCall
import com.acumatica.aihelper.hardware.VoiceToTextManager
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    orchestrator: MobileAiOrchestrator,
    voiceManager: VoiceToTextManager,
    isOnline: Boolean,
    onOpenEntityConfig: () -> Unit,
    onLogout: () -> Unit
) {
    val messages by orchestrator.messagesFlow.collectAsState(initial = emptyList())
    var inputText by remember { mutableStateOf("") }
    var pendingMutationCall by remember { mutableStateOf<ToolCall?>(null) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Universal AI Acumatica Bot") },
                actions = {
                    IconButton(onClick = onOpenEntityConfig) {
                        Text("⚙️")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            orchestrator.clearHistory()
                        }
                    }) {
                        Text("🗑️")
                    }
                    IconButton(onClick = onLogout) {
                        Text("🚪")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFFFF9800))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isOnline) "🟢 Network Available (Online)" else "🟠 Offline Mode (Queued Messages)",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            AnimatedVisibility(visible = voiceManager.isListening) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE91E63))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🎙️ Recording voice... Speak into microphone",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    val isError = msg.text.contains("❌") || msg.text.contains("not found")
                    val bgColor = when {
                        isError -> Color(0xFFFFEBEE)
                        msg.sender == "user" -> Color(0xFFE3F2FD)
                        msg.sender == "bot" -> Color(0xFFF1F8E9)
                        else -> Color(0xFFFFF3E0)
                    }

                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bgColor)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (msg.isVoiceInput) "USER 🎙️ (VOICE)" else msg.sender.uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isError) Color.Red else Color.Unspecified
                                )
                                msg.entityName?.let { entity ->
                                    Text("Entity: $entity", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(msg.text, color = if (isError) Color(0xFFB71C1C) else Color.Unspecified)

                            msg.ocrTextExtracted?.let { ocr ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text("📄 OCR Text:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFFF57F17))
                                        Text(ocr, fontSize = 11.sp, color = Color.Black)
                                    }
                                }
                            }

                            msg.barcodeScanned?.let {
                                Text("🏷️ Barcode: $it", fontSize = 11.sp, color = Color.Gray)
                            }
                            msg.attachmentUrl?.let {
                                Text("📎 Attachment: $it", fontSize = 11.sp, color = Color.Blue)
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = {
                        scope.launch {
                            orchestrator.processOcrImage(
                                imageBytes = ByteArray(200),
                                isOnline = isOnline,
                                onRequireMutationConfirmation = { toolCall ->
                                    pendingMutationCall = toolCall
                                    true
                                }
                            )
                        }
                }) {
                    Text("📄 OCR")
                }

                OutlinedButton(onClick = {
                    scope.launch {
                        orchestrator.processScannedBarcode("4710011223344", isOnline)
                    }
                }) {
                    Text("📷 Barcode")
                }

                OutlinedButton(onClick = {
                    scope.launch {
                        orchestrator.processImageAttachment("Customer", "C000000003", ByteArray(100))
                    }
                }) {
                    Text("📎 Photo")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("A random natural language query...") }
                )

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = {
                        if (voiceManager.isListening) {
                            voiceManager.stopListening()
                        } else {
                            voiceError = null
                            voiceManager.startListening(
                                languageCode = "ru-RU",
                                onSpeechRecognized = { transcribedText ->
                                    inputText = transcribedText
                                    scope.launch {
                                        orchestrator.processVoicePrompt (
                                            spokenText = transcribedText,
                                            isOnline = isOnline,
                                            onRequireMutationConfirmation = { toolCall ->
                                                pendingMutationCall = toolCall
                                                true
                                            }
                                        )
                                    }
                                },
                                onError = { voiceError = it }
                            )
                        }
                    },
                    modifier = Modifier
                        .background(if (voiceManager.isListening) Color(0xFFE91E63) else Color(0xFF2196F3), CircleShape)
                ) {
                    Text(if (voiceManager.isListening) "⏹️" else "🎙️", color = Color.White)
                }

                Spacer(modifier = Modifier.width(4.dp))

                Button(onClick = {
                    val textToSend = inputText
                    inputText = ""
                    scope.launch {
                        orchestrator.processPrompt(
                            prompt = textToSend,
                            isOnline = isOnline,
                            onRequireMutationConfirmation = { toolCall ->
                                pendingMutationCall = toolCall
                                true
                            }
                        )
                    }
                }) {
                    Text("Send")
                }
            }

            voiceError?.let {
                Text(it, color = Color.Red, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
        }
    }
}