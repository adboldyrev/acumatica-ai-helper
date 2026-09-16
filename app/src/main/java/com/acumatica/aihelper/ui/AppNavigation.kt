package com.acumatica.aihelper.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.acumatica.aihelper.data.models.NetworkStatus
import com.acumatica.aihelper.data.remote.AcumaticaRestClient
import com.acumatica.aihelper.data.repository.NetworkObserver
import com.acumatica.aihelper.data.repository.SecurityRepository
import com.acumatica.aihelper.domain.MobileAiOrchestrator
import com.acumatica.aihelper.hardware.VoiceToTextManager
import com.acumatica.aihelper.ui.screens.ChatScreen
import com.acumatica.aihelper.ui.screens.EntityConfigScreen
import com.acumatica.aihelper.ui.screens.LoginScreen

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

    if (!isAuthenticated) {
        LoginScreen(
            securityRepo = securityRepo,
            restClient = restClient,
            activity = activity,
            onLoginSuccess = { isAuthenticated = true }
        )
    } else {
        if (currentScreen == "CONFIG") {
            EntityConfigScreen(
                orchestrator = orchestrator,
                onBackToChat = { currentScreen = "CHAT" }
            )
        } else {
            ChatScreen(
                orchestrator = orchestrator,
                voiceManager = voiceManager,
                isOnline = isOnline,
                onOpenEntityConfig = { currentScreen = "CONFIG" },
            ) {
                securityRepo.clearAll()
                isAuthenticated = false
            }
        }
    }
}