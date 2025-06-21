package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.cameraxapp.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request camera permissions
        if (allPermissionsGranted()) startCamera() else requestPermissions()

        /* ── UI listeners ───────────────────────────────────────────── */

        // Shutter
        binding.imageCaptureButton?.setOnClickListener { takePhoto() }

        // ℹ️ → open bubble
        binding.infoButton?.setOnClickListener {
            binding.infoButton?.visibility = View.GONE          // hide ℹ️
            binding.infoCloseButton?.visibility = View.VISIBLE  // show ⓧ

            binding.infoBubble?.apply {
                alpha = 0f
                translationY = -32f            // subtle emerge-from-button
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250L)
                    .start()
            }
        }

        // ⓧ → close bubble
        binding.infoCloseButton?.setOnClickListener {
            binding.infoCloseButton?.visibility = View.GONE     // hide ⓧ
            binding.infoButton?.visibility  = View.VISIBLE      // show ℹ️

            binding.infoBubble?.animate()
                ?.alpha(0f)
                ?.translationY(-32f)
                ?.setDuration(200L)
                ?.withEndAction { binding.infoBubble?.visibility = View.GONE }
                ?.start()
        }

        /* ───────────────────────────────────────────────────────────── */

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    /* ---------------- Camera-X helpers ---------------- */

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val msg = "Photo saved: ${result.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder?.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()

            val analyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                    Log.d(TAG, "Average luminosity: $luma")
                })
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    analyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use-case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /* ---------------- permission helpers ---------------- */

    private fun requestPermissions() =
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = perms.entries.all { it.key !in REQUIRED_PERMISSIONS || it.value }
            if (granted) startCamera()
            else Toast.makeText(this, "Permission request denied", Toast.LENGTH_SHORT).show()
        }

    /* ---------------- life-cycle ---------------- */

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    /* ---------------- static / helper ---------------- */

    private class LuminosityAnalyzer(private val listener: LumaListener) :
        ImageAnalysis.Analyzer {
        private fun ByteBuffer.toByteArray() = ByteArray(remaining()).also { get(it) }
        override fun analyze(image: ImageProxy) {
            val luma = image.planes[0].buffer
                .toByteArray()
                .map { it.toInt() and 0xFF }
                .average()
            listener(luma)
            image.close()
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
}
