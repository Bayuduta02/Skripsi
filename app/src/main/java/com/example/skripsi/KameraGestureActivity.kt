package com.example.skripsi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Suppress("DEPRECATION")
class KameraGestureActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var gestureText: TextView
    private lateinit var switchCameraButton: ImageButton
    private lateinit var overlayView: OverlayView

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var interpreter: Interpreter
    private lateinit var classLabels: List<String>

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null

    // Variables untuk tracking transformasi
    private var imageWidth = 0
    private var imageHeight = 0
    private var previewWidth = 0
    private var previewHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.kamera_gesture)

        // Set full screen dengan cara yang lebih modern
        setupFullScreen()

        initViews()
        setupCamera()
    }

    private fun setupFullScreen() {
        // Hide system UI untuk full screen
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Alternative method jika method di atas tidak bekerja
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    private fun initViews() {
        previewView = findViewById(R.id.camera_preview)
        gestureText = findViewById(R.id.gesture_text)
        switchCameraButton = findViewById(R.id.btn_switch_camera)
        overlayView = findViewById(R.id.overlay)

        // Optimasi PreviewView
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER // Ubah ke FILL_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE

        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            val options = Interpreter.Options().apply {
                numThreads = 4
                useNNAPI = false
            }
            interpreter = Interpreter(loadModelFile("model.tflite"), options)
            classLabels = assets.open("labels.txt").bufferedReader().readLines()
            Log.d(TAG, "Model loaded successfully with ${classLabels.size} classes")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            Toast.makeText(this, "Error loading AI model", Toast.LENGTH_LONG).show()
            finish()
        }

        switchCameraButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
    }

    private fun setupCamera() {
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider?.unbindAll()

                val rotation = previewView.display?.rotation ?: 0

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Ubah ke 4:3 untuk kompatibilitas lebih baik
                    .setTargetRotation(rotation)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(rotation)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImage(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Camera initialization failed", exc)
                Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        var bitmap: Bitmap? = null
        try {
            bitmap = imageProxy.toBitmap()
            if (bitmap != null) {
                // Simpan dimensi untuk transformasi
                imageWidth = bitmap.width
                imageHeight = bitmap.height

                val (label, bbox, confidence) = analyzeImage(bitmap)

                runOnUiThread {
                    // Update dimensi preview saat ini
                    previewWidth = previewView.width
                    previewHeight = previewView.height

                    if (confidence > 0.5f && !label.contains("tidak dikenali")) {
                        gestureText.text = label

                        // Transformasi bounding box yang lebih akurat
                        val transformedBBox = transformBoundingBoxToView(bbox)

                        val clampedBBox = RectF(
                            transformedBBox.left.coerceAtLeast(0f),
                            transformedBBox.top.coerceAtLeast(0f),
                            transformedBBox.right.coerceAtMost(previewWidth.toFloat()),
                            transformedBBox.bottom.coerceAtMost(previewHeight.toFloat())
                        )

                        if (clampedBBox.width() > 20f && clampedBBox.height() > 20f) {
                            overlayView.setResults(listOf(clampedBBox), listOf(label))
                        } else {
                            overlayView.clearResults()
                        }
                    } else {
                        gestureText.text = "Tunjukkan gesture tangan Anda"
                        overlayView.clearResults()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            // Pastikan bitmap di-recycle dengan aman
            bitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            imageProxy.close()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun ImageProxy.toBitmap(): Bitmap? {
        return try {
            val image = this.image ?: return null
            val buffer = image.planes[0].buffer
            val pixelStride = image.planes[0].pixelStride
            val rowStride = image.planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            buffer.position(0) // Reset buffer position
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                bitmap
            } else {
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (!bitmap.isRecycled) bitmap.recycle()
                croppedBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ImageProxy to Bitmap", e)
            null
        }
    }

    private fun analyzeImage(bitmap: Bitmap): Triple<String, FloatArray, Float> {
        return try {
            val inputBuffer = convertBitmapToByteBuffer(bitmap)
            val outputBBox = Array(1) { FloatArray(4) }
            val outputClass = Array(1) { FloatArray(classLabels.size) }

            interpreter.runForMultipleInputsOutputs(
                arrayOf(inputBuffer),
                mapOf(0 to outputBBox, 1 to outputClass)
            )

            val maxIndex = outputClass[0].indices.maxByOrNull { outputClass[0][it] } ?: -1
            val confidence = if (maxIndex != -1) outputClass[0][maxIndex] else 0f

            val label = if (confidence > 0.5f && maxIndex in classLabels.indices) {
                "${classLabels[maxIndex]} (${"%.1f".format(confidence * 100)}%)"
            } else {
                "Gesture tidak dikenali"
            }

            // Konversi normalized coordinates ke pixel coordinates
            val pixelBBox = FloatArray(4).apply {
                this[0] = outputBBox[0][0] * bitmap.width  // x_min
                this[1] = outputBBox[0][1] * bitmap.height // y_min
                this[2] = outputBBox[0][2] * bitmap.width  // x_max
                this[3] = outputBBox[0][3] * bitmap.height // y_max
            }

            Log.d(TAG, "Detected: $label, BBox: [${pixelBBox[0]}, ${pixelBBox[1]}, ${pixelBBox[2]}, ${pixelBBox[3]}]")

            Triple(label, pixelBBox, confidence)
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference", e)
            Triple("Error", FloatArray(4), 0f)
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val inputSize = 224
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        // Normalisasi ImageNet
        val mean = floatArrayOf(123.68f, 116.78f, 103.94f)
        val std = floatArrayOf(58.393f, 57.12f, 57.375f)

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = resized.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                byteBuffer.putFloat((r - mean[0]) / std[0])
                byteBuffer.putFloat((g - mean[1]) / std[1])
                byteBuffer.putFloat((b - mean[2]) / std[2])
            }
        }

        if (!resized.isRecycled) resized.recycle()
        return byteBuffer
    }

    // Fungsi transformasi yang diperbaiki
    private fun transformBoundingBoxToView(bbox: FloatArray): RectF {
        if (previewWidth == 0 || previewHeight == 0 || imageWidth == 0 || imageHeight == 0) {
            return RectF(0f, 0f, 0f, 0f)
        }

        val viewWidth = previewWidth.toFloat()
        val viewHeight = previewHeight.toFloat()
        val imgWidth = imageWidth.toFloat()
        val imgHeight = imageHeight.toFloat()

        // Hitung scale factor
        val scaleX = viewWidth / imgWidth
        val scaleY = viewHeight / imgHeight

        // Gunakan scale yang sesuai dengan PreviewView.ScaleType.FILL_CENTER
        val scale = maxOf(scaleX, scaleY)

        // Hitung offset untuk centering
        val scaledImageWidth = imgWidth * scale
        val scaledImageHeight = imgHeight * scale
        val offsetX = (viewWidth - scaledImageWidth) / 2f
        val offsetY = (viewHeight - scaledImageHeight) / 2f

        // Transform coordinates
        val transformedBBox = RectF(
            bbox[0] * scale + offsetX,  // left
            bbox[1] * scale + offsetY,  // top
            bbox[2] * scale + offsetX,  // right
            bbox[3] * scale + offsetY   // bottom
        )

        Log.d(TAG, "Transform - Image: ${imgWidth}x${imgHeight}, Preview: ${viewWidth}x${viewHeight}")
        Log.d(TAG, "Scale: $scale, Offset: ($offsetX, $offsetY)")
        Log.d(TAG, "Original BBox: [${bbox[0]}, ${bbox[1]}, ${bbox[2]}, ${bbox[3]}]")
        Log.d(TAG, "Transformed BBox: $transformedBBox")

        return transformedBBox
    }

    private fun loadModelFile(filename: String): ByteBuffer {
        return assets.openFd(filename).use { fd ->
            val inputStream = fd.createInputStream()
            val byteBuffer = ByteBuffer.allocateDirect(fd.length.toInt())
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                byteBuffer.put(buffer, 0, bytesRead)
            }
            byteBuffer.rewind()
            byteBuffer
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply full screen saat resume
        setupFullScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::interpreter.isInitialized) interpreter.close()
    }

    companion object {
        private const val TAG = "KameraGestureActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}