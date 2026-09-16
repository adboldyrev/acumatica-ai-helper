package com.acumatica.aihelper.data.remote

import android.util.Log
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
        provider: String,
        apiKey: String,
        allConfiguredEntities: List<EntitySchemaConfig>
    ): ToolCall? = withContext(Dispatchers.IO) {
        var targetEntityName = askAiForEntityRouteOnly(userPrompt, allConfiguredEntities, provider, apiKey)

        if (targetEntityName == null || targetEntityName.entityName == "Unknown") {
            return@withContext fallbackLocalParse(userPrompt, allConfiguredEntities)
        }
        val targetEntityConfig = allConfiguredEntities.firstOrNull {
            it.entityName.equals(targetEntityName.entityName, ignoreCase = true)
        } ?: return@withContext fallbackLocalParse(userPrompt, allConfiguredEntities)

        val narrowSystemPrompt = generateSystemPromptForSingleEntity(userPrompt, targetEntityConfig)
        return@withContext try {
            when (provider.uppercase()) {
                "CLAUDE" -> parseWithClaude(userPrompt, narrowSystemPrompt, apiKey)
                "OPENAI" -> parseWithOpenAi(userPrompt, narrowSystemPrompt, apiKey)
                else -> parseWithGemini(userPrompt, narrowSystemPrompt, apiKey)
            }
        } catch (e: Exception) {
            fallbackLocalParse(userPrompt, allConfiguredEntities)
        }
    }

    suspend fun askAiForEntityRouteOnly(
        userPrompt: String,
        allConfiguredEntities: List<EntitySchemaConfig>,
        provider: String,
        apiKey: String
    ): ToolCall? = withContext(Dispatchers.IO) {
        val promtForFindEntity = generateSystemPrompt(userPrompt, allConfiguredEntities)
        return@withContext try {
            when (provider.uppercase()) {
                "CLAUDE" -> parseWithClaude(userPrompt, promtForFindEntity, apiKey)
                "OPENAI" -> parseWithOpenAi(userPrompt, promtForFindEntity, apiKey)
                else -> parseWithGemini(userPrompt, promtForFindEntity, apiKey)
            }
        } catch (e: Exception) {
            fallbackLocalParse(userPrompt, allConfiguredEntities)
        }
    }

    fun generateSystemPrompt(userPrompt: String, configuredEntities: List<EntitySchemaConfig>): String {
        val enabledEntities = configuredEntities.filter { it.isEnabled }.map {it.entityName}.joinToString(", ")

        return """
            You are an API Router. Available entities: [$enabledEntities]. 
            Based on the User Prompt, return ONLY the exact entity name from the list. If no match, return "Unknown".

            User Prompt: 
            $userPrompt
            """.trimIndent()
    }

    fun generateSystemPromptForSingleEntity(userPrompt: String, entityConfig: EntitySchemaConfig): String {
        val singleSchemaJson = JSONObject().apply {
            put("entityName", entityConfig.entityName)
            put("endpointPath", entityConfig.endpointPath)
            put("keyField", entityConfig.keyField)
            put("availableFields", JSONArray(entityConfig.availableFields))
            put("selectedFields", JSONArray(entityConfig.selectedFields.toList()))
        }.toString(2)

        return """
    You are an expert integration assistant for Acumatica ERP v18 Enterprise REST API.
    Your task is to analyze the User Prompt and the provided Cached OAS Entity Schema to build a valid, structured REST API request specification.

    ### CONSTRAINTS & RULES:
    1. Return ONLY a valid JSON object matching the output schema described below. Do not include markdown formatting or backticks.
    2. For single record queries (GET by ID), if the user specifies a concrete identifier value (e.g. 'SO004321'), put it into the 'recordKey' field.
    3. Acumatica REST API uses the OData protocol for querying data (GET):
       - Use '${'$'}filter' for conditions (e.g. "${entityConfig.keyField} eq 'C001'"). Always use the exact field names provided in the schema.
       - Use '${'$'}select' to limit response fields based on the provided list of selectedFields.
    4. For Mutations (POST/PUT), map fields into the "payload" object.

    ### TARGET ENTITY SCHEMA METADATA & DEFINITION:
    $singleSchemaJson

    ### USER INPUT PROMPT:
    "$userPrompt"

    ### EXPECTED OUTPUT FORMAT:
    {
      "entityName": "${entityConfig.entityName}",
      "endpointPath": "${entityConfig.endpointPath}",
      "method": "GET | POST | PUT | DELETE",
      "recordKey": null,
      "queryParams": {
        "${'$'}select": "Field1,Field2",
        "${'$'}filter": "Field1 eq 'Value'",
        "${'$'}expand": null
      },
      "payload": null
    }
    """.trimIndent()
    }
    
    private fun parseWithGemini(userPrompt: String, systemPrompt: String, apiKey: String): ToolCall? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$apiKey"
        val jsonBody = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))

            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", userPrompt)
                ))
            ))

            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e("GeminiNlu", "API Error: ${response.code} - ${response.body?.string()}")
                return null
            }
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

    private fun parseWithClaude(userPromt: String, systemPrompt: String, apiKey: String): ToolCall? {
        val url = "https://api.anthropic.com/v1/messages"
        val jsonBody = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", 500)
            put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", userPromt)
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

    private fun parseWithOpenAi(userPrompt: String, systemPrompt: String, apiKey: String): ToolCall? {
        val url = "https://api.openai.com/v1/chat/completions"
        val jsonBody = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e("OpenAiNlu", "API Error: ${response.code} - ${response.body?.string()}")
                return null
            }
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

        val endpointPath = jsonObj.optString("endpointPath", "/$entityName")

        return ToolCall(
            name = "${method.lowercase()}_$entityName",
            entityName = entityName,
            endpointPath = endpointPath,
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
            payload = payload,
            endpointPath = entityName
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
            return "Acumatica ERP Result ($entityName):$jsonResult"
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