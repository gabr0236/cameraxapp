package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
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

    // store last image for preview
    private var lastPhotoUri: Uri? = null
    private var lastAssetBitmap: Bitmap? = null

    // --------------------------------------------------------------------
    // lifecycle
    // --------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) startCamera() else requestPermissions()

        binding.imageCaptureButton?.setOnClickListener {
            // takePhoto()            // for real capture
            uploadFromAssets()        // test path
        }

        binding.predictionClose?.setOnClickListener {
            binding.predictionCard?.visibility = View.GONE
        }

        // info bubble toggle
        binding.infoButton?.setOnClickListener {
            binding.infoButton?.visibility = View.GONE
            binding.infoCloseButton?.visibility = View.VISIBLE
            binding.infoBubble?.apply {
                alpha = 0f; translationY = -32f; visibility = View.VISIBLE
                animate().alpha(1f).translationY(0f).setDuration(250L).start()
            }
        }
        binding.infoCloseButton?.setOnClickListener {
            binding.infoCloseButton?.visibility = View.GONE
            binding.infoButton?.visibility = View.VISIBLE
            binding.infoBubble?.animate()
                ?.alpha(0f)?.translationY(-32f)?.setDuration(200L)
                ?.withEndAction { binding.infoBubble?.visibility = View.GONE }?.start()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // --------------------------------------------------------------------
    // Camera-X helpers
    // --------------------------------------------------------------------
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder?.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()

            val analysis = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                    Log.d(TAG, "Average luminosity: $luma")
                })
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA,
                preview, imageCapture, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())

        val meta = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            meta
        ).build()

        capture.takePicture(
            opts,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }
                override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                    res.savedUri?.let { lifecycleScope.launch { uploadUri(it) } }
                }
            }
        )
    }

    private suspend fun uploadUri(uri: Uri) {
        try {
            lastPhotoUri = uri       // save for preview
            lastAssetBitmap = null
            val part = uri.asMultipart(contentResolver)
            val preds = ApiClient.service.predict(part)
            withContext(Dispatchers.Main) { showPredictions(preds) }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Upload failed: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // --------------------------------------------------------------------
    // Prediction UI
    // --------------------------------------------------------------------
    private fun showPredictions(preds: List<Prediction>) {
        binding.predictionCard?.visibility = View.VISIBLE

        // load preview image first
        binding.predictionImage?.let { img ->
            when {
                lastPhotoUri != null     -> img.setImageURI(lastPhotoUri)
                lastAssetBitmap != null  -> img.setImageBitmap(lastAssetBitmap)
                else                     -> img.setImageDrawable(null)
            }
        }

        // build table
        val table: TableLayout = binding.predictionTable ?: return
        while (table.childCount > 1) table.removeViewAt(1)

        preds.forEach { p ->
            val row = TableRow(this).apply { setPadding(0, 8, 0, 8) }
            fun cell(t: String) = TextView(this).apply {
                text = t; setPadding(0, 0, 24, 0)
            }
            val parts = p.clazz.split("-", limit = 2)
            val genus   = parts.getOrNull(0)?.replaceFirstChar { it.uppercase() } ?: p.clazz
            val species = parts.getOrNull(1) ?: ""
            row.addView(cell(genus))
            row.addView(cell(species))
            row.addView(cell("%.2f%%".format(p.prob)))
            table.addView(row)
        }
    }

    // --------------------------------------------------------------------
    // Bundled-JPEG test helper
    // --------------------------------------------------------------------
    private fun uploadFromAssets(fileName: String = "IBT_23255.jpeg") {
        lifecycleScope.launch {
            try {
                val bytes = assets.open(fileName).use { it.readBytes() }
                lastAssetBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                lastPhotoUri = null
                val part = MultipartBody.Part.createFormData(
                    "file", fileName, bytes.toRequestBody("image/jpeg".toMediaType())
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

    // --------------------------------------------------------------------
    // Permission helpers
    // --------------------------------------------------------------------
    private fun requestPermissions() = permLauncher.launch(REQUIRED_PERMISSIONS)
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) startCamera()
            else Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }

    // --------------------------------------------------------------------
    // Analyzer & constants
    // --------------------------------------------------------------------
    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {
        private fun ByteBuffer.toByteArray() = ByteArray(remaining()).also { get(it) }
        override fun analyze(image: ImageProxy) {
            val luma = image.planes[0].buffer.toByteArray()
                .map { it.toInt() and 0xFF }.average()
            listener(luma)
            image.close()
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
}
