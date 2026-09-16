package com.acumatica.aihelper.domain.models

import org.json.JSONObject

data class ToolCall(
    val name: String,
    val entityName: String,
    val method: String, // GET, PUT, POST, PATCH, DELETE
    val recordKey: String?,
    val payload: JSONObject?
)