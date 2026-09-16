package com.acumatica.aihelper.data.remote

import com.acumatica.aihelper.domain.models.EntitySchemaConfig
import com.acumatica.aihelper.domain.models.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class LlmNluEngine(private val httpClient: OkHttpClient = OkHttpClient()) {

    suspend fun parseUserPromptWithAi(
        userPrompt: String,
        provider: String, // "GEMINI", "CLAUDE", "OPENAI"
        apiKey: String,
        activeEntities: List<EntitySchemaConfig>
    ): ToolCall? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext fallbackLocalParse(userPrompt, activeEntities)
        }

        val availableEntityNames = activeEntities.filter { it.isEnabled }.map { it.entityName }
        if (availableEntityNames.isEmpty()) return@withContext null

        return@withContext try {
            when (provider.uppercase()) {
                "CLAUDE" -> parseWithClaude(userPrompt, apiKey, availableEntityNames)
                "OPENAI" -> parseWithOpenAi(userPrompt, apiKey, availableEntityNames)
                else -> parseWithGemini(userPrompt, apiKey, availableEntityNames)
            }
        } catch (e: Exception) {
            fallbackLocalParse(userPrompt, activeEntities)
        }
    }

    private fun parseWithGemini(userPrompt: String, apiKey: String, availableEntities: List<String>): ToolCall? {
        val systemInstructions = """
            You - AI-parcer of requests to Acumatica ERP REST API. Available entities: $1. Return ONLY valid JSON with function call: { "entityName": "EntityName", "method": "GET/PUT/POST/DELETE", "recordKey": "RecordKey or null", "payload": null }
        """.trimIndent().format(availableEntities.joinToString(", "))

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "$systemInstructions User request: $userPrompt")))))
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseStr = response.body?.string() ?: return null
            val jsonRoot = JSONObject(responseStr)
            val textCandidate = jsonRoot.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "") ?: ""

            return extractToolCallFromJsonText(textCandidate)
        }
    }

    private fun parseWithClaude(userPrompt: String, apiKey: String, availableEntities: List<String>): ToolCall? {
        val url = "https://api.anthropic.com/v1/messages"
        val systemPrompt = """
            You are an AI parser for Acumatica ERP. Available entities: $1. Return ONLY JSON: {"entityName": "...", "method": "GET/PUT/DELETE", "recordKey": "...", "payload": null}.
        """.trimIndent().format(availableEntities.joinToString(", "))

        val jsonBody = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", 500)
            put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            }))
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("anthropic-dangerous-direct-browser-access", "true")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseStr = response.body?.string() ?: return null
            val jsonRoot = JSONObject(responseStr)
            val contentArr = jsonRoot.optJSONArray("content") ?: return null
            val text = contentArr.optJSONObject(0)?.optString("text", "") ?: ""
            return extractToolCallFromJsonText(text)
        }
    }

    private fun parseWithOpenAi(userPrompt: String, apiKey: String, availableEntities: List<String>): ToolCall? {
        val url = "https://api.openai.com/v1/chat/completions"
        val systemPrompt = """
            You are an AI parser for Acumatica ERP. Available entities: $1. Return ONLY JSON: {"entityName": "...", "method": "GET/PUT/DELETE", "recordKey": "...", "payload": null}.
        """.trimIndent().format(availableEntities.joinToString(", "))

        val jsonBody = JSONObject().apply {
            put("model", "gpt-4o")
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseStr = response.body?.string() ?: return null
            val jsonRoot = JSONObject(responseStr)
            val text = jsonRoot.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: ""
            return extractToolCallFromJsonText(text)
        }
    }

    private fun extractToolCallFromJsonText(rawText: String): ToolCall? {
        val cleanJson = rawText.replace("```json", "").replace("```", "").trim()
        val jsonObj = runCatching { JSONObject(cleanJson) }.getOrNull() ?: return null

        val entityName = jsonObj.optString("entityName", null) ?: return null
        val method = jsonObj.optString("method", "GET")
        val recordKey = if (jsonObj.has("recordKey") && !jsonObj.isNull("recordKey")) jsonObj.getString("recordKey") else null
        val payload = jsonObj.optJSONObject("payload")

        return ToolCall(
            name = "${method.lowercase()}_$entityName",
            entityName = entityName,
            method = method,
            recordKey = recordKey,
            payload = payload
        )
    }

    private fun fallbackLocalParse(prompt: String, activeEntities: List<EntitySchemaConfig>): ToolCall? {
        val lower = prompt.lowercase()
        val key = Regex("[A-Z0-9]{4,20}").find(prompt)?.value

        var entityName: String? = null
        activeEntities.filter { it.isEnabled }.forEach { cfg ->
            if (lower.contains(cfg.entityName.lowercase())) {
                entityName = cfg.entityName
            }
        }

        if (entityName == null) {
            entityName = when {
                lower.contains("customer") || lower.contains("customer") -> "Customer"
                lower.contains("sales order") || lower.contains("salesorder") -> "SalesOrder"
                lower.contains("invoice") || lower.contains("invoice") || lower.contains("salesinvoice") -> "SalesInvoice"
                lower.contains("vendor") || lower.contains("vendor") -> "Vendor"
                lower.contains("purchase order") || lower.contains("purchaseorder") -> "PurchaseOrder"
                lower.contains("item") || lower.contains("stockitem") -> "StockItem"
                lower.contains("project") || lower.contains("project") -> "Project"
                lower.contains("warehouse") || lower.contains("warehouse") -> "Warehouse"
                else -> Regex("([A-Z][a-zA-Z0-9]{2,20})").find(prompt)?.value
            }
        }

        if (entityName == null) return null

        val isMutation = lower.contains("update") || lower.contains("change") || lower.contains("create") || lower.contains("modify") || lower.contains("save")
        val method = if (isMutation) "PUT" else "GET"

        val payload = if (isMutation && key != null) {
            JSONObject().apply {
                put("id", key)
                put("Description", JSONObject().put("value", "Update via AI Mobile Bot"))
            }
        } else null

        return ToolCall(
            name = "${method.lowercase()}_$entityName",
            entityName = entityName,
            method = method,
            recordKey = key,
            payload = payload
        )
    }

    fun generateNaturalLanguageResponse(
        userPrompt: String,
        entityName: String,
        jsonResult: String,
        provider: String,
        apiKey: String
    ): String {
        if (apiKey.isBlank()) {
            return "Результат Acumatica ERP ($entityName):$jsonResult"
        }

        return try {
            val systemPrompt = """
                You are an Acumatica ERP AI assistant. The user asked: '$1'. Acumatica ERP returned a JSON response of $2 for the entity '$3'. 
                Formulate a short, clear, and polite response in Russian based on this data. Briefly list the key metrics.
            """.trimIndent().format(userPrompt, jsonResult, entityName)

            val aiText = when (provider.uppercase()) {
                "CLAUDE" -> generateWithClaude(systemPrompt, apiKey)
                "OPENAI" -> generateWithOpenAi(systemPrompt, apiKey)
                else -> generateWithGemini(systemPrompt, apiKey)
            }

            if (!aiText.isNullOrBlank()) {
                "$aiText 📊 [Raw JSON ($entityName)]:$jsonResult"
            } else {
                "Acumatica ERP Result ($entityName):$jsonResult"
            }
        } catch (e: Exception) {
            "Acumatica ERP Result ($entityName):$jsonResult"
        }
    }

    private fun generateWithGemini(systemPrompt: String, apiKey: String): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))))
        }
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseStr = response.body?.string() ?: return null
            val jsonRoot = JSONObject(responseStr)
            jsonRoot.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", null)
        }
    }

    private fun generateWithClaude(systemPrompt: String, apiKey: String): String? {
        val url = "https://api.anthropic.com/v1/messages"
        val jsonBody = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", 1000)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", systemPrompt)
            }))
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("anthropic-dangerous-direct-browser-access", "true")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseStr = response.body?.string() ?: return null
            val jsonRoot = JSONObject(responseStr)
            jsonRoot.optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text", null)
        }
    }

    private fun generateWithOpenAi(systemPrompt: String, apiKey: String): String? {
        val url = "https://api.openai.com/v1/chat/completions"
        val jsonBody = JSONObject().apply {
            put("model", "gpt-4o")
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", "You are an Acumatica ERP AI assistant."))
                put(JSONObject().put("role", "user").put("content", systemPrompt))
            })
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseStr = response.body?.string() ?: return null
            val jsonRoot = JSONObject(responseStr)
            jsonRoot.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", null)
        }
    }
}