package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextStyle
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.GradeEntity
import com.example.domain.LlamaManager
import com.example.domain.OCRManager
import com.example.domain.PDFExporter
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(
    private val database: AppDatabase,
    private val ocrManager: OCRManager,
    private val llamaManager: LlamaManager,
    private val pdfExporter: PDFExporter
) : ViewModel() {

    private val _dbGrades = MutableStateFlow<List<GradeEntity>>(emptyList())
    val dbGrades: StateFlow<List<GradeEntity>> = _dbGrades

    var isModelLoaded by mutableStateOf(false)
        private set

    var isProcessing by mutableStateOf(false)
        private set
        
    var lastGradeResult by mutableStateOf<GradeEntity?>(null)
        private set

    init {
        viewModelScope.launch {
            database.gradeDao().getAllGrades().collect { list ->
                _dbGrades.value = list
            }
        }
    }

    fun loadModel(modelPath: String, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch {
            isProcessing = true
            val success = llamaManager.loadModel(modelPath)
            isModelLoaded = success
            isProcessing = false
            onFinished(success)
        }
    }

    fun processAnswer(bitmap: Bitmap, studentName: String, subject: String, answerKey: String) {
        viewModelScope.launch {
            isProcessing = true
            
            // 1. OCR (Ekstrak Teks dari Gambar menggunakan ML Kit)
            val extractedText = ocrManager.processImage(bitmap)
            
            // 2. Evaluasi menggunakan GGUF lokal via MediaPipe LlmInference
            val resultText = llamaManager.nilaiJawaban(kunci = answerKey, jawaban = extractedText)
            
            // Parse result untuk mendapatkan skor dan alasan (regex / parsial parsing)
            // Asumsi LLM output: "Skor: 8/10. Alasan: Blabla"
            var score = "N/A"
            var reason = resultText
            
            val scoreRegex = "Skor:\\s*([^\\.]+)\\.".toRegex()
            val match = scoreRegex.find(resultText)
            if (match != null) {
                score = match.groupValues[1].trim()
                reason = resultText.replace(match.value, "").replace("Alasan:", "").trim()
            }
            
            // 3. Simpan ke Database
            val grade = GradeEntity(
                studentName = studentName,
                subject = subject,
                score = score,
                reason = reason,
                date = System.currentTimeMillis()
            )
            database.gradeDao().insertGrade(grade)
            lastGradeResult = grade
            
            isProcessing = false
        }
    }

    fun exportPdf(grade: GradeEntity, context: android.content.Context) {
        viewModelScope.launch {
            val file = pdfExporter.exportToPDF(grade)
            if (file != null) {
                Toast.makeText(context, "PDF Disimpan: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Gagal export PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class MainViewModelFactory(
    private val database: AppDatabase,
    private val ocrManager: OCRManager,
    private val llamaManager: LlamaManager,
    private val pdfExporter: PDFExporter
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(database, ocrManager, llamaManager, pdfExporter) as T
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val db = AppDatabase.getDatabase(this)
        val ocr = OCRManager(this)
        val llama = LlamaManager(this)
        val exporter = PDFExporter(this)
        val factory = MainViewModelFactory(db, ocr, llama, exporter)
        
        setContent {
            MyApplicationTheme {
                val viewModel: MainViewModel = viewModels<MainViewModel> { factory }.value
                AppContent(viewModel)
            }
        }
    }
}

@Composable
fun AppContent(viewModel: MainViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    
    Scaffold(
        bottomBar = {
            BentoNavBar(selectedTab) { selectedTab = it }
        },
        containerColor = CyberpunkBackground
    ) { innerPadding ->
        Box(modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()
            .background(CyberpunkBackground)) {
            if (selectedTab == 0) {
                ScanScreen(viewModel)
            } else {
                HistoryScreen(viewModel)
            }
        }
    }
}

@Composable
fun AppHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                 drawLine(
                     color = CyberpunkNeonPurple.copy(alpha = 0.2f),
                     start = Offset(0f, size.height),
                     end = Offset(size.width, size.height),
                     strokeWidth = 1.dp.toPx()
                 )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            Text("SYSTEM STATUS: OPTIMAL", color = CyberpunkCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            Row {
                Text("KR AUTO", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = (-1).sp)
                Text("GRADER", color = CyberpunkNeonPurple, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = (-1).sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
            Box(Modifier.size(8.dp).background(CyberpunkCyan, CircleShape).shadow(8.dp, spotColor = CyberpunkCyan, shape = CircleShape))
            Box(Modifier.size(8.dp).background(Color.DarkGray, CircleShape))
        }
    }
}

@Composable
fun BentoNavBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { onTabSelected(0) },
            modifier = Modifier.weight(1f).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if(selectedTab==0) Color(0xFF1E293B) else Color(0xFF1E293B).copy(alpha=0.5f)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Scan", tint = if(selectedTab==0) CyberpunkCyan else Color.Gray)
        }
        
        Box(modifier = Modifier
            .size(64.dp)
            .background(Color(0xFF8B5CF6), CircleShape)
            .border(2.dp, CyberpunkCyan, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(56.dp).background(Color(0xFF0A0A0A), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(48.dp).background(CyberpunkNeonPurple, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }

        Button(
            onClick = { onTabSelected(1) },
            modifier = Modifier.weight(1f).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if(selectedTab==1) Color(0xFF1E293B) else Color(0xFF1E293B).copy(alpha=0.5f)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.History, contentDescription = "History", tint = if(selectedTab==1) CyberpunkNeonPurple else Color.Gray)
        }
    }
}

@Composable
fun BentoTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace) },
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Color(0xFF0F1219),
            focusedContainerColor = Color(0xFF0F1219),
            unfocusedBorderColor = Color(0xFF333333),
            focusedBorderColor = CyberpunkCyan,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.LightGray
        ),
        textStyle = TextStyle(fontSize = 12.sp)
    )
}

@Composable
fun ScanScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var studentName by remember { mutableStateOf("Budi") }
    var subject by remember { mutableStateOf("Bahasa Indonesia") }
    var answerKey by remember { mutableStateOf("Ibukota Indonesia adalah Jakarta.") }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        if (!viewModel.isModelLoaded) {
            val modelDir = File(context.getExternalFilesDir(null), "models")
            if (!modelDir.exists()) modelDir.mkdirs()
            val ggufFile = File(modelDir, "qwen.gguf")
            if (ggufFile.exists()) {
                 viewModel.loadModel(ggufFile.absolutePath) { success ->
                     if(!success) Toast.makeText(context, "Gagal memuat Model!", Toast.LENGTH_SHORT).show()
                 }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader()
        
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Camera Bento Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0F172A))
                    .border(2.dp, CyberpunkNeonPurple.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
            ) {
                if (hasCameraPermission) {
                    val cameraController = remember { LifecycleCameraController(context) }
                    cameraController.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                    
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> PreviewView(ctx).apply { controller = cameraController } }
                    )

                    // Overlays
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.TopStart)
                            .background(Color(0xFF0A0A0A).copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                            .border(1.dp, CyberpunkCyan.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(6.dp).background(Color.Red, CircleShape))
                        Text("CAMERA_X FEED", color = CyberpunkCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }

                    Box(
                        modifier = Modifier
                            .size(200.dp, 260.dp)
                            .align(Alignment.Center)
                            .drawBehind {
                                drawRoundRect(
                                    color = CyberpunkCyan.copy(alpha = 0.3f),
                                    style = Stroke(
                                        width = 2.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                    ),
                                    cornerRadius = CornerRadius(12.dp.toPx())
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ALIGMENT BOX", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text("PLACE ANSWER SHEET HERE", color = Color.DarkGray, fontSize = 8.sp)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.BottomEnd)
                            .background(Color(0xFF0A0A0A).copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                            .border(1.dp, CyberpunkNeonPurple.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text("ML Kit OCR", color = CyberpunkNeonPurple, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("PROBABILITY: 98.4%", color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }

                    CornerAccents()

                    Button(
                        onClick = {
                            cameraController.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bitmap = imageProxyToBitmap(image)
                                        viewModel.processAnswer(bitmap, studentName, subject, answerKey)
                                        image.close()
                                    }
                                    override fun onError(exception: ImageCaptureException) {
                                        Toast.makeText(context, "Gagal menangkap gambar", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberpunkCyan, contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (viewModel.isProcessing) "Memproses..." else "SCAN & NILAI", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Izin Kamera Dibutuhkan", color = CyberpunkRed)
                    }
                }
            }

            // Bento Grid for Inputs
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                    .border(1.dp, CyberpunkNeonPurple.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BentoTextField(value = studentName, onValueChange = { studentName = it }, label = "SISWA", modifier = Modifier.weight(1f))
                    BentoTextField(value = subject, onValueChange = { subject = it }, label = "MAPEL", modifier = Modifier.weight(1f))
                }
                BentoTextField(value = answerKey, onValueChange = { answerKey = it }, label = "KUNCI JAWABAN", modifier = Modifier.fillMaxWidth())
            }

            if (viewModel.lastGradeResult != null) {
                BentoStatsGrid(viewModel.lastGradeResult!!)
            }
        }
    }
}

@Composable
fun CornerAccents() {
    val stroke = 2.dp
    val cornerSize = 32.dp
    val color = CyberpunkCyan
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawLine(color, Offset(0f, 0f), Offset(cornerSize.toPx(), 0f), stroke.toPx())
        drawLine(color, Offset(0f, 0f), Offset(0f, cornerSize.toPx()), stroke.toPx())
        drawLine(color, Offset(size.width, 0f), Offset(size.width - cornerSize.toPx(), 0f), stroke.toPx())
        drawLine(color, Offset(size.width, 0f), Offset(size.width, cornerSize.toPx()), stroke.toPx())
        drawLine(color, Offset(0f, size.height), Offset(cornerSize.toPx(), size.height), stroke.toPx())
        drawLine(color, Offset(0f, size.height), Offset(0f, size.height - cornerSize.toPx()), stroke.toPx())
        drawLine(color, Offset(size.width, size.height), Offset(size.width - cornerSize.toPx(), size.height), stroke.toPx())
        drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - cornerSize.toPx()), stroke.toPx())
    }
}

@Composable
fun BentoStatsGrid(grade: GradeEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                .border(1.dp, CyberpunkNeonPurple.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("LATEST GRADE", color = CyberpunkNeonPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(grade.score, fontWeight = FontWeight.Black, color = Color.White, fontSize = 32.sp, lineHeight = 32.sp)
                    Text("/10", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text("\"${grade.reason}\"", color = Color.Gray, fontSize = 10.sp, fontStyle = FontStyle.Italic, maxLines = 2)
            }
            Text("Siswa: ${grade.studentName}", color = Color.DarkGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(16.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(36.dp).background(CyberpunkNeonPurple.copy(0.1f), RoundedCornerShape(10.dp)).border(1.dp, CyberpunkNeonPurple.copy(0.3f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.History, contentDescription = null, tint = CyberpunkNeonPurple, modifier = Modifier.size(16.dp))
                }
                Column {
                    Text("MODEL LLM", color = Color.Gray, fontSize = 9.sp)
                    Text("Qwen-2.5-0.5B", color = CyberpunkCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(16.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(36.dp).background(CyberpunkCyan.copy(0.1f), RoundedCornerShape(10.dp)).border(1.dp, CyberpunkCyan.copy(0.3f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.History, contentDescription = null, tint = CyberpunkCyan, modifier = Modifier.size(16.dp))
                }
                Column {
                    Text("DATABASE", color = Color.Gray, fontSize = 9.sp)
                    Text("Terhubung", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val grades by viewModel.dbGrades.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader()
        
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Daftar Nilai Masa Lalu", style = MaterialTheme.typography.titleMedium, color = CyberpunkCyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(16.dp))
            
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(grades) { grade ->
                    Box(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                            .border(1.dp, CyberpunkNeonPurple.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(grade.studentName, fontWeight = FontWeight.Bold, color = CyberpunkYellow, fontSize = 16.sp)
                                Text("${grade.subject} | Skor: ${grade.score}", color = Color.White, fontSize = 14.sp)
                                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(grade.date))
                                Text(dateStr, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            Button(
                                onClick = { viewModel.exportPdf(grade, context) },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberpunkCyan),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("PDF", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    
    val matrix = Matrix()
    matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
    
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
