package com.acumatica.aihelper.hardware

class BarcodeAndCameraManager {
    fun simulateBarcodeScan(rawBarcode: String): String {
        return rawBarcode.trim()
    }

    fun prepareImageForUpload(imageBytes: ByteArray, defaultFileName: String = "photo_attachment.jpg"): Pair<String, ByteArray> {
        return Pair(defaultFileName, imageBytes)
    }
}