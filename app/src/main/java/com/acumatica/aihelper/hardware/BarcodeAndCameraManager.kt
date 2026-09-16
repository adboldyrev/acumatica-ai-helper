package com.acumatica.aihelper.hardware

class BarcodeAndCameraManager {
    // Симуляция распознавания штрихкода (в продакшн подключается ML Kit Barcode Scanning & CameraX)
    fun simulateBarcodeScan(rawBarcode: String): String {
        return rawBarcode.trim()
    }

    fun prepareImageForUpload(imageBytes: ByteArray, defaultFileName: String = "photo_attachment.jpg"): Pair<String, ByteArray> {
        return Pair(defaultFileName, imageBytes)
    }
}