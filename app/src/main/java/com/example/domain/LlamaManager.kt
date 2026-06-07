package com.example.domain

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlamaManager(private val context: Context) {
    
    private var llmInference: LlmInference? = null
    var isModelLoaded = false
        private set

    /**
     * Memuat model GGUF dari penyimpanan lokal (Offline).
     * Instruksi Penggunaan:
     * 1. Download model Qwen2.5-0.5B-Instruct Q4.gguf dari HuggingFace.
     * 2. Simpan di direktori internal aplikasi:
     *    /Android/data/com.aistudio.autograder.offline/files/models/qwen.gguf
     * 
     * Catatan: Untuk menukar dengan Llama 3.1 8B 4.7GB, cukup ganti file
     * dan ganti path string di bawah ini sesuai lokasinya.
     */
    suspend fun loadModel(modelPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024)
                    .setTopK(40)
                    .setTemperature(0.7f)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
                isModelLoaded = true
                true
            } catch (e: Exception) {
                Log.e("LlamaManager", "Gagal load model LLM", e)
                e.printStackTrace()
                isModelLoaded = false
                false
            }
        }
    }

    /**
     * Evaluasi jawaban siswa menggunakan model LLM Offline
     * Prompt akan dirumuskan khusus untuk Guru Bahasa Indonesia
     */
    suspend fun nilaiJawaban(kunci: String, jawaban: String): String {
        return withContext(Dispatchers.IO) {
            if (llmInference == null) {
                return@withContext "Model GGUF belum dimuat. Pastikan file model ada di perangkat."
            }

            val prompt = """
                Kamu guru Indonesia.
                Kunci: $kunci.
                Jawaban: $jawaban.
                Output: Skor: X/10. Alasan: 1 kalimat
            """.trimIndent()

            try {
                val result = llmInference?.generateResponse(prompt)
                result ?: "Gagal mendapatkan respon LLM."
            } catch (e: Exception) {
                e.printStackTrace()
                "Error Inferensi: ${e.message}"
            }
        }
    }
}
