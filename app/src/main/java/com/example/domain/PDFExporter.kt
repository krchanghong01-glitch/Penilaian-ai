package com.example.domain

import android.content.Context
import android.os.Environment
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.example.data.GradeEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PDFExporter(private val context: Context) {

    /**
     * Export nilai siswa ke PDF Raport Offline
     */
    fun exportToPDF(grade: GradeEntity): File? {
        try {
            // Direktori Documents di public external storage
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val dateStr = sdf.format(Date(grade.date))
            val fileName = "Raport_${grade.studentName}_${dateStr}.pdf"
            
            val pdfFile = File(dir, fileName)
            
            // Konfigurasi iText 7.0+
            val writer = PdfWriter(pdfFile)
            val pdf = PdfDocument(writer)
            val document = Document(pdf)

            // Tulis konten
            document.add(Paragraph("KR AutoGrader Offline - Laporan Nilai"))
            document.add(Paragraph("--------------------------------------------------"))
            document.add(Paragraph("Nama Siswa : ${grade.studentName}"))
            document.add(Paragraph("Subjek     : ${grade.subject}"))
            document.add(Paragraph("Tanggal    : ${SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date(grade.date))}"))
            document.add(Paragraph("--------------------------------------------------"))
            document.add(Paragraph("Skor Akhir : ${grade.score}"))
            document.add(Paragraph("Catatan AI : ${grade.reason}"))
            document.add(Paragraph("--------------------------------------------------"))
            
            // Generate
            document.close()
            
            return pdfFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
