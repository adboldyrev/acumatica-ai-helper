package com.acumatica.aihelper.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.acumatica.aihelper.domain.MobileAiOrchestrator
import com.acumatica.aihelper.ui.theme.AcumaticaAIHelperTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityConfigScreen(
    orchestrator: MobileAiOrchestrator,
    onBackToChat: () -> Unit
) {
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedEntity by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val entityConfigs = remember { mutableStateListOf(*orchestrator.configuredEntities.toTypedArray()) }
    val filteredEntities = entityConfigs.filter { it.entityName.contains(searchQuery, ignoreCase = true) }

    AcumaticaAIHelperTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("OpenAPI Settings", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onBackToChat) { Text("⬅️") }
                    }
                )
            }
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {

                Button(
                    onClick = {
                        scope.launch {
                            statusMessage = "Downloading OAS schema..."
                            statusMessage = orchestrator.fetchAndApplySwaggerSchema()
                            entityConfigs.clear()
                            entityConfigs.addAll(orchestrator.configuredEntities)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Refresh OpenAPI Schema")
                }

                statusMessage?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search Entities") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredEntities) { cfg ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        expandedEntity = if (expandedEntity == cfg.entityName) null else cfg.entityName
                                    }
                                ) {
                                    Checkbox(
                                        checked = cfg.isEnabled,
                                        onCheckedChange = { checked ->
                                            cfg.isEnabled = checked
                                            orchestrator.updateEntityConfigs(entityConfigs)
                                        }
                                    )
                                    Text(cfg.entityName, style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "${cfg.selectedFields.size}/${cfg.availableFields.size} Fields",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }

                                if (expandedEntity == cfg.entityName) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    Text("Select fields for OData queries:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        cfg.availableFields.forEach { field ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(
                                                    checked = cfg.selectedFields.contains(field),
                                                    onCheckedChange = { checked ->
                                                        if (checked) cfg.selectedFields.add(field) else cfg.selectedFields.remove(field)
                                                        orchestrator.updateEntityConfigs(entityConfigs)
                                                    },
                                                    modifier = Modifier.scale(0.8f)
                                                )
                                                Text(field, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        orchestrator.updateEntityConfigs(entityConfigs)
                        onBackToChat()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save & Exit")
                }
            }
        }
    }
}
