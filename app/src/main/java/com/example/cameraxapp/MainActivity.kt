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
import androidx.lifecycle.lifecycleScope
import com.example.cameraxapp.databinding.ActivityMainBinding
import com.example.cameraxapp.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
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

    /* ---------------- life-cycle ---------------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) startCamera() else requestPermissions()

        /* ── UI listeners ────────────────────────── */

        binding.imageCaptureButton?.setOnClickListener {
        //    takePhoto()
              uploadFromAssets()          // send the bundled JPEG instead
        }

        // ℹ️ open bubble
        binding.infoButton?.setOnClickListener {
            binding.infoButton?.visibility = View.GONE
            binding.infoCloseButton?.visibility = View.VISIBLE

            binding.infoBubble?.apply {
                alpha = 0f
                translationY = -32f
                visibility = View.VISIBLE
                animate().alpha(1f).translationY(0f).setDuration(250L).start()
            }
        }

        // ⓧ close bubble
        binding.infoCloseButton?.setOnClickListener {
            binding.infoCloseButton?.visibility = View.GONE
            binding.infoButton?.visibility = View.VISIBLE

            binding.infoBubble?.animate()
                ?.alpha(0f)
                ?.translationY(-32f)
                ?.setDuration(200L)
                ?.withEndAction { binding.infoBubble?.visibility = View.GONE }
                ?.start()
        }

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
                    val uri = result.savedUri ?: return
                    Log.d(TAG, "Photo saved → $uri")

                    /* -------- upload to FastAPI in a coroutine -------- */
                    lifecycleScope.launch {
                        try {
                            val part: MultipartBody.Part =
                                uri.asMultipart(contentResolver)     // helper in UriExt.kt
                            val preds = ApiClient.service.predict(part)

                            withContext(Dispatchers.Main) { showPredictions(preds) }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Upload failed: ${e.localizedMessage}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
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

    private fun requestPermissions() = activityResultLauncher.launch(REQUIRED_PERMISSIONS)

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

    /* ---------------- utility / helper ---------------- */

    private fun showPredictions(preds: List<Prediction>) {
        val text = preds.joinToString("\n") {
            "%s : %.2f%%".format(it.clazz, it.prob)
        }
        binding.predictionText?.text = text
        binding.predictionCard?.visibility = View.VISIBLE
    }

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

    /* ---------- test-upload helper (assets) ---------- */
    private fun uploadFromAssets(fileName: String = "IBT_23255.jpeg") {
        lifecycleScope.launch {
            try {
                val bytes = assets.open(fileName).use { it.readBytes() }

                val part = MultipartBody.Part.createFormData(
                    /* name  = */ "file",
                    /* filename on server = */ fileName,
                    /* body = */ bytes.toRequestBody("image/jpeg".toMediaType())
                )

                val preds = ApiClient.service.predict(part)

                withContext(Dispatchers.Main) { showPredictions(preds) }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Test upload failed: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
