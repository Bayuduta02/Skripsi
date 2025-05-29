package com.example.skripsi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Optimized Gesture Recognition Camera Activity
 * Features:
 * - GPU acceleration support
 * - Coroutine-based asynchronous processing
 * - Better memory management
 * - Improved coordinate transformation
 * - Enhanced error handling
 * - Performance optimizations
 */
class KameraGestureActivity : AppCompatActivity() {

    // UI Components
    private lateinit var previewView: PreviewView
    private lateinit var gestureText: TextView
    private lateinit var switchCameraButton: ImageButton
    private lateinit var overlayView: OverlayView

    // Camera and ML components
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var modelExecutor: ExecutorService
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private lateinit var classLabels: List<String>

    // Camera properties
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var cameraResolution: Size? = null
    private var previewRotation: Int = 0

    // Processing control
    private var isProcessing = false
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Model configuration
    private companion object {
        const val TAG = "OptimizedGestureCamera"
        const val MODEL_INPUT_SIZE = 224
        const val CONFIDENCE_THRESHOLD = 0.6f
        const val MIN_DETECTION_SIZE = 30f
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        // ImageNet normalization values
        val NORMALIZATION_MEAN = floatArrayOf(123.68f, 116.78f, 103.94f)
        val NORMALIZATION_STD = floatArrayOf(58.393f, 57.12f, 57.375f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.kamera_gesture)

        setupFullscreen()
        initializeComponents()

        // Log available camera sizes for debugging
        logAvailableCameraSizes()

        lifecycleScope.launch {
            try {
                initializeModel()
                setupCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                showErrorAndFinish("Failed to initialize camera and model")
            }
        }
    }

    private fun setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }
    }

    private fun initializeComponents() {
        previewView = findViewById(R.id.camera_preview)
        gestureText = findViewById(R.id.gesture_text)
        switchCameraButton = findViewById(R.id.btn_switch_camera)
        overlayView = findViewById(R.id.overlay_view)

        // Optimize PreviewView configuration untuk resolusi penuh
        previewView.apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER // Ubah ke FILL_CENTER untuk menggunakan ruang penuh
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }

        // Initialize executors
        cameraExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "CameraExecutor").apply {
                priority = Thread.MAX_PRIORITY
            }
        }

        modelExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ModelExecutor").apply {
                priority = Thread.NORM_PRIORITY + 1
            }
        }

        switchCameraButton.setOnClickListener { switchCamera() }
        switchCameraButton.contentDescription = "Switch Camera"
    }

    private suspend fun initializeModel() = withContext(Dispatchers.IO) {
        try {
            val modelBuffer = loadModelFromAssets("model.tflite")
            classLabels = loadLabelsFromAssets("labels.txt")

            val options = createInterpreterOptions()
            interpreter = Interpreter(modelBuffer, options)

            Log.d(TAG, "Model loaded successfully with ${classLabels.size} classes")
            Log.d(TAG, "GPU acceleration: ${gpuDelegate != null}")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            throw e
        }
    }

    private fun createInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()

        // Try GPU acceleration first
        val compatibilityList = CompatibilityList()
        if (compatibilityList.isDelegateSupportedOnThisDevice) {
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate!!)
                Log.d(TAG, "GPU acceleration enabled")
            } catch (e: Exception) {
                Log.w(TAG, "GPU acceleration failed, falling back to CPU", e)
                gpuDelegate = null
            }
        }

        // CPU optimization
        options.numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        options.useNNAPI = true

        return options
    }

    private suspend fun loadModelFromAssets(filename: String): MappedByteBuffer =
        withContext(Dispatchers.IO) {
            try {
                assets.openFd(filename).use { fileDescriptor ->
                    FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                        val fileChannel = inputStream.channel
                        val startOffset = fileDescriptor.startOffset
                        val declaredLength = fileDescriptor.declaredLength
                        fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error loading model file: $filename", e)
                throw e
            }
        }

    private suspend fun loadLabelsFromAssets(filename: String): List<String> =
        withContext(Dispatchers.IO) {
            try {
                assets.open(filename).bufferedReader().readLines()
            } catch (e: IOException) {
                Log.e(TAG, "Error loading labels file: $filename", e)
                // Return default labels if file not found
                listOf("Unknown", "Thumbs Up", "Peace", "Okay", "Fist", "Open Palm")
            }
        }

    private fun setupCamera() {
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startCamera()
        } else {
            showErrorAndFinish("Camera permission is required")
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            lifecycleScope.launch {
                try {
                    cameraProvider = cameraProviderFuture.get()
                    bindCameraUseCases()
                } catch (e: Exception) {
                    Log.e(TAG, "Camera initialization failed", e)
                    showErrorAndFinish("Camera initialization failed")
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private suspend fun bindCameraUseCases() = withContext(Dispatchers.Main) {
        try {
            cameraProvider?.unbindAll()

            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            previewRotation = rotation

            // Konfigurasi ResolutionSelector untuk mendapatkan resolusi optimal
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                )
                .setResolutionStrategy(
                    ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                )
                .build()

            // Preview dengan resolusi optimal
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .setResolutionSelector(resolutionSelector)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // ImageAnalysis dengan resolusi yang sama
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .setResolutionSelector(resolutionSelector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!isProcessing) {
                            processImageAsync(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            // Hapus viewport yang membatasi resolusi dan gunakan auto-fit
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            // Bind use cases tanpa ViewPort untuk mendapatkan resolusi penuh
            camera = cameraProvider?.bindToLifecycle(
                this@KameraGestureActivity,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            // Log resolusi yang digunakan
            preview.resolutionInfo?.let { resolutionInfo ->
                Log.d(TAG, "Preview resolution: ${resolutionInfo.resolution}")
            }

            imageAnalyzer.resolutionInfo?.let { resolutionInfo ->
                Log.d(TAG, "Analysis resolution: ${resolutionInfo.resolution}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            Toast.makeText(this@KameraGestureActivity, "Camera initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageAsync(imageProxy: ImageProxy) {
        if (cameraResolution == null) {
            cameraResolution = Size(imageProxy.width, imageProxy.height)
            Log.d(TAG, "Camera resolution: ${imageProxy.width}x${imageProxy.height}")
        }

        processingScope.launch {
            isProcessing = true
            try {
                val bitmap = imageProxy.convertToBitmap()
                if (bitmap != null) {
                    val result = analyzeGesture(bitmap)
                    updateUI(result, bitmap.width, bitmap.height)
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
            } finally {
                isProcessing = false
                imageProxy.close()
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private suspend fun ImageProxy.convertToBitmap(): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val image = this@convertToBitmap.image ?: return@withContext null

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

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = java.io.ByteArrayOutputStream()

            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val imageBytes = out.toByteArray()

            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        } catch (e: Exception) {
            Log.e(TAG, "Error converting ImageProxy to Bitmap", e)
            null
        }
    }

    private suspend fun analyzeGesture(bitmap: Bitmap): GestureResult = withContext(Dispatchers.Default) {
        try {
            val interpreter = this@KameraGestureActivity.interpreter
                ?: return@withContext GestureResult.error("Model not initialized")

            val inputBuffer = preprocessImage(bitmap)

            // Create output arrays
            val outputBBox = Array(1) { FloatArray(4) }
            val outputClass = Array(1) { FloatArray(classLabels.size) }

            // Run inference
            val inputs = arrayOf(inputBuffer)
            val outputs = mapOf(0 to outputBBox, 1 to outputClass)

            interpreter.runForMultipleInputsOutputs(inputs, outputs)

            // Process results
            val maxIndex = outputClass[0].indices.maxByOrNull { outputClass[0][it] } ?: -1
            val confidence = if (maxIndex != -1) outputClass[0][maxIndex] else 0f

            if (confidence > CONFIDENCE_THRESHOLD && maxIndex in classLabels.indices) {
                val label = classLabels[maxIndex]
                val pixelBBox = convertNormalizedToPixelCoords(outputBBox[0], bitmap.width, bitmap.height)

                GestureResult.success(label, pixelBBox, confidence)
            } else {
                GestureResult.noDetection()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during gesture analysis", e)
            GestureResult.error("Analysis failed: ${e.message}")
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        resized.getPixels(intValues, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

        // Normalize pixels
        for (pixelValue in intValues) {
            val r = ((pixelValue shr 16) and 0xFF).toFloat()
            val g = ((pixelValue shr 8) and 0xFF).toFloat()
            val b = (pixelValue and 0xFF).toFloat()

            byteBuffer.putFloat((r - NORMALIZATION_MEAN[0]) / NORMALIZATION_STD[0])
            byteBuffer.putFloat((g - NORMALIZATION_MEAN[1]) / NORMALIZATION_STD[1])
            byteBuffer.putFloat((b - NORMALIZATION_MEAN[2]) / NORMALIZATION_STD[2])
        }

        resized.recycle()
        return byteBuffer
    }

    private fun convertNormalizedToPixelCoords(normalizedBBox: FloatArray, width: Int, height: Int): RectF {
        return RectF(
            (normalizedBBox[0] * width).coerceAtLeast(0f),
            (normalizedBBox[1] * height).coerceAtLeast(0f),
            (normalizedBBox[2] * width).coerceAtMost(width.toFloat()),
            (normalizedBBox[3] * height).coerceAtMost(height.toFloat())
        )
    }

    @SuppressLint("SetTextI18n", "DefaultLocale")
    private suspend fun updateUI(result: GestureResult, imageWidth: Int, imageHeight: Int) =
        withContext(Dispatchers.Main) {
            when (result) {
                is GestureResult.Success -> {
                    gestureText.text = "Detected: ${result.label} (${String.format("%.1f", result.confidence * 100)}%)"

                    // Transform coordinates for overlay
                    previewView.post {
                        val transformedBBox = transformCoordinates(
                            result.boundingBox,
                            imageWidth,
                            imageHeight
                        )

                        transformedBBox?.let {
                            if (it.width() >= MIN_DETECTION_SIZE && it.height() >= MIN_DETECTION_SIZE) {
                                overlayView.setResults(listOf(it), listOf(result.label))
                            } else {
                                overlayView.clearResults()
                            }
                        } ?: overlayView.clearResults()
                    }
                }
                is GestureResult.NoDetection -> {
                    gestureText.text = "Tunjukkan gesture tangan Anda"
                    overlayView.clearResults()
                }
                is GestureResult.Error -> {
                    gestureText.text = "Error: ${result.message}"
                    overlayView.clearResults()
                }
            }
        }
    private fun logAvailableCameraSizes() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val configMap = characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                configMap?.getOutputSizes(ImageFormat.YUV_420_888)?.let { sizes ->
                    Log.d(TAG, "Camera $cameraId available sizes:")
                    sizes.forEach { size ->
                        Log.d(TAG, "  ${size.width}x${size.height}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera info", e)
        }
    }

    private fun transformCoordinates(bbox: RectF, imageWidth: Int, imageHeight: Int): RectF? {
        return try {
            val previewWidth = previewView.width.toFloat()
            val previewHeight = previewView.height.toFloat()

            if (previewWidth <= 0 || previewHeight <= 0) return null

            // Dengan FILL_CENTER, gambar akan mengisi seluruh preview tanpa letterbox
            // Hitung skala berdasarkan dimensi yang lebih kecil
            val scaleX = previewWidth / imageWidth
            val scaleY = previewHeight / imageHeight

            val left = bbox.left * scaleX
            val top = bbox.top * scaleY
            val right = bbox.right * scaleX
            val bottom = bbox.bottom * scaleY

            // Koreksi mirror jika kamera depan
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                RectF(
                    previewWidth - right,
                    top,
                    previewWidth - left,
                    bottom
                )
            } else {
                RectF(left, top, right, bottom)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error transforming coordinates", e)
            null
        }
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        lifecycleScope.launch {
            bindCameraUseCases()
        }
    }

    private fun showErrorAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted() && cameraProvider != null) {
            lifecycleScope.launch {
                bindCameraUseCases()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cancel all coroutines
        processingScope.cancel()

        // Shutdown executors
        cameraExecutor.shutdown()
        modelExecutor.shutdown()

        // Clean up ML resources
        interpreter?.close()
        gpuDelegate?.close()
    }

    /**
     * Sealed class representing gesture analysis results
     */
    sealed class GestureResult {
        data class Success(
            val label: String,
            val boundingBox: RectF,
            val confidence: Float
        ) : GestureResult()

        data object NoDetection : GestureResult()

        data class Error(val message: String) : GestureResult()

        companion object {
            fun success(label: String, boundingBox: RectF, confidence: Float) =
                Success(label, boundingBox, confidence)

            fun noDetection() = NoDetection

            fun error(message: String) = Error(message)
        }
    }
}