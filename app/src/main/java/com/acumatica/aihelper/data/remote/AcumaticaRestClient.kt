package com.acumatica.aihelper.data.remote

import android.util.Log
import com.acumatica.aihelper.data.exceptions.AcumaticaApiException
import com.acumatica.aihelper.data.exceptions.EntityNotFoundException
import com.acumatica.aihelper.data.remote.models.OAuthTokenResponse
import com.acumatica.aihelper.domain.models.AcumaticaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

import java.util.concurrent.TimeUnit

class AcumaticaRestClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {

    suspend fun loginOAuth(config: AcumaticaConfig): OAuthTokenResponse = withContext(Dispatchers.IO) {
        val cleanBaseUrl = config.baseUrl.trimEnd('/')
        val tokenUrl = "$cleanBaseUrl/t/company/identity/connect/token"
        Log.i("AcumaticaClient", "Login URL: $tokenUrl")
        
        val formBody = FormBody.Builder()
            .add("grant_type", "password")
            .add("client_id", config.clientId)
            .add("client_secret", config.clientSecret)
            .add("username", config.username)
            .add("password", config.password)
            .add("scope", "api offline_access")
            .build()

        val request = Request.Builder().url(tokenUrl)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("OAuth Login Error (${response.code})")
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            OAuthTokenResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token", null),
                expiresIn = json.optLong("expires_in", 3600L)
            )
        }
    }

    suspend fun refreshTokenOAuth(config: AcumaticaConfig, refreshToken: String): OAuthTokenResponse = withContext(Dispatchers.IO) {
        val tokenUrl = "${config.baseUrl}/t/company/identity/connect/token"
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", config.clientId)
            .add("client_secret", config.clientSecret)
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder().url(tokenUrl).post(formBody).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("OAuth Token Refresh Error (${response.code})")
            val json = JSONObject(response.body?.string() ?: "{}")
            OAuthTokenResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token", refreshToken),
                expiresIn = json.optLong("expires_in", 3600L)
            )
        }
    }

    suspend fun downloadSwaggerOas(baseUrl: String, endpointVersion: String = "24.200.001", accessToken: String? = null): String = withContext(Dispatchers.IO) {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val swaggerUrl = "$cleanBaseUrl/entity/Default/$endpointVersion/swagger.json"
        Log.i("AcumaticaClient", "Downloading OAS from: $swaggerUrl")
        
        val requestBuilder = Request.Builder().url(swaggerUrl).get()
        accessToken?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }
        
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("OAS Download Error (${response.code})")
            response.body?.string() ?: "{}"
        }
    }

    suspend fun getLlmConnection(baseUrl: String, accessToken: String, apiVersion: String): JSONObject? = withContext(Dispatchers.IO) {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val url = "$cleanBaseUrl/entity/LLMConnections/$apiVersion/LLMConnection?\$expand=Parameters"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val array = JSONArray(body)
            if (array.length() > 0) array.getJSONObject(0) else null
        }
    }

    suspend fun executeGet(
        baseUrl: String,
        accessToken: String,
        entityName: String,
        recordKey: String?,
        selectedFields: Set<String> = emptySet(),
        queryParams: Map<String, String?> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val mergedParams = queryParams.toMutableMap()
        
        // Merge $select from selectedFields if not already in queryParams
        if (!mergedParams.containsKey("\$select") && selectedFields.isNotEmpty()) {
            mergedParams["\$select"] = selectedFields.joinToString(",")
        } else if (!mergedParams.containsKey("\$select")) {
            mergedParams["\$select"] = "*"
        }

        // Add default $top=5 for collection queries if not provided
        if (recordKey == null && !mergedParams.containsKey("\$top")) {
            mergedParams["\$top"] = "5"
        }

        val queryString = mergedParams.entries
            .filter { it.value != null }
            .joinToString("&") { "${it.key}=${it.value}" }

        val finalUrl = when {
            queryString.isBlank() -> baseUrl
            baseUrl.contains("?") -> "$baseUrl&$queryString"
            else -> "$baseUrl?$queryString"
        }


        val request = Request.Builder()
            .url(finalUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .get()
            .build()

        Log.i("executeGet", finalUrl)

        httpClient.newCall(request).execute().use { response ->
            val responseBodyStr = response.body?.string() ?: "{}"

            if (response.code == 404) {
                throw EntityNotFoundException(
                    entityName,
                    recordKey ?: "N/A",
                    "Record '$recordKey' not found in entity '$entityName' (HTTP 404)."
                )
            }

            if (!response.isSuccessful) {
                if (responseBodyStr.contains("cannot be found") || responseBodyStr.contains("does not exist")) {
                    throw EntityNotFoundException(entityName, recordKey ?: "N/A", "Record '$recordKey' does not exist in entity '$entityName'.")
                }
                throw AcumaticaApiException(response.code, responseBodyStr, "GET Error (${response.code}): $responseBodyStr")
            }

            if (responseBodyStr.trim() == "[]") {
                throw EntityNotFoundException(entityName, recordKey ?: "N/A", "Record '$recordKey' not found in entity '$entityName' (empty result).")
            }

            if (responseBodyStr.startsWith("{")) {
                val jsonObj = JSONObject(responseBodyStr)
                if (jsonObj.has("value") && jsonObj.get("value") is JSONArray) {
                    val arr = jsonObj.getJSONArray("value")
                    if (arr.length() == 0 && recordKey != null) {
                        throw EntityNotFoundException(entityName, recordKey, "Record '$recordKey' in entity '$entityName' not found.")
                    }
                }
            }

            responseBodyStr
        }
    }

    suspend fun executeMutation(
        url: String,
        accessToken: String,
        entityName: String,
        method: String,
        recordKey: String?,
        jsonPayload: JSONObject
    ): String = withContext(Dispatchers.IO) {
        val mediaType = "application/json".toMediaType()
        val body = jsonPayload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .method(method.uppercase(), body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBodyStr = response.body?.string() ?: "{}"

            if (response.code == 404 || responseBodyStr.contains("cannot be found") || responseBodyStr.contains("does not exist")) {
                throw EntityNotFoundException(entityName, recordKey ?: "N/A", "Mutation Error: Record '$recordKey' not found in entity '$entityName'.")
            }

            if (!response.isSuccessful) {
                throw AcumaticaApiException(response.code, responseBodyStr, "Mutation Error ($method ${response.code}): $responseBodyStr")
            }

            responseBodyStr
        }
    }

    suspend fun uploadAttachment(
        baseUrl: String,
        apiVersion: String,
        accessToken: String,
        entityName: String,
        recordKey: String,
        fileName: String,
        imageBytes: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        val recordUrl = "$baseUrl/entity/Default/$apiVersion/$entityName/$recordKey?\$expand=files"
        val getRequest = Request.Builder()
            .url(recordUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        val putTemplateLink = httpClient.newCall(getRequest).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = JSONObject(response.body?.string() ?: "{}")
            json.optJSONObject("_links")?.optString("files:put", null)
        } ?: "/entity/Default/$apiVersion/files/PX.Objects.IN.InventoryItemMaint/Item/{recordKey}/$fileName"

        val uploadUrl = if (putTemplateLink.startsWith("http")) {
            putTemplateLink.replace("{filename}", fileName)
        } else {
            "$baseUrl${putTemplateLink.replace("{filename}", fileName)}"
        }

        val mediaType = "application/octet-stream".toMediaType()
        val body = imageBytes.toRequestBody(mediaType)

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("PX-CbFileComment", "Uploaded via Acumatica AI Mobile Bot")
            .put(body)
            .build()

        httpClient.newCall(uploadRequest).execute().use { response ->
            response.isSuccessful || response.code == 204
        }
    }

    suspend fun logout(baseUrl: String, accessToken: String) = withContext(Dispatchers.IO) {
        val logoutUrl = "$baseUrl/entity/auth/logout"
        val request = Request.Builder()
            .url(logoutUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .post(FormBody.Builder().build())
            .build()

        runCatching { httpClient.newCall(request).execute().close() }
    }
}