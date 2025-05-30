package com.example.skripsi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.kamera_gesture)

        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )

        initViews()
        setupCamera()
    }

    private fun initViews() {
        previewView = findViewById(R.id.camera_preview)
        gestureText = findViewById(R.id.gesture_text)
        switchCameraButton = findViewById(R.id.btn_switch_camera)
        overlayView = findViewById(R.id.overlay_view)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
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
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
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
        try {
            val bitmap = imageProxy.toBitmap()
            if (bitmap != null) {
                val (label, bbox, confidence) = analyzeImage(bitmap)

                runOnUiThread {
                    if (confidence > 0.5f && !label.contains("tidak dikenali")) {
                        gestureText.text = label
                        val transformedBBox = transformBoundingBoxToView(bbox, bitmap)

                        val clampedBBox = RectF(
                            transformedBBox.left.coerceAtLeast(0f),
                            transformedBBox.top.coerceAtLeast(0f),
                            transformedBBox.right.coerceAtMost(previewView.width.toFloat()),
                            transformedBBox.bottom.coerceAtMost(previewView.height.toFloat())
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

                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
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
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                bitmap
            } else {
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
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

            val pixelBBox = FloatArray(4).apply {
                this[0] = outputBBox[0][0] * bitmap.width
                this[1] = outputBBox[0][1] * bitmap.height
                this[2] = outputBBox[0][2] * bitmap.width
                this[3] = outputBBox[0][3] * bitmap.height
            }

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

        resized.recycle()
        return byteBuffer
    }

    private fun transformBoundingBoxToView(bbox: FloatArray, bitmap: Bitmap): RectF {
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()

        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)

        val scaledImageWidth = imageWidth * scale
        val scaledImageHeight = imageHeight * scale

        val dx = (viewWidth - scaledImageWidth) / 2f
        val dy = (viewHeight - scaledImageHeight) / 2f

        return RectF(
            bbox[0] * scale + dx,
            bbox[1] * scale + dy,
            bbox[2] * scale + dx,
            bbox[3] * scale + dy
        )
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
