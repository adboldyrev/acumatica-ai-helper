package com.acumatica.aihelper.domain.models

data class EntitySchemaConfig(
    val entityName: String,
    var isEnabled: Boolean = true,
    val availableFields: List<String>,
    val selectedFields: MutableSet<String> = mutableSetOf()
)