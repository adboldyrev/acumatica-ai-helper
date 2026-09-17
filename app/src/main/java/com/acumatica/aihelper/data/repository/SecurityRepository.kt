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
        aiEndpoint: String? = null,
        aiSubscriptionKey: String? = null,
        aiModel: String? = null,
        aiMaxTokens: Int? = null
    ) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)
        prefs.edit()
            .putString("baseUrl", config.baseUrl.trimEnd('/'))
            .putString("clientId", config.clientId)
            .putString("clientSecret", config.clientSecret)
            .putString("username", config.username)
            .putString("password", config.password)
            .putString("apiVersion", config.apiVersion)
            .putString("accessToken", accessToken)
            .putString("refreshToken", refreshToken ?: "")
            .putLong("expiresAt", expiresAt)
            .apply {
                if (aiEndpoint != null) putString("aiEndpoint", aiEndpoint)
                if (aiSubscriptionKey != null) putString("aiSubscriptionKey", aiSubscriptionKey)
                if (aiModel != null) putString("aiModel", aiModel)
                if (aiMaxTokens != null) putInt("aiMaxTokens", aiMaxTokens)
            }
            .apply()
    }

    fun getAiEndpoint(): String? = prefs.getString("aiEndpoint", null)
    fun getAiSubscriptionKey(): String? = prefs.getString("aiSubscriptionKey", null)
    fun getAiModel(): String = prefs.getString("aiModel", "claude-sonnet-4-6") ?: "claude-sonnet-4-6"
    fun getAiMaxTokens(): Int = prefs.getInt("aiMaxTokens", 1000)

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
            password = prefs.getString("password", "") ?: "",
            apiVersion = prefs.getString("apiVersion", "24.200.001") ?: "24.200.001"
        )
    }

    fun getAccessToken(): String? = prefs.getString("accessToken", null)

    fun saveEntityConfigs(configs: List<EntitySchemaConfig>) {
        val jsonObj = JSONObject()
        configs.forEach { cfg ->
            val entityJson = JSONObject().apply {
                put("isEnabled", cfg.isEnabled)
                put("endpointPath", cfg.endpointPath)
                put("keyField", cfg.keyField)
                put("selectedFields", JSONArray(cfg.selectedFields.toList()))
                put("availableFields", JSONArray(cfg.availableFields))
            }
            jsonObj.put(cfg.entityName, entityJson)
        }
        prefs.edit().putString("custom_entity_configs", jsonObj.toString()).apply()
    }

    fun getEntityConfigs(fallbackAvailableSchemas: Map<String, List<String>>): List<EntitySchemaConfig> {
        val savedStr = prefs.getString("custom_entity_configs", null)
        val savedObj = if (savedStr != null) runCatching { JSONObject(savedStr) }.getOrNull() else null

        val entityNamesSource = savedObj?.keys()?.asSequence()?.toList() ?: fallbackAvailableSchemas.keys.toList()

        return entityNamesSource.map { entityName ->
            val savedEntity = savedObj?.optJSONObject(entityName)
            val isEnabled = savedEntity?.optBoolean("isEnabled", true) ?: true
            
            val endpointPath = savedEntity?.optString("endpointPath") ?: "/$entityName"
            val savedAvailableFields = mutableListOf<String>()
            
            if (savedEntity != null && savedEntity.has("availableFields")) {
                val arr = savedEntity.getJSONArray("availableFields")
                for (i in 0 until arr.length()) {
                    savedAvailableFields.add(arr.getString(i))
                }
            } else {
                savedAvailableFields.addAll(fallbackAvailableSchemas[entityName] ?: listOf("ID"))
            }

            val keyField = savedEntity?.optString("keyField") ?: when (entityName) {
                "Customer" -> "CustomerID"
                "StockItem" -> "InventoryID"
                "Vendor" -> "VendorID"
                "Project" -> "ProjectID"
                "Warehouse" -> "WarehouseID"
                "SalesOrder", "PurchaseOrder" -> "OrderNbr"
                "SalesInvoice" -> "ReferenceNbr"
                else -> if (savedAvailableFields.isNotEmpty()) savedAvailableFields[0] else "ID"
            }

            val selectedFieldsList = mutableSetOf<String>()

            if (savedEntity != null && savedEntity.has("selectedFields")) {
                val arr = savedEntity.getJSONArray("selectedFields")
                for (i in 0 until arr.length()) {
                    selectedFieldsList.add(arr.getString(i))
                }
            } else {
                selectedFieldsList.addAll(savedAvailableFields.take(10))
            }

            EntitySchemaConfig(
                entityName = entityName,
                isEnabled = isEnabled,
                availableFields = savedAvailableFields,
                selectedFields = selectedFieldsList,
                endpointPath = endpointPath,
                keyField = keyField
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