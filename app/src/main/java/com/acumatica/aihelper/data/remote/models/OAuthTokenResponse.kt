package com.acumatica.aihelper.data.remote.models

data class OAuthTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long
)