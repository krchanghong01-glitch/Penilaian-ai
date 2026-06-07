package com.example.domain

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OCRManager(private val context: Context) {
    
    // Inisialisasi Text Recognizer v2
    // Menggunakan TextRecognizerOptions.DEFAULT_OPTIONS (Latency rendah, akurasi tinggi, 100% on-device offline)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Memproses gambar Bitmap menjadi teks String
     */
    suspend fun processImage(bitmap: Bitmap): String {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            result.text
        } catch (e: Exception) {
            e.printStackTrace()
            "Gagal membaca teks: ${e.localizedMessage}"
        }
    }
}
