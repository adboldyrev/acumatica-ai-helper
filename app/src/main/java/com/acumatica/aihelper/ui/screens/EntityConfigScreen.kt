package com.acumatica.aihelper.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acumatica.aihelper.domain.MobileAiOrchestrator
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

    val filteredEntities = entityConfigs.filter {
        it.entityName.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚙️ Configure OAS Entities & Fields") },
                navigationIcon = {
                    IconButton(onClick = onBackToChat) { Text("⬅️") }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(12.dp)) {

            Button(
                onClick = {
                    scope.launch {
                        statusMessage = "Downloading swagger.json from server..."
                        val result = orchestrator.fetchAndApplySwaggerSchema()
                        statusMessage = result
                        entityConfigs.clear()
                        entityConfigs.addAll(orchestrator.configuredEntities)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔄 Update OAS")
            }

            statusMessage?.let {
                Text(it, fontSize = 12.sp, color = Color.Blue, modifier = Modifier.padding(vertical = 4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search entity...") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredEntities) { cfg ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedEntity = if (expandedEntity == cfg.entityName) null else cfg.entityName
                                }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = cfg.isEnabled,
                                        onCheckedChange = { checked ->
                                            cfg.isEnabled = checked
                                            orchestrator.updateEntityConfigs(entityConfigs)
                                        }
                                    )
                                    Text(cfg.entityName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                Text(
                                    text = if (expandedEntity == cfg.entityName) "▲ Поля (${cfg.selectedFields.size}/${cfg.availableFields.size})" else "▼ Поля (${cfg.selectedFields.size}/${cfg.availableFields.size})",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }

                            if (expandedEntity == cfg.entityName) {
                                Divider(modifier = Modifier.padding(vertical = 6.dp))
                                Text("Select fields for \$select OData:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    cfg.availableFields.forEach { field ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = cfg.selectedFields.contains(field),
                                                onCheckedChange = { checked ->
                                                    if (checked) cfg.selectedFields.add(field) else cfg.selectedFields.remove(field)
                                                    orchestrator.updateEntityConfigs(entityConfigs)
                                                }
                                            )
                                            Text(field, fontSize = 13.sp)
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
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("Save and Return to Chat")
            }
        }
    }
}