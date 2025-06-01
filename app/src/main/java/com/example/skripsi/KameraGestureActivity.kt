package com.example.skripsi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.skripsi.utils.AutoFitSurfaceView
import com.example.skripsi.utils.getPreviewOutputSize

class KameraGestureActivity : AppCompatActivity() {

    private lateinit var surfaceView: AutoFitSurfaceView
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var previewSize: Size
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    private val cameraManager: CameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        surfaceView = AutoFitSurfaceView(this)
        setContentView(surfaceView)

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
        val cameraId = cameraManager.cameraIdList.first()
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        previewSize = getPreviewOutputSize(
            windowManager.defaultDisplay, characteristics, SurfaceHolder::class.java
        )
        surfaceView.setAspectRatio(previewSize.width, previewSize.height)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
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
        if (::cameraDevice.isInitialized) {
            cameraDevice.close()
        }
    }
}
