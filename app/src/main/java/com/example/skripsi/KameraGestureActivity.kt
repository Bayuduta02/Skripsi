package com.example.skripsi

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class KameraGestureActivity : AppCompatActivity() {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var switchCameraButton: ImageButton
    private lateinit var gestureTextView: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.kamera_gesture)

        previewView = findViewById(R.id.camera_preview)
        switchCameraButton = findViewById(R.id.btn_switch_camera)
        gestureTextView = findViewById(R.id.gesture_text)

        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCamera()

            switchCameraButton.setOnClickListener {
                toggleCamera()
            }

        }, ContextCompat.getMainExecutor(this)) // Gunakan thread utama untuk listener
    }

    // Fungsi untuk mengganti antara kamera depan dan belakang
    private fun toggleCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        bindCamera() // Bind ulang kamera setelah toggle
    }

    // Fungsi untuk mengikat (bind) kamera dengan lifecycle
    private fun bindCamera() {
        cameraProvider?.unbindAll() // Unbind kamera sebelumnya agar bisa bind yang baru
        try {
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            cameraProvider?.bindToLifecycle(this, cameraSelector, preview)

            // Placeholder untuk deteksi bahasa isyarat (nanti ganti dengan hasil deteksi)
            detectSignLanguage("Makan") // Contoh hasil deteksi sementara
        } catch (exc: Exception) {
            Log.e("CameraX", "Gagal memulai kamera: ${exc.message}")
        }
    }

    // Fungsi untuk mendeteksi bahasa isyarat dan menampilkan di TextView
    @SuppressLint("SetTextI18n")
    private fun detectSignLanguage(detectedText: String) {
        gestureTextView.text = "Deteksi: $detectedText"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown() // Pastikan executor ditutup dengan benar saat Activity dihancurkan
    }
}
