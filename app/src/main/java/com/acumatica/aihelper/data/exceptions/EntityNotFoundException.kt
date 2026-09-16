package com.acumatica.aihelper.data.exceptions

class EntityNotFoundException(val entityName: String, val recordKey: String, override val message: String) : Exception(message)