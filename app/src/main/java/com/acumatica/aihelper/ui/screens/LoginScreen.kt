package com.acumatica.aihelper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.acumatica.aihelper.data.remote.AcumaticaRestClient
import com.acumatica.aihelper.data.repository.SecurityRepository
import com.acumatica.aihelper.domain.models.AcumaticaConfig
import com.acumatica.aihelper.ui.theme.AcumaticaAIHelperTheme
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    securityRepo: SecurityRepository,
    restClient: AcumaticaRestClient,
    activity: FragmentActivity,
    onLoginSuccess: () -> Unit
) {
    var baseUrl by remember { mutableStateOf("https://30033.test-acumatica.com") }
    var clientId by remember { mutableStateOf("74D576B3-2EA7-88F1-464C-FDCC7CFB7618@Company") }
    var clientSecret by remember { mutableStateOf("AxfAEqq_dBXlmBtK-EexXA") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("123") }
    var apiVersion by remember { mutableStateOf("24.200.001") }
    
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    AcumaticaAIHelperTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Acumatica AI Assistant",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Please log in to continue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
                )

                LoginTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = "Acumatica URL")
                LoginTextField(value = clientId, onValueChange = { clientId = it }, label = "Client ID")
                LoginTextField(value = clientSecret, onValueChange = { clientSecret = it }, label = "Client Secret")
                LoginTextField(value = username, onValueChange = { username = it }, label = "ERP Username")
                LoginTextField(value = password, onValueChange = { password = it }, label = "Password", isPassword = true)
                LoginTextField(value = apiVersion, onValueChange = { apiVersion = it }, label = "API Version")

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading,
                    onClick = {
                        isLoading = true
                        errorMessage = null
                        coroutineScope.launch {
                            try {
                                val sanitizedBaseUrl = baseUrl.trim().trimEnd('/')
                                val config = AcumaticaConfig(
                                    baseUrl = sanitizedBaseUrl,
                                    clientId = clientId.trim(),
                                    clientSecret = clientSecret.trim(),
                                    username = username.trim(),
                                    password = password, // Don't trim password
                                    apiVersion = apiVersion.trim()
                                )
                                val tokenResp = restClient.loginOAuth(config)
                                
                                val llmConnection = restClient.getLlmConnection(config.baseUrl, tokenResp.accessToken, apiVersion.trim())
                                var aiEndpoint: String? = null
                                var aiSubscriptionKey: String? = null
                                var aiModel: String? = null
                                var aiMaxTokens: Int? = null
                                
                                llmConnection?.let { conn ->
                                    val params = conn.optJSONArray("Parameters")
                                    if (params != null) {
                                        for (i in 0 until params.length()) {
                                            val p = params.getJSONObject(i)
                                            val paramId = p.optJSONObject("ParameterID")?.optString("value")
                                            val paramValue = p.optJSONObject("Value")?.optString("value")
                                            when (paramId) {
                                                "target-uri" -> aiEndpoint = paramValue
                                                "Ocp-Apim-Subscription-Key" -> aiSubscriptionKey = paramValue
                                                "model" -> aiModel = paramValue
                                                "max_tokens" -> aiMaxTokens = paramValue?.toIntOrNull()
                                            }
                                        }
                                    }
                                }

                                securityRepo.saveConfig(config, tokenResp.accessToken, tokenResp.refreshToken, tokenResp.expiresIn, aiEndpoint, aiSubscriptionKey, aiModel, aiMaxTokens)
                                isLoading = false
                                onLoginSuccess()
                            } catch (e: Exception) {
                                isLoading = false
                                e.printStackTrace()
                                val message = e.message ?: "Unknown error"
                                errorMessage = if (message.contains("Unable to resolve host")) {
                                    "Network Error: DNS failure inside emulator. Please try 'Cold Boot' of the emulator or check internet connection."
                                } else {
                                    "Login failed: $message"
                                }
                            }
                        }
                    }
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Log In", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    onClick = {
                        securityRepo.authenticateBiometrics(activity, onLoginSuccess, { errorMessage = it })
                    }
                ) {
                    Text("Biometric Sign In")
                }

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}

@Composable
fun LoginTextField(value: String, onValueChange: (String) -> Unit, label: String, isPassword: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
        )
    )
}
