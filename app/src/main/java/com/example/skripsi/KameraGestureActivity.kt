package com.example.skripsi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class KameraGestureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var gestureText: TextView
    private lateinit var switchCameraButton: ImageButton
    private lateinit var closeButton: ImageButton

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tflite: Interpreter
    private lateinit var labels: List<String>

    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    private val inputSize = 224
    private val threshold = 0.5f

    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0
    private var previewWidth = 0f
    private var previewHeight = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.kamera_gesture)

        // Get screen dimensions
        getScreenDimensions()

        // Setup fullscreen immersive mode
        setupFullscreenMode()

        // Initialize views
        initializeViews()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Load model and labels
        try {
            tflite = loadModel()
            labels = loadLabels()
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading model: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Setup button listeners
        setupButtonListeners()

        // Request camera permission and start camera
        requestPermissionAndStartCamera()
    }

    private fun getScreenDimensions() {
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(displayMetrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        }
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
    }

    private fun setupFullscreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    private fun initializeViews() {
        previewView = findViewById(R.id.camera_preview)
        overlayView = findViewById(R.id.overlay_view)
        gestureText = findViewById(R.id.gesture_text)
        switchCameraButton = findViewById(R.id.btn_switch_camera)
        closeButton = findViewById(R.id.btn_close)

        // Set preview view scale type for full screen
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        // Set initial gesture text
        gestureText.text = "Mendeteksi gesture..."

        // Wait for layout to get actual preview dimensions
        previewView.post {
            previewWidth = previewView.width.toFloat()
            previewHeight = previewView.height.toFloat()
        }
    }

    private fun setupButtonListeners() {
        switchCameraButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }

        closeButton.setOnClickListener {
            finish()
        }
    }

    private fun requestPermissionAndStartCamera() {
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Izin kamera diperlukan untuk menjalankan aplikasi", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Create high resolution target
                val targetResolution = Size(screenWidth, screenHeight)

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetResolution,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                // Preview use case
                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also { preview ->
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // ImageAnalysis use case
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analyzer ->
                        analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImage(imageProxy)
                        }
                    }

                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this,
                    lensFacing,
                    preview,
                    imageAnalyzer
                )

                runOnUiThread {
                    val cameraText = if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) {
                        "Kamera Depan"
                    } else {
                        "Kamera Belakang"
                    }
                    Toast.makeText(this, "Menggunakan $cameraText", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal memulai kamera: ${e.message}", Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun loadModel(): Interpreter {
        return try {
            val assetFileDescriptor = assets.openFd("model.tflite")
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val byteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options()
            options.setNumThreads(4) // Use 4 threads for better performance

            Interpreter(byteBuffer, options)
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("Error loading TensorFlow Lite model: ${e.message}", e)
        }
    }

    private fun loadLabels(): List<String> {
        return try {
            assets.open("labels.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            e.printStackTrace()
            // Return default labels if file not found
            listOf("Unknown", "Gesture1", "Gesture2", "Gesture3")
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        try {
            // Get image rotation
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // Convert ImageProxy to Bitmap
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap == null) {
                return
            }

            // Rotate bitmap if needed
            val rotatedBitmap = if (rotationDegrees != 0) {
                rotateBitmap(bitmap, rotationDegrees.toFloat())
            } else {
                bitmap
            }

            // Resize bitmap for model input
            val resizedBitmap = Bitmap.createScaledBitmap(rotatedBitmap, inputSize, inputSize, true)
            val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

            // Prepare output arrays
            val outputBbox = Array(1) { FloatArray(4) } // [top, left, bottom, right] normalized
            val outputScores = Array(1) { FloatArray(labels.size.coerceAtLeast(1)) }

            // Run inference
            val outputMap = mapOf(
                0 to outputBbox,
                1 to outputScores
            )

            tflite.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

            // Process results
            val scores = outputScores[0]
            val bestIdx = scores.indices.maxByOrNull { scores[it] } ?: -1
            val bestScore = if (bestIdx >= 0) scores[bestIdx] else 0f

            if (bestScore > threshold && bestIdx >= 0) {
                val label = labels.getOrNull(bestIdx) ?: "Unknown"
                val box = outputBbox[0]

                // Calculate bounding box coordinates
                val boundingBox = calculateBoundingBox(box, rotatedBitmap.width, rotatedBitmap.height)

                runOnUiThread {
                    gestureText.text = "$label (${(bestScore * 100).toInt()}%)"
                    overlayView.setBoxAndLabel(boundingBox, label)
                }
            } else {
                runOnUiThread {
                    gestureText.text = "Tidak terdeteksi gesture"
                    overlayView.clearBox()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                gestureText.text = "Error dalam deteksi"
                overlayView.clearBox()
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun calculateBoundingBox(box: FloatArray, imageWidth: Int, imageHeight: Int): RectF {
        // Get current preview dimensions
        val currentPreviewWidth = previewView.width.toFloat()
        val currentPreviewHeight = previewView.height.toFloat()

        if (currentPreviewWidth <= 0 || currentPreviewHeight <= 0) {
            return RectF(0f, 0f, 0f, 0f)
        }

        // Bounding box dari model: [top, left, bottom, right] normalized (0-1)
        val top = box[0] * imageHeight
        val left = box[1] * imageWidth
        val bottom = box[2] * imageHeight
        val right = box[3] * imageWidth

        // Calculate scale factors
        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val previewAspectRatio = currentPreviewWidth / currentPreviewHeight

        val scaleX: Float
        val scaleY: Float
        val offsetX: Float
        val offsetY: Float

        if (imageAspectRatio > previewAspectRatio) {
            // Image is wider than preview - scale by height, center horizontally
            scaleY = currentPreviewHeight / imageHeight
            scaleX = scaleY
            offsetX = (currentPreviewWidth - imageWidth * scaleX) / 2f
            offsetY = 0f
        } else {
            // Image is taller than preview - scale by width, center vertically
            scaleX = currentPreviewWidth / imageWidth
            scaleY = scaleX
            offsetX = 0f
            offsetY = (currentPreviewHeight - imageHeight * scaleY) / 2f
        }

        // Apply scaling and offset
        var rect = RectF(
            left * scaleX + offsetX,
            top * scaleY + offsetY,
            right * scaleX + offsetX,
            bottom * scaleY + offsetY
        )

        // Handle front camera mirroring
        if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) {
            val flippedLeft = currentPreviewWidth - rect.right
            val flippedRight = currentPreviewWidth - rect.left
            rect = RectF(flippedLeft, rect.top, flippedRight, rect.bottom)
        }

        return rect
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            // Normalize RGB values to [0, 1]
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // Red
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // Green
            buffer.putFloat((pixel and 0xFF) / 255.0f)          // Blue
        }

        return buffer
    }

    @OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val image = imageProxy.image ?: return null

            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, outputStream)
            val imageBytes = outputStream.toByteArray()

            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
        val matrix = Matrix().apply {
            postRotate(rotationDegrees)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::tflite.isInitialized) {
            tflite.close()
        }
    }

    override fun onResume() {
        super.onResume()
        setupFullscreenMode()
    }

    override fun onPause() {
        super.onPause()
        // Clear detection when paused
        runOnUiThread {
            gestureText.text = "Mendeteksi gesture..."
            overlayView.clearBox()
        }
    }
}