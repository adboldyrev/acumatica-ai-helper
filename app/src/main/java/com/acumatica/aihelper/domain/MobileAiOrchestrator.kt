package com.acumatica.aihelper.domain

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.acumatica.aihelper.data.exceptions.AcumaticaApiException
import com.acumatica.aihelper.data.exceptions.EntityNotFoundException
import com.acumatica.aihelper.data.local.ChatDao
import com.acumatica.aihelper.data.local.ChatMessageEntity
import com.acumatica.aihelper.data.remote.AcumaticaRestClient
import com.acumatica.aihelper.data.remote.LlmNluEngine
import com.acumatica.aihelper.data.remote.SwaggerSchemaParser
import com.acumatica.aihelper.data.repository.SecurityRepository
import com.acumatica.aihelper.domain.models.AcumaticaConfig
import com.acumatica.aihelper.domain.models.EntitySchemaConfig
import com.acumatica.aihelper.domain.models.ToolCall
import com.acumatica.aihelper.hardware.OcrTextRecognizerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MobileAiOrchestrator(
    private val restClient: AcumaticaRestClient,
    private val securityRepo: SecurityRepository,
    private val chatDao: ChatDao,
    private val ocrManager: OcrTextRecognizerManager,
    private val swaggerParser: SwaggerSchemaParser = SwaggerSchemaParser(),
    private val llmNluEngine: LlmNluEngine = LlmNluEngine(securityRepo)
) {
    private suspend fun ensureValidToken(config: AcumaticaConfig): String? {
        if (!securityRepo.isTokenExpired()) {
            return securityRepo.getAccessToken()
        }
        val refreshToken = securityRepo.getRefreshToken()
        return if (!refreshToken.isNullOrBlank()) {
            runCatching {
                val tokenResp = restClient.refreshTokenOAuth(config, refreshToken)
                securityRepo.updateTokens(tokenResp.accessToken, tokenResp.refreshToken, tokenResp.expiresIn)
                tokenResp.accessToken
            }.getOrElse {
                runCatching {
                    val tokenResp = restClient.loginOAuth(config)
                    securityRepo.updateTokens(tokenResp.accessToken, tokenResp.refreshToken, tokenResp.expiresIn)
                    tokenResp.accessToken
                }.getOrNull()
            }
        } else {
            runCatching {
                val tokenResp = restClient.loginOAuth(config)
                securityRepo.updateTokens(tokenResp.accessToken, tokenResp.refreshToken, tokenResp.expiresIn)
                tokenResp.accessToken
            }.getOrNull()
        }
    }

    val messagesFlow: Flow<List<ChatMessageEntity>> = chatDao.getAllMessagesFlow()
    var configuredEntities by mutableStateOf<List<EntitySchemaConfig>>(emptyList())
        private set

    init {
        reloadEntityConfigs()
    }

    fun reloadEntityConfigs() {
        val fallbackSchemas = mapOf(
            "Customer" to listOf("CustomerID", "CustomerName", "CustomerClass", "CreditLimit", "Balance", "Status"),
            "StockItem" to listOf("InventoryID", "Description", "ItemClass", "BaseUOM", "BasePrice", "QtyOnHand"),
            "SalesOrder" to listOf("OrderNbr", "OrderType", "CustomerID", "OrderTotal", "Status", "Date"),
            "SalesInvoice" to listOf("ReferenceNbr", "DocType", "CustomerID", "Amount", "Status", "DueDate"),
            "Vendor" to listOf("VendorID", "VendorName", "VendorClass", "Balance", "Status"),
            "PurchaseOrder" to listOf("OrderNbr", "OrderType", "VendorID", "OrderTotal", "Status")
        )
        configuredEntities = securityRepo.getEntityConfigs(fallbackSchemas)
    }

    suspend fun fetchAndApplySwaggerSchema(): String = withContext(Dispatchers.IO) {
        val config = securityRepo.getConfig() ?: return@withContext "Error: Please log in first."
        val token = securityRepo.getAccessToken()
        try {
            val swaggerJsonStr = restClient.downloadSwaggerOas(config.baseUrl, config.apiVersion, token)
            
            val extendedSchemas = swaggerParser.parseSwaggerJsonExtended(swaggerJsonStr)
            
            val baseSchemas = extendedSchemas.mapValues { it.value.fields }
            val loadedConfigs = securityRepo.getEntityConfigs(baseSchemas)
            
            val enrichedConfigs = loadedConfigs.map { cfg ->
                val ext = extendedSchemas[cfg.entityName]
                if (ext != null) {
                    cfg.copy(endpointPath = ext.endpointPath, keyField = ext.keyField)
                } else cfg
            }
            
            configuredEntities = enrichedConfigs
            securityRepo.saveEntityConfigs(enrichedConfigs)
            "Successfully loaded entities from OAS: ${enrichedConfigs.size}"
        } catch (e: Exception) {
            "OAS Update Error: ${e.message}"
        }
    }

    fun updateEntityConfigs(newConfigs: List<EntitySchemaConfig>) {
        configuredEntities = newConfigs
        securityRepo.saveEntityConfigs(newConfigs)
    }

    suspend fun clearHistory() { chatDao.clearHistory() }

    suspend fun processOcrImage(
        imageBytes: ByteArray,
        isOnline: Boolean,
        onRequireMutationConfirmation: suspend (ToolCall) -> Boolean
    ): String = withContext(Dispatchers.IO) {
        val extractedText = ocrManager.recognizeTextFromImage(imageBytes)

        chatDao.insertMessage(
            ChatMessageEntity(
                sender = "user",
                text = "📄 Document photo uploaded for OCR",
                ocrTextExtracted = extractedText,
                isPendingOffline = !isOnline
            )
        )

        val ocrPrompt = "Find data in Acumatica ERP based on text: $extractedText"

        if (!isOnline) {
            val msg = "⚠️ OCR request saved locally. Request will execute when back online."
            chatDao.insertMessage(ChatMessageEntity(sender = "bot", text = msg))
            return@withContext msg
        }

        return@withContext executeRemotePrompt(ocrPrompt, onRequireMutationConfirmation)
    }

    suspend fun processVoicePrompt(
        spokenText: String,
        isOnline: Boolean,
        onRequireMutationConfirmation: suspend (ToolCall) -> Boolean
    ): String = withContext(Dispatchers.IO) {
        chatDao.insertMessage(
            ChatMessageEntity(
                sender = "user",
                text = "🎙️ $spokenText",
                isVoiceInput = true,
                isPendingOffline = !isOnline
            )
        )

        if (!isOnline) {
            val msg = "⚠️ Voice request saved locally in offline queue."
            chatDao.insertMessage(ChatMessageEntity(sender = "bot", text = msg))
            return@withContext msg
        }

        return@withContext executeRemotePrompt(spokenText, onRequireMutationConfirmation)
    }

    suspend fun processScannedBarcode(barcode: String, isOnline: Boolean): String = withContext(Dispatchers.IO) {
        val promptText = "Show data for barcode: $barcode"
        chatDao.insertMessage(
            ChatMessageEntity(
                sender = "user",
                text = promptText,
                barcodeScanned = barcode,
                isPendingOffline = !isOnline
            )
        )

        if (!isOnline) {
            val msg = "📷 Barcode $barcode saved in offline queue."
            chatDao.insertMessage(ChatMessageEntity(sender = "bot", text = msg))
            return@withContext msg
        }

        val config = securityRepo.getConfig() ?: return@withContext "Error: No configuration found."
        val token = securityRepo.getAccessToken() ?: return@withContext "Error: No access token found."

        return@withContext try {
            val barcodeUrl = "${config.baseUrl}/entity/Default/${config.apiVersion}/StockItem/$barcode"
            val resultJson = restClient.executeGet(barcodeUrl, token, "StockItem", barcode)
            val answer = "Barcode search result for $barcode: $resultJson"
            chatDao.insertMessage(
                ChatMessageEntity(
                    sender = "bot",
                    text = answer,
                    recordKey = barcode
                )
            )
            answer
        } catch (e: EntityNotFoundException) {
            val errorMsg = "❌ Error: Item with barcode '$barcode' not found in Acumatica ERP."
            chatDao.insertMessage(
                ChatMessageEntity(
                    sender = "bot",
                    text = errorMsg,
                    recordKey = barcode
                )
            )
            errorMsg
        } catch (e: Exception) {
            val errorMsg = "❌ Request execution error: ${e.message}"
            chatDao.insertMessage(ChatMessageEntity(sender = "bot", text = errorMsg))
            errorMsg
        }
    }

    suspend fun processImageAttachment(
        entityName: String,
        recordKey: String,
        imageBytes: ByteArray,
        fileName: String = "inspection_photo.jpg"
    ): String = withContext(Dispatchers.IO) {
        val config = securityRepo.getConfig() ?: return@withContext "Error: No configuration found."
        val token = securityRepo.getAccessToken() ?: return@withContext "Error: No access token found."

        val success = restClient.uploadAttachment(
            baseUrl = config.baseUrl,
            apiVersion = config.apiVersion,
            accessToken = token,
            entityName = entityName,
            recordKey = recordKey,
            fileName = fileName,
            imageBytes = imageBytes
        )

        val resultMsg = if (success) {
            "✅ Photo $fileName successfully attached to $entityName ($recordKey)."
        } else {
            "❌ Error: Record '$recordKey' in entity '$entityName' not found or unable to attach photo."
        }

        chatDao.insertMessage(
            ChatMessageEntity(
                sender = "bot",
                text = resultMsg,
                entityName = entityName,
                recordKey = recordKey,
                attachmentUrl = fileName
            )
        )
        return@withContext resultMsg
    }

    suspend fun processPrompt(
        prompt: String,
        isOnline: Boolean,
        onRequireMutationConfirmation: suspend (ToolCall) -> Boolean
    ): String = withContext(Dispatchers.IO) {
        chatDao.insertMessage(
            ChatMessageEntity(
                sender = "user",
                text = prompt,
                isPendingOffline = !isOnline
            )
        )

        if (!isOnline) {
            val offlineNotice = "⚠️ Device is offline. Request saved locally."
            chatDao.insertMessage(ChatMessageEntity(sender = "bot", text = offlineNotice))
            return@withContext offlineNotice
        }

        return@withContext executeRemotePrompt(prompt, onRequireMutationConfirmation)
    }

    private suspend fun executeRemotePrompt(
        prompt: String,
        onRequireMutationConfirmation: suspend (ToolCall) -> Boolean
    ): String {
        val config = securityRepo.getConfig() ?: return "Error: No configuration found."

        val toolCall = llmNluEngine.parseUserPromptWithAi(
            userPrompt = prompt,
            allConfiguredEntities = configuredEntities
        ) ?: run {
            val responseText = "AI could not determine intent or Acumatica ERP entity. Ensure the required entity is enabled in OpenAPI Schema settings."
            chatDao.insertMessage(ChatMessageEntity(sender = "bot", text = responseText))
            return responseText
        }

        val isMutation = toolCall.method.uppercase() in listOf("PUT", "POST", "PATCH", "DELETE")
        if (isMutation) {
            val confirmed = onRequireMutationConfirmation(toolCall)
            if (!confirmed) {
                val cancelMsg = "Operation ${toolCall.method} for ${toolCall.entityName} canceled by user."
                chatDao.insertMessage(
                    ChatMessageEntity(
                        sender = "bot",
                        text = cancelMsg,
                        entityName = toolCall.entityName,
                        recordKey = toolCall.recordKey
                    )
                )
                return cancelMsg
            }
        }

        val entityConfig = configuredEntities.find { it.entityName.equals(toolCall.entityName, ignoreCase = true) }
        val selectedFields = entityConfig?.selectedFields ?: emptySet()

        val normalizedPath = toolCall.endpointPath?.trimStart('/') ?: toolCall.entityName

        val requestUrl = if (!toolCall.recordKey.isNullOrBlank()) {
            "${config.baseUrl}/entity/Default/${config.apiVersion}/$normalizedPath/${toolCall.recordKey}"
        } else {
            "${config.baseUrl}/entity/Default/${config.apiVersion}/$normalizedPath"
        }

        return try {
            val token = ensureValidToken(config) ?: return "Error: Authentication failed."
            val rawResult: String = if (isMutation) {
                restClient.executeMutation(
                    url = requestUrl,
                    accessToken = token,
                    entityName = toolCall.entityName,
                    method = toolCall.method,
                    recordKey = toolCall.recordKey,
                    jsonPayload = toolCall.payload ?: JSONObject()
                )
            } else {
                restClient.executeGet(
                    baseUrl = requestUrl,
                    accessToken = token,
                    entityName = toolCall.entityName,
                    recordKey = toolCall.recordKey,
                    selectedFields = selectedFields,
                    queryParams = toolCall.queryParams ?: emptyMap()
                )
            }

            val finalJsonResult: String = if (isMutation && toolCall.recordKey != null) {
                runCatching {
                    restClient.executeGet(
                        baseUrl = requestUrl, 
                        accessToken = token, 
                        entityName = toolCall.entityName, 
                        recordKey = toolCall.recordKey, 
                        selectedFields = selectedFields,
                        queryParams = toolCall.queryParams ?: emptyMap()
                    )
                }.getOrDefault(rawResult)
            } else {
                rawResult
            }

            val naturalAnswer = llmNluEngine.generateNaturalLanguageResponse(
                userPrompt = prompt,
                entityName = toolCall.entityName,
                jsonResult = finalJsonResult
            )
            val formattedAnswer = "🤖 Acumatica AI (${toolCall.entityName}): $naturalAnswer"
            chatDao.insertMessage(
                ChatMessageEntity(
                    sender = "bot",
                    text = formattedAnswer,
                    entityName = toolCall.entityName,
                    recordKey = toolCall.recordKey
                )
            )
            formattedAnswer
        } catch (e: EntityNotFoundException) {
            val notFoundError = "❌ Error: Record '${e.recordKey}' in entity '${e.entityName}' not found in Acumatica ERP."
            chatDao.insertMessage(
                ChatMessageEntity(
                    sender = "bot",
                    text = notFoundError,
                    entityName = e.entityName,
                    recordKey = e.recordKey
                )
            )
            notFoundError
        } catch (e: AcumaticaApiException) {
            val errorMsg = if (e.statusCode >= 500) {
                "I couldn't process your request - please rephrase it."
            } else {
                "Acumatica ERP Error(${e.statusCode}): ${e.message}"
            }
            chatDao.insertMessage(
                ChatMessageEntity(
                    sender = "bot",
                    text = errorMsg,
                    entityName = toolCall.entityName,
                    recordKey = toolCall.recordKey
                )
            )
            errorMsg
        } catch (e: Exception) {
            val genericError = "❌ Acumatica ERP Error: ${e.message}"
            chatDao.insertMessage(
                ChatMessageEntity(
                    sender = "bot",
                    text = genericError,
                    entityName = toolCall.entityName,
                    recordKey = toolCall.recordKey
                )
            )
            genericError
        }
    }
}