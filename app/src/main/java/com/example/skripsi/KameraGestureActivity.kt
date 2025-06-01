package com.example.skripsi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.skripsi.utils.AutoFitSurfaceView
import com.example.skripsi.utils.getPreviewOutputSize

class KameraGestureActivity : AppCompatActivity() {

    private lateinit var surfaceView: AutoFitSurfaceView
    private lateinit var gestureText: TextView
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var previewSize: Size
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var closeButton: ImageButton
    private lateinit var switchCameraButton: ImageButton

    private val cameraManager: CameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var currentCameraId: String = ""
    private var isBackCamera: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        setContentView(R.layout.camera_activity)
        surfaceView = findViewById(R.id.camera_preview)
        gestureText = findViewById(R.id.gesture_text)
        closeButton = findViewById(R.id.btn_close)
        switchCameraButton = findViewById(R.id.btn_switch_camera)

        closeButton.setOnClickListener { finish() }
        switchCameraButton.setOnClickListener {
            isBackCamera = !isBackCamera
            closeCamera()
            openCamera()
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                openCamera()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        currentCameraId = cameraManager.cameraIdList.first { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (isBackCamera) facing == CameraCharacteristics.LENS_FACING_BACK else facing == CameraCharacteristics.LENS_FACING_FRONT
        }

        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
        previewSize = getPreviewOutputSize(
            windowManager.defaultDisplay, characteristics, SurfaceHolder::class.java
        )
        surfaceView.setAspectRatio(previewSize.width, previewSize.height)

        cameraManager.openCamera(currentCameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createCameraPreviewSession()
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                Toast.makeText(this@KameraGestureActivity, "Camera error: $error", Toast.LENGTH_SHORT).show()
            }
        }, null)
    }

    private fun closeCamera() {
        if (::cameraDevice.isInitialized) {
            cameraDevice.close()
        }
    }

    private fun createCameraPreviewSession() {
        val surface = surfaceView.holder.surface
        surfaceView.holder.setFixedSize(previewSize.width, previewSize.height)

        previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder.addTarget(surface)

        cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                cameraCaptureSession = session
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                cameraCaptureSession.setRepeatingRequest(
                    previewRequestBuilder.build(), null, null
                )
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Toast.makeText(this@KameraGestureActivity, "Camera preview failed", Toast.LENGTH_SHORT).show()
            }
        }, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
    }
}
