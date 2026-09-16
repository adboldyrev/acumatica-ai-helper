package com.acumatica.aihelper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.acumatica.aihelper.data.remote.AcumaticaRestClient
import com.acumatica.aihelper.data.repository.SecurityRepository
import com.acumatica.aihelper.domain.models.AcumaticaConfig
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    securityRepo: SecurityRepository,
    restClient: AcumaticaRestClient,
    activity: FragmentActivity,
    onLoginSuccess: () -> Unit
) {
    var baseUrl by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiVersion by remember { mutableStateOf("24.200.001") }
    var aiProvider by remember { mutableStateOf("GEMINI") }
    var aiKey by remember { mutableStateOf("") }
    //var aiKey by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Acumatica Universal AI Bot", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Acumatica URL") })
        OutlinedTextField(value = clientId, onValueChange = { clientId = it }, label = { Text("Client ID") })
        OutlinedTextField(value = clientSecret, onValueChange = { clientSecret = it }, label = { Text("Client Secret") })
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("ERP Username") })
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(value = apiVersion, onValueChange = { apiVersion = it }, label = { Text("API Version") })

        Spacer(modifier = Modifier.height(8.dp))

        Text("AI Agent (NLU Parsing):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("GEMINI", "CLAUDE", "OPENAI").forEach { provider ->
                FilterChip(
                    selected = (aiProvider == provider),
                    onClick = { aiProvider = provider },
                    label = { Text(provider) }
                )
            }
        }

        OutlinedTextField(
            value = aiKey,
            onValueChange = { aiKey = it },
            label = { Text("API Key ($aiProvider)") },
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            enabled = !isLoading,
            onClick = {
                isLoading = true
                errorMessage = null
                coroutineScope.launch {
                    try {
                        val config = AcumaticaConfig(
                            baseUrl = baseUrl,
                            clientId = clientId,
                            clientSecret = clientSecret,
                            username = username,
                            password = password,
                            apiVersion = apiVersion
                        )
                        val tokenResp = restClient.loginOAuth(config)
                        securityRepo.saveConfig(
                            config = config,
                            accessToken = tokenResp.accessToken,
                            refreshToken = tokenResp.refreshToken,
                            expiresInSeconds = tokenResp.expiresIn,
                            aiProvider = aiProvider,
                            aiKey = aiKey
                        )
                        isLoading = false
                        onLoginSuccess()
                    } catch (e: Exception) {
                        isLoading = false
                        errorMessage = "OAuth login failed: ${e.message}"
                    }
                }
            }
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
            } else {
                Text("Log in via OAuth 2.0")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(onClick = {
            securityRepo.authenticateBiometrics(
                activity = activity,
                onSuccess = { onLoginSuccess() },
                onError = { errorMessage = it }
            )
        }) {
            Text("Login with Fingerprint / Face ID")
        }

        errorMessage?.let {
            Text(it, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
        }
    }
}