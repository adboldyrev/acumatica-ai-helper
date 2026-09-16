package com.acumatica.aihelper

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.acumatica.aihelper.data.local.AppDatabase
import com.acumatica.aihelper.data.remote.AcumaticaRestClient
import com.acumatica.aihelper.data.repository.NetworkObserver
import com.acumatica.aihelper.data.repository.SecurityRepository
import com.acumatica.aihelper.domain.MobileAiOrchestrator
import com.acumatica.aihelper.hardware.OcrTextRecognizerManager
import com.acumatica.aihelper.hardware.VoiceToTextManager
import com.acumatica.aihelper.ui.AppNavigation

class MainActivity : FragmentActivity() {
    private lateinit var db: AppDatabase
    private lateinit var securityRepo: SecurityRepository
    private lateinit var restClient: AcumaticaRestClient
    private lateinit var ocrManager: OcrTextRecognizerManager
    private lateinit var voiceManager: VoiceToTextManager
    private lateinit var networkObserver: NetworkObserver
    private lateinit var orchestrator: MobileAiOrchestrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize local storage and security repositories
        db = AppDatabase.getDatabase(this)
        securityRepo = SecurityRepository(this)
        restClient = AcumaticaRestClient()
        ocrManager = OcrTextRecognizerManager(this)
        voiceManager = VoiceToTextManager(this)
        networkObserver = NetworkObserver(this)

        // Initialize AI Orchestrator
        orchestrator = MobileAiOrchestrator(
            restClient = restClient,
            securityRepo = securityRepo,
            chatDao = db.chatDao(),
            ocrManager = ocrManager
        )

        setContent {
            AppNavigation(
                securityRepo = securityRepo,
                restClient = restClient,
                orchestrator = orchestrator,
                networkObserver = networkObserver,
                voiceManager = voiceManager,
                activity = this
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.destroy()
    }
}