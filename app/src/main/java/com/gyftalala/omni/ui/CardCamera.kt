package com.gyftalala.omni.ui

import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera and recognizer exist only while the unlocked scanner is visible. */
class CardCamera(private val context: Context) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = ContextCompat.getMainExecutor(context)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val active = AtomicBoolean(true)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val preview = Preview.Builder().build()
    private val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    @Suppress("DEPRECATION")
    private val analysis = ImageAnalysis.Builder().setTargetResolution(Size(1280, 960))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    private var lastRead = 0L

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun start(view: PreviewView, owner: LifecycleOwner, onReady: (Boolean) -> Unit, onRead: (String) -> Unit, onError: (String) -> Unit) {
        preview.setSurfaceProvider(view.surfaceProvider)
        analysis.setAnalyzer(executor) { proxy ->
            val now = SystemClock.elapsedRealtime()
            val image = proxy.image
            if (!active.get() || image == null || now - lastRead < 450) { proxy.close(); return@setAnalyzer }
            lastRead = now
            try {
                recognizer.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener(main) { if (active.get()) onRead(it.text) }
                    .addOnFailureListener(main) { if (active.get()) onError("Live reading paused. Capture a photo to try again.") }
                    .addOnCompleteListener { proxy.close() }
            } catch (_: Exception) { proxy.close() }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (active.get()) runCatching {
                provider = future.get()
                camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
                onReady(camera!!.cameraInfo.hasFlashUnit())
            }.onFailure { onError("Camera could not start. Close the scanner and try again.") }
        }, main)
    }

    fun torch(enabled: Boolean) { camera?.cameraControl?.enableTorch(enabled) }

    fun capture(onSaved: (File) -> Unit, onError: (String) -> Unit) {
        val file = File(context.cacheDir, "captures/card-scan-${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                if (active.get()) onSaved(file) else file.delete()
            }
            override fun onError(exception: ImageCaptureException) {
                file.delete()
                if (active.get()) onError("Photo could not be captured. Hold the card steady and try again.")
            }
        })
    }

    override fun close() {
        if (!active.getAndSet(false)) return
        analysis.clearAnalyzer()
        camera?.cameraControl?.enableTorch(false)
        provider?.unbind(preview, analysis, capture)
        executor.shutdown()
        recognizer.close()
    }
}
