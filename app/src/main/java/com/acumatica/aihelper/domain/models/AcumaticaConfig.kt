package com.acumatica.aihelper.domain.models

data class AcumaticaConfig(
    val baseUrl: String,
    val clientId: String,
    val clientSecret: String,
    val username: String,
    val password: String,
    val apiVersion: String
)