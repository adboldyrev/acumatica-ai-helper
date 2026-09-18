package com.acumatica.aihelper.data.remote

import org.json.JSONObject

class SwaggerSchemaParser {

    data class ParsedEntity(
        val fields: List<String>,
        val endpointPath: String,
        val keyField: String
    )

    fun parseSwaggerJsonExtended(swaggerJsonStr: String): Map<String, ParsedEntity> {
        val result = mutableMapOf<String, ParsedEntity>()
        runCatching {
            val root = JSONObject(swaggerJsonStr)
            
            val pathsObj = root.optJSONObject("paths")
            val pathToEntityMap = mutableMapOf<String, String>() // path -> entityName
            
            if (pathsObj != null) {
                val pathKeys = pathsObj.keys()
                while (pathKeys.hasNext()) {
                    val path = pathKeys.next()
                    val segments = path.split("/").filter { it.isNotBlank() }
                    if (segments.isNotEmpty() && !segments[0].contains("{")) {
                        val entityName = segments[0]
                        pathToEntityMap[entityName] = "/$entityName"
                    }
                }
            }

            val definitions = root.optJSONObject("definitions") ?: root.optJSONObject("components")?.optJSONObject("schemas")

            if (definitions != null) {
                val keys = definitions.keys()
                while (keys.hasNext()) {
                    val entityName = keys.next()
                    
                    // CRITICAL FIX: Only include entities that are top-level endpoints (present in 'paths')
                    // This prevents including nested sub-entities like CustomerContact that cannot be queried directly.
                    if (!pathToEntityMap.containsKey(entityName)) continue

                    // Skip internal Acumatica schemas and wrappers
                    if (entityName.endsWith("_Invoke") || entityName.endsWith("_Action") || 
                        entityName.endsWith("_Result") || entityName == "Entity" || 
                        entityName == "FileLink" || entityName == "ODataError") continue

                    val entityObj = definitions.getJSONObject(entityName)
                    
                    // Collect all properties, including those from allOf (common in OAS 3.0)
                    val allProperties = JSONObject()
                    
                    // 1. Check top-level properties
                    entityObj.optJSONObject("properties")?.let { props ->
                        val pKeys = props.keys()
                        while (pKeys.hasNext()) {
                            val k = pKeys.next()
                            allProperties.put(k, props.get(k))
                        }
                    }
                    
                    // 2. Check allOf properties
                    entityObj.optJSONArray("allOf")?.let { allOf ->
                        for (i in 0 until allOf.length()) {
                            val sub = allOf.optJSONObject(i)
                            sub?.optJSONObject("properties")?.let { props ->
                                val pKeys = props.keys()
                                while (pKeys.hasNext()) {
                                    val k = pKeys.next()
                                    allProperties.put(k, props.get(k))
                                }
                            }
                        }
                    }

                    if (allProperties.length() == 0) continue

                    // If the schema only has 'entity' and 'parameters', it's almost certainly a wrapper
                    if (allProperties.has("entity") && allProperties.has("parameters") && allProperties.length() <= 3) {
                        continue
                    }

                    val fieldsList = mutableListOf<String>()
                    var detectedKeyField = "ID"

                    val propKeys = allProperties.keys()
                    while (propKeys.hasNext()) {
                        val fieldName = propKeys.next()
                        if (!fieldName.startsWith("_") && fieldName != "id" && fieldName != "rowNumber" && 
                            fieldName != "note" && fieldName != "custom") {
                            
                            // Check if it's a "real" field (not a nested entity ref)
                            val fieldObj = allProperties.optJSONObject(fieldName)
                            val isReference = fieldObj?.has("\$ref") == true
                            val isObject = fieldObj?.optString("type") == "object"
                            
                            // In Acumatica, we mostly want the flat fields for now. 
                            // Nested objects are usually sub-entities or linked records.
                            if (!isReference && !isObject) {
                                fieldsList.add(fieldName)
                            }
                        }
                    }
                    
                    if (fieldsList.isNotEmpty()) {
                        detectedKeyField = when {
                            allProperties.has("${entityName}ID") -> "${entityName}ID"
                            allProperties.has("CustomerID") -> "CustomerID"
                            allProperties.has("InventoryID") -> "InventoryID"
                            allProperties.has("OrderNbr") -> "OrderNbr"
                            allProperties.has("ReferenceNbr") -> "ReferenceNbr"
                            allProperties.has("VendorID") -> "VendorID"
                            allProperties.has("ProjectID") -> "ProjectID"
                            allProperties.has("WarehouseID") -> "WarehouseID"
                            else -> allProperties.keys().asSequence().firstOrNull { it.contains("id", ignoreCase = true) || it.contains("nbr", ignoreCase = true) } ?: fieldsList[0]
                        }

                        val endpointPath = pathToEntityMap[entityName]!!
                        result[entityName] = ParsedEntity(
                            fields = fieldsList.sorted(),
                            endpointPath = endpointPath,
                            keyField = detectedKeyField
                        )
                    }
                }
            }
        }

        if (result.isEmpty()) {
            val fallbacks = listOf(
                Triple("Customer", "/Customer", "CustomerID"),
                Triple("StockItem", "/StockItem", "InventoryID"),
                Triple("SalesOrder", "/SalesOrder", "OrderNbr"),
                Triple("SalesInvoice", "/SalesInvoice", "ReferenceNbr"),
                Triple("Vendor", "/Vendor", "VendorID"),
                Triple("PurchaseOrder", "/PurchaseOrder", "OrderNbr"),
                Triple("Project", "/Project", "ProjectID"),
                Triple("Warehouse", "/Warehouse", "WarehouseID")
            )
            
            val fallbackFields = mapOf(
                "Customer" to listOf("CustomerID", "CustomerName", "CustomerClass", "CreditLimit", "Balance", "Status"),
                "StockItem" to listOf("InventoryID", "Description", "ItemClass", "BaseUOM", "BasePrice", "QtyOnHand"),
                "SalesOrder" to listOf("OrderNbr", "OrderType", "CustomerID", "OrderTotal", "Status", "Date"),
                "SalesInvoice" to listOf("ReferenceNbr", "DocType", "CustomerID", "Amount", "Status", "DueDate"),
                "Vendor" to listOf("VendorID", "VendorName", "VendorClass", "Balance", "Status"),
                "PurchaseOrder" to listOf("OrderNbr", "OrderType", "VendorID", "OrderTotal", "Status"),
                "Project" to listOf("ProjectID", "Description", "CustomerID", "Status", "StartDate"),
                "Warehouse" to listOf("WarehouseID", "Description", "Active")
            )

            fallbacks.forEach { (name, path, key) ->
                result[name] = ParsedEntity(
                    fields = fallbackFields[name] ?: listOf("ID"),
                    endpointPath = path,
                    keyField = key
                )
            }
        }

        return result
    }

    fun parseSwaggerJson(swaggerJsonStr: String): Map<String, List<String>> {
        return parseSwaggerJsonExtended(swaggerJsonStr).mapValues { it.value.fields }
    }
}