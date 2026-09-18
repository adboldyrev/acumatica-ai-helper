package com.acumatica.aihelper.ui

import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import com.acumatica.aihelper.data.models.NetworkStatus
import com.acumatica.aihelper.data.remote.AcumaticaRestClient
import com.acumatica.aihelper.data.repository.NetworkObserver
import com.acumatica.aihelper.data.repository.SecurityRepository
import com.acumatica.aihelper.domain.MobileAiOrchestrator
import com.acumatica.aihelper.hardware.VoiceToTextManager
import com.acumatica.aihelper.ui.screens.ChatScreen
import com.acumatica.aihelper.ui.screens.ChecklistScreen
import com.acumatica.aihelper.ui.screens.EntityConfigScreen
import com.acumatica.aihelper.ui.screens.LoginScreen
import com.acumatica.aihelper.ui.theme.AcumaticaAIHelperTheme

@Composable
fun AppNavigation(
    securityRepo: SecurityRepository,
    restClient: AcumaticaRestClient,
    orchestrator: MobileAiOrchestrator,
    networkObserver: NetworkObserver,
    voiceManager: VoiceToTextManager,
    activity: FragmentActivity
) {
    var isAuthenticated by remember { mutableStateOf(securityRepo.getAccessToken() != null) }
    var currentScreen by remember { mutableStateOf("CHAT") }

    val networkStatus by networkObserver.observeStatus().collectAsState(initial = NetworkStatus.Available)
    val isOnline = networkStatus is NetworkStatus.Available

    AcumaticaAIHelperTheme {
        if (!isAuthenticated) {
            LoginScreen(
                securityRepo = securityRepo,
                restClient = restClient,
                activity = activity,
                onLoginSuccess = { isAuthenticated = true }
            )
        } else {
            when (currentScreen) {
                "CONFIG" -> EntityConfigScreen(
                    orchestrator = orchestrator,
                    onBackToChat = { currentScreen = "CHAT" }
                )
                "CHECKLIST" -> ChecklistScreen(
                    onBackToChat = { currentScreen = "CHAT" }
                )
                else -> ChatScreen(
                    orchestrator = orchestrator,
                    voiceManager = voiceManager,
                    isOnline = isOnline,
                    onOpenEntityConfig = { currentScreen = "CONFIG" },
                    onOpenChecklist = { currentScreen = "CHECKLIST" },
                    onLogout = {
                        securityRepo.clearAll()
                        isAuthenticated = false
                    }
                )
            }
        }
    }
}
