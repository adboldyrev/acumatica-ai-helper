package com.acumatica.aihelper.data.remote

import org.json.JSONObject

class SwaggerSchemaParser {
    fun parseSwaggerJson(swaggerJsonStr: String): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        runCatching {
            val root = JSONObject(swaggerJsonStr)
            val definitions = root.optJSONObject("definitions") ?: root.optJSONObject("components")?.optJSONObject("schemas")

            if (definitions != null) {
                val keys = definitions.keys()
                while (keys.hasNext()) {
                    val entityName = keys.next()
                    val entityObj = definitions.getJSONObject(entityName)
                    val propertiesObj = entityObj.optJSONObject("properties")
                    val fieldsList = mutableListOf<String>()

                    if (propertiesObj != null) {
                        val propKeys = propertiesObj.keys()
                        while (propKeys.hasNext()) {
                            val fieldName = propKeys.next()
                            if (!fieldName.startsWith("_") && fieldName != "id" && fieldName != "rowNumber") {
                                fieldsList.add(fieldName)
                            }
                        }
                    }
                    if (fieldsList.isNotEmpty()) {
                        result[entityName] = fieldsList.sorted()
                    }
                }
            } else {
                val pathsObj = root.optJSONObject("paths")
                if (pathsObj != null) {
                    val pathKeys = pathsObj.keys()
                    while (pathKeys.hasNext()) {
                        val path = pathKeys.next()
                        val match = Regex("^/([A-Za-z0-9_]+)").find(path)
                        if (match != null) {
                            val entityName = match.groupValues[1]
                            if (!result.containsKey(entityName)) {
                                result[entityName] = listOf("ID", "Description", "Status", "Date", "CustomerClass", "Amount", "LastModified")
                            }
                        }
                    }
                }
            }
        }

        if (result.isEmpty()) {
            result["Customer"] = listOf("CustomerID", "CustomerName", "CustomerClass", "CreditLimit", "Balance", "Status")
            result["StockItem"] = listOf("InventoryID", "Description", "ItemClass", "BaseUOM", "BasePrice", "QtyOnHand")
            result["SalesOrder"] = listOf("OrderNbr", "OrderType", "CustomerID", "OrderTotal", "Status", "Date")
            result["SalesInvoice"] = listOf("ReferenceNbr", "DocType", "CustomerID", "Amount", "Status", "DueDate")
            result["Vendor"] = listOf("VendorID", "VendorName", "VendorClass", "Balance", "Status")
            result["PurchaseOrder"] = listOf("OrderNbr", "OrderType", "VendorID", "OrderTotal", "Status")
            result["Project"] = listOf("ProjectID", "Description", "CustomerID", "Status", "StartDate")
            result["Warehouse"] = listOf("WarehouseID", "Description", "Active")
        }

        return result
    }
}