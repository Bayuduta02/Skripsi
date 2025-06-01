// KameraGestureActivity.kt (Versi yang Diperbaiki untuk Screen Size)
package com.example.skripsi

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class KameraGestureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var gestureText: TextView
    private lateinit var interpreter: Interpreter
    private lateinit var labels: List<String>
    private lateinit var cameraExecutor: ExecutorService

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup fullscreen sebelum setContentView
        setupFullscreen()

        setContentView(R.layout.camera_activity)

        previewView = findViewById(R.id.camera_preview)
        overlayView = findViewById(R.id.overlay_view)
        gestureText = findViewById(R.id.gesture_text)

        // Sesuaikan ukuran PreviewView dengan screen size
        adjustPreviewViewSize()

        // Gunakan FILL_CENTER untuk mengisi area yang tersedia
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE

        findViewById<ImageButton>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_switch_camera).setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun adjustPreviewViewSize() {
        // Dapatkan ukuran layar yang sebenarnya
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Hitung ukuran yang tersedia untuk kamera
        val availableHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars()
            )
            windowMetrics.bounds.height() - insets.top - insets.bottom
        } else {
            (screenHeight * 0.92).toInt()
        }

        Log.d("DEBUG_ADJUST", "Adjusting PreviewView to: $screenWidth x $availableHeight")

        // Set ukuran PreviewView secara programatis
        val layoutParams = previewView.layoutParams as FrameLayout.LayoutParams
        layoutParams.width = screenWidth
        layoutParams.height = availableHeight
        layoutParams.gravity = android.view.Gravity.CENTER
        previewView.layoutParams = layoutParams

        // Set ukuran OverlayView yang sama
        val overlayLayoutParams = overlayView.layoutParams as FrameLayout.LayoutParams
        overlayLayoutParams.width = screenWidth
        overlayLayoutParams.height = availableHeight
        overlayLayoutParams.gravity = android.view.Gravity.CENTER
        overlayView.layoutParams = overlayLayoutParams
    }

    private fun setupFullscreen() {
        // Set flag untuk menjaga layar tetap menyala
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Untuk Android 11+ (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Set window untuk edge-to-edge
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            // Set untuk mendukung display cutout/notch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

        } else {
            // Untuk Android versi lama (API < 30)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )

            // Set untuk mendukung display cutout/notch di Android P+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun forceFullscreen() {
        // Method tambahan untuk memaksa fullscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    override fun onResume() {
        super.onResume()
        // Pastikan fullscreen tetap aktif saat kembali ke activity
        setupFullscreen()
        forceFullscreen()

        previewView.post {
            Log.d("DEBUG_PREVIEW", "PreviewView size: ${previewView.width} x ${previewView.height}")
        }
        overlayView.post {
            Log.d("DEBUG_OVERLAY", "OverlayView size: ${overlayView.width} x ${overlayView.height}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullscreen()
            forceFullscreen()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initModel()
            startCamera()
        } else {
            Toast.makeText(this, "Izin kamera dibutuhkan", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun initModel() {
        try {
            val modelFile = assets.openFd("model.tflite")
            val fileInputStream = FileInputStream(modelFile.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = modelFile.startOffset
            val declaredLength = modelFile.declaredLength
            val modelBuffer: MappedByteBuffer =
                fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            interpreter = Interpreter(modelBuffer)

            val labelsInputStream = assets.open("labels.txt")
            labels = labelsInputStream.bufferedReader().readLines()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memuat model: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Dapatkan ukuran layar yang sebenarnya
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // Hitung ukuran yang tersedia untuk kamera (dikurangi system bars)
            val availableHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars()
                )
                windowMetrics.bounds.height() - insets.top - insets.bottom
            } else {
                // Untuk versi Android lama, gunakan tinggi yang lebih konservatif
                (screenHeight * 0.92).toInt() // Estimasi mengurangi space untuk system bars
            }

            Log.d("DEBUG_SCREEN", "Original screen size: $screenWidth x $screenHeight")
            Log.d("DEBUG_SCREEN", "Available size for camera: $screenWidth x $availableHeight")

            // Gunakan ukuran yang sesuai dengan available size for camera
            val targetResolution = Size(screenWidth, availableHeight)

            Log.d("DEBUG_CAMERA", "Target resolution: ${targetResolution.width} x ${targetResolution.height}")

            val preview = Preview.Builder()
                .setTargetResolution(targetResolution)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(224, 224))
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, GestureAnalyzer())
                }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                Log.d("DEBUG_CAMERA", "Camera bound successfully")

                // Log resolusi sebenarnya yang digunakan
                camera.cameraInfo.let { cameraInfo ->
                    Log.d("DEBUG_CAMERA", "Camera info: $cameraInfo")
                }

            } catch (exc: Exception) {
                Log.e("DEBUG_CAMERA", "Camera binding failed", exc)
                Toast.makeText(this, "Gagal inisialisasi kamera: ${exc.message}", Toast.LENGTH_SHORT).show()

                // Coba dengan resolusi yang lebih rendah sebagai fallback
                tryFallbackResolution(cameraProvider, imageAnalyzer)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tryFallbackResolution(
        cameraProvider: ProcessCameraProvider,
        imageAnalyzer: ImageAnalysis
    ) {
        try {
            Log.d("DEBUG_CAMERA", "Trying fallback resolution...")

            // Dapatkan ukuran layar untuk fallback
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // Coba dengan berbagai resolusi fallback yang umum
            val fallbackResolutions = listOf(
                Size(screenWidth, (screenHeight * 0.9).toInt()), // 90% dari screen height
                Size(screenWidth, (screenHeight * 0.8).toInt()), // 80% dari screen height
                Size(1080, 1920), // Full HD standard
                Size(720, 1280),  // HD standard
                Size(480, 640)    // SD standard
            )

            for (resolution in fallbackResolutions) {
                try {
                    Log.d("DEBUG_CAMERA", "Trying fallback resolution: ${resolution.width} x ${resolution.height}")

                    val fallbackPreview = Preview.Builder()
                        .setTargetResolution(resolution)
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        fallbackPreview,
                        imageAnalyzer
                    )

                    Log.d("DEBUG_CAMERA", "Fallback camera binding successful with resolution: ${resolution.width} x ${resolution.height}")
                    return // Berhasil, keluar dari fungsi

                } catch (e: Exception) {
                    Log.w("DEBUG_CAMERA", "Fallback resolution ${resolution.width} x ${resolution.height} failed: ${e.message}")
                    continue // Coba resolusi berikutnya
                }
            }

            // Jika semua fallback gagal
            throw Exception("All fallback resolutions failed")

        } catch (fallbackExc: Exception) {
            Log.e("DEBUG_CAMERA", "All fallback camera binding failed", fallbackExc)
            Toast.makeText(
                this,
                "Tidak dapat menginisialisasi kamera dengan resolusi apapun",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    inner class GestureAnalyzer : ImageAnalysis.Analyzer {
        @SuppressLint("SetTextI18n")
        override fun analyze(imageProxy: ImageProxy) {
            val bitmap = imageProxy.toBitmap(imageProxy.imageInfo.rotationDegrees)
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true)

            val tfImage = TensorImage(DataType.FLOAT32)
            tfImage.load(scaledBitmap)

            val outputBbox = Array(1) { FloatArray(4) }
            val outputClasses = Array(1) { FloatArray(32) }
            val outputMap = mapOf(0 to outputBbox, 1 to outputClasses)

            interpreter.runForMultipleInputsOutputs(arrayOf(tfImage.buffer), outputMap)

            val classProbabilities = outputClasses[0]
            val classIndex = classProbabilities.indices.maxByOrNull { classProbabilities[it] } ?: -1
            val score = if (classIndex != -1) classProbabilities[classIndex] else 0f
            val label = labels.getOrNull(classIndex) ?: "?"

            val box = outputBbox[0]
            val previewWidth = previewView.width.toFloat()
            val previewHeight = previewView.height.toFloat()
            val inputSize = 224f
            val scaleX = previewWidth / inputSize
            val scaleY = previewHeight / inputSize

            val boundingBoxRect = RectF(
                box[0] * inputSize * scaleX,
                box[1] * inputSize * scaleY,
                box[2] * inputSize * scaleX,
                box[3] * inputSize * scaleY
            )

            runOnUiThread {
                if (score > 0.5f) {
                    gestureText.text = "$label (${(score * 100).toInt()}%)"
                    overlayView.setBoxAndLabel(boundingBoxRect, label)
                } else {
                    gestureText.text = "Tidak dikenal"
                    overlayView.clearBox()
                }
            }

            imageProxy.close()
        }
    }

    private fun ImageProxy.toBitmap(rotationDegrees: Int = 0): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        return if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::interpreter.isInitialized) interpreter.close()
    }
}