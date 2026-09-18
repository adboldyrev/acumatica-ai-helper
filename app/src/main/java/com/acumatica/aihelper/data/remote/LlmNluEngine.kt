package com.acumatica.aihelper.data.remote

import android.util.Log
import com.acumatica.aihelper.data.repository.SecurityRepository
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

class LlmNluEngine(
    private val securityRepo: SecurityRepository,
    private val httpClient: OkHttpClient = OkHttpClient()
) {

    suspend fun parseUserPromptWithAi(
        userPrompt: String,
        allConfiguredEntities: List<EntitySchemaConfig>
    ): ToolCall? = withContext(Dispatchers.IO) {
        val targetEntityName = askAiForEntityRouteOnly(userPrompt, allConfiguredEntities)

        if (targetEntityName == null || targetEntityName.entityName == "Unknown") {
            return@withContext fallbackLocalParse(userPrompt, allConfiguredEntities)
        }
        val targetEntityConfig = allConfiguredEntities.firstOrNull {
            it.entityName.equals(targetEntityName.entityName, ignoreCase = true)
        } ?: return@withContext fallbackLocalParse(userPrompt, allConfiguredEntities)

        val narrowSystemPrompt = generateSystemPromptForSingleEntity(targetEntityConfig)
        return@withContext try {
            parseWithAcumaticaLlmProxy(userPrompt, narrowSystemPrompt)
        } catch (e: Exception) {
            Log.e("parseUserPromptWithAi", "Error: ${e.message}")
            fallbackLocalParse(userPrompt, allConfiguredEntities)
        }
    }

    suspend fun askAiForEntityRouteOnly(
        userPrompt: String,
        allConfiguredEntities: List<EntitySchemaConfig>
    ): ToolCall? = withContext(Dispatchers.IO) {
        val promptForFindEntity = generateSystemPrompt(allConfiguredEntities)
        Log.i("askAiForEntityRouteOnly", promptForFindEntity)
        return@withContext try {
            parseWithAcumaticaLlmProxy(userPrompt, promptForFindEntity)
        } catch (e: Exception) {
            fallbackLocalParse(userPrompt, allConfiguredEntities)
        }
    }

    fun generateSystemPrompt(configuredEntities: List<EntitySchemaConfig>): String {
        val enabledEntities = configuredEntities.filter { it.isEnabled }.map { it.entityName }.joinToString(", ")

        return """
            You are an API Router for Acumatica ERP. Available entities: [$enabledEntities]. 
            Based on the User Prompt, return a JSON object with the key "entityName".
            Example: {"entityName": "Customer"}
            If no match is found, return {"entityName": "Unknown"}.
            """.trimIndent()
    }

    fun generateSystemPromptForSingleEntity(entityConfig: EntitySchemaConfig): String {
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

    private fun parseWithAcumaticaLlmProxy(
        userPrompt: String,
        systemPrompt: String
    ): ToolCall? {
        val aiEndpoint = securityRepo.getAiEndpoint() ?: return null
        val aiSubscriptionKey = securityRepo.getAiSubscriptionKey() ?: ""
        val aiModel = securityRepo.getAiModel()
        val aiMaxTokens = securityRepo.getAiMaxTokens()

        val jsonBody = JSONObject().apply {
            put("model", aiModel)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
            put("max_tokens", aiMaxTokens)
        }

        val request = Request.Builder()
            .url(aiEndpoint)
            .addHeader("Ocp-Apim-Subscription-Key", aiSubscriptionKey)
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseStr = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                Log.e("AcumaticaProxyNlu", "API Error: ${response.code} - $responseStr")
                return null
            } else {
                Log.i("AcumaticaProxyNlu", "API Success: ${response.code} - $responseStr")
            }

            val jsonRoot = JSONObject(responseStr)
            // Handle both Anthropic and OpenAI/Azure response formats
            val text = if (jsonRoot.has("content") && jsonRoot.get("content") is JSONArray) {
                // Anthropic format
                val contentArr = jsonRoot.getJSONArray("content")
                if (contentArr.length() > 0) {
                    contentArr.getJSONObject(0).optString("text", "")
                } else ""
            } else {
                // OpenAI/Azure format
                jsonRoot.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "") ?: ""
            }
            return extractToolCallFromJsonText(text)
        }
    }

    private fun extractToolCallFromJsonText(rawText: String): ToolCall? {
        val cleanJson = rawText.replace("```json", "").replace("```", "").trim()
        val jsonObj = runCatching { JSONObject(cleanJson) }.getOrNull() 
            ?: return if (cleanJson.length in 3..30 && !cleanJson.contains("{")) {
                // If it's just a plain entity name string
                ToolCall(
                    name = "get_$cleanJson",
                    entityName = cleanJson,
                    method = "GET",
                    recordKey = null,
                    payload = null,
                    endpointPath = cleanJson
                )
            } else null

        val entityName = jsonObj.optString("entityName", null) ?: return null
        val method = jsonObj.optString("method", "GET")
        val recordKey = if (jsonObj.has("recordKey") && !jsonObj.isNull("recordKey")) jsonObj.getString("recordKey") else null
        val payload = jsonObj.optJSONObject("payload")
        
        val queryParams = mutableMapOf<String, String?>()
        jsonObj.optJSONObject("queryParams")?.let { qp ->
            val keys = qp.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!qp.isNull(k)) {
                    queryParams[k] = qp.optString(k)
                }
            }
        }

        val endpointPath = jsonObj.optString("endpointPath", entityName).trimStart('/')

        return ToolCall(
            name = "${method.lowercase()}_$entityName",
            entityName = entityName,
            endpointPath = endpointPath,
            method = method,
            recordKey = recordKey,
            payload = payload,
            queryParams = if (queryParams.isNotEmpty()) queryParams else null
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
        jsonResult: String
    ): String {
        val aiSubscriptionKey = securityRepo.getAiSubscriptionKey()
        if (aiSubscriptionKey.isNullOrBlank()) {
            return "Acumatica ERP Result ($entityName):$jsonResult"
        }

        return try {
            val systemPrompt = """
                You are an Acumatica ERP AI assistant. The user asked: '$userPrompt'. Acumatica ERP returned a JSON response of $jsonResult for the entity '$entityName'. 
                Formulate a short, clear, and polite response in Russian based on this data. Briefly list the key metrics.
            """.trimIndent()

            val aiText = generateWithAcumaticaLlmProxy(systemPrompt)

            if (!aiText.isNullOrBlank()) {
                "$aiText 📊 [Raw JSON ($entityName)]:$jsonResult"
            } else {
                "Acumatica ERP Result ($entityName):$jsonResult"
            }
        } catch (e: Exception) {
            "Acumatica ERP Result ($entityName):$jsonResult"
        }
    }

    private fun generateWithAcumaticaLlmProxy(
        systemPrompt: String
    ): String? {
        val aiEndpoint = securityRepo.getAiEndpoint() ?: return null
        val aiSubscriptionKey = securityRepo.getAiSubscriptionKey() ?: ""
        val aiModel = securityRepo.getAiModel()
        val aiMaxTokens = securityRepo.getAiMaxTokens()

        val jsonBody = JSONObject().apply {
            put("model", aiModel)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", "Generate natural language summary for the provided ERP data."))
            })
            put("max_tokens", aiMaxTokens)
        }

        val request = Request.Builder()
            .url(aiEndpoint)
            .addHeader("Ocp-Apim-Subscription-Key", aiSubscriptionKey)
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val responseStr = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                Log.e("AcumaticaProxyNL", "API Error: ${response.code} - $responseStr")
                return null
            } else {
                Log.i("AcumaticaProxyNL", "API Success: ${response.code} - $responseStr")
            }

            val jsonRoot = JSONObject(responseStr)
            if (jsonRoot.has("content") && jsonRoot.get("content") is JSONArray) {
                // Anthropic format
                val contentArr = jsonRoot.getJSONArray("content")
                if (contentArr.length() > 0) {
                    contentArr.getJSONObject(0).optString("text", null)
                } else null
            } else {
                // OpenAI/Azure format
                jsonRoot.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", null)
            }
        }
    }
}
