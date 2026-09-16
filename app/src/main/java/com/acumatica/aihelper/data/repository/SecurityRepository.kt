package com.acumatica.aihelper.data.repository

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.acumatica.aihelper.domain.models.AcumaticaConfig
import com.acumatica.aihelper.domain.models.EntitySchemaConfig
import org.json.JSONArray
import org.json.JSONObject

class SecurityRepository(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "acumatica_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveConfig(
        config: AcumaticaConfig,
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
        aiProvider: String,
        aiKey: String
    ) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)
        prefs.edit()
            .putString("baseUrl", config.baseUrl.trimEnd('/'))
            .putString("clientId", config.clientId)
            .putString("clientSecret", config.clientSecret)
            .putString("username", config.username)
            .putString("password", config.password)
            .putString("accessToken", accessToken)
            .putString("refreshToken", refreshToken ?: "")
            .putLong("expiresAt", expiresAt)
            .putString("aiProvider", aiProvider)
            .putString("aiKey", aiKey)
            .apply()
    }

    fun updateTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)
        val edit = prefs.edit()
            .putString("accessToken", accessToken)
            .putLong("expiresAt", expiresAt)
        if (!refreshToken.isNullOrBlank()) {
            edit.putString("refreshToken", refreshToken)
        }
        edit.apply()
    }

    fun getRefreshToken(): String? = prefs.getString("refreshToken", null)
    fun getExpiresAt(): Long = prefs.getLong("expiresAt", 0L)
    fun isTokenExpired(): Boolean = System.currentTimeMillis() >= (getExpiresAt() - 60000L)

    fun getConfig(): AcumaticaConfig? {
        val url = prefs.getString("baseUrl", null) ?: return null
        return AcumaticaConfig(
            baseUrl = url,
            clientId = prefs.getString("clientId", "") ?: "",
            clientSecret = prefs.getString("clientSecret", "") ?: "",
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: ""
        )
    }

    fun getAccessToken(): String? = prefs.getString("accessToken", null)
    fun getAiProvider(): String = prefs.getString("aiProvider", "GEMINI") ?: "GEMINI"
    fun getAiKey(): String = prefs.getString("aiKey", "") ?: ""

    fun saveEntityConfigs(configs: List<EntitySchemaConfig>) {
        val jsonObj = JSONObject()
        configs.forEach { cfg ->
            val entityJson = JSONObject().apply {
                put("isEnabled", cfg.isEnabled)
                put("selectedFields", JSONArray(cfg.selectedFields.toList()))
            }
            jsonObj.put(cfg.entityName, entityJson)
        }
        prefs.edit().putString("custom_entity_configs", jsonObj.toString()).apply()
    }

    fun getEntityConfigs(fallbackAvailableSchemas: Map<String, List<String>>): List<EntitySchemaConfig> {
        val savedStr = prefs.getString("custom_entity_configs", null)
        val savedObj = if (savedStr != null) runCatching { JSONObject(savedStr) }.getOrNull() else null

        return fallbackAvailableSchemas.map { (entityName, fields) ->
            val savedEntity = savedObj?.optJSONObject(entityName)
            val isEnabled = savedEntity?.optBoolean("isEnabled", true) ?: true
            val selectedFieldsList = mutableSetOf<String>()

            if (savedEntity != null && savedEntity.has("selectedFields")) {
                val arr = savedEntity.getJSONArray("selectedFields")
                for (i in 0 until arr.length()) {
                    selectedFieldsList.add(arr.getString(i))
                }
            } else {
                selectedFieldsList.addAll(fields.take(10))
            }

            EntitySchemaConfig(
                entityName = entityName,
                isEnabled = isEnabled,
                availableFields = fields,
                selectedFields = selectedFieldsList
            )
        }
    }

    fun clearAll() { prefs.edit().clear().apply() }

    fun authenticateBiometrics(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Acumatica AI Bot Login")
            .setSubtitle("Confirm your identity with biometrics")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}