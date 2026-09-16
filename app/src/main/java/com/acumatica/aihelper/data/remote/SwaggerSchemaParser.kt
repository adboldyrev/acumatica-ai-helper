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
                    val entityObj = definitions.getJSONObject(entityName)
                    val propertiesObj = entityObj.optJSONObject("properties")
                    val fieldsList = mutableListOf<String>()
                    var detectedKeyField = "ID"

                    if (propertiesObj != null) {
                        val propKeys = propertiesObj.keys()
                        while (propKeys.hasNext()) {
                            val fieldName = propKeys.next()
                            if (!fieldName.startsWith("_") && fieldName != "id" && fieldName != "rowNumber") {
                                fieldsList.add(fieldName)
                            }
                        }
                        
                        detectedKeyField = when {
                            propertiesObj.has("${entityName}ID") -> "${entityName}ID"
                            propertiesObj.has("CustomerID") -> "CustomerID"
                            propertiesObj.has("InventoryID") -> "InventoryID"
                            propertiesObj.has("OrderNbr") -> "OrderNbr"
                            propertiesObj.has("ReferenceNbr") -> "ReferenceNbr"
                            propertiesObj.has("VendorID") -> "VendorID"
                            propertiesObj.has("ProjectID") -> "ProjectID"
                            propertiesObj.has("WarehouseID") -> "WarehouseID"
                            else -> propertiesObj.keys().asSequence().firstOrNull { it.contains("id", ignoreCase = true) || it.contains("nbr", ignoreCase = true) } ?: "ID"
                        }
                    }
                    
                    if (fieldsList.isNotEmpty()) {
                        val endpointPath = pathToEntityMap[entityName] ?: "/$entityName"
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