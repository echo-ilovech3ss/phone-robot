package dev.phonerobot.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/** Viewer coordinates: -1 left/top, +1 right/bottom. Names are only local enrolled guesses. */
data class FaceObservation(val x: Float, val y: Float, val name: String?, val faceCount: Int)

class RobotVision(
    context: Context,
    private val onFace: (FaceObservation?) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val store = FaceStore(this.context)
    private val matcher = FaceMatcher()
    private val converter = CameraFrame()
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setMinFaceSize(0.15f).build())
    private var generation = 0L
    private var active = false
    private var closed = false
    private var candidate: Candidate? = null
    private var storeReady = false
    private var storeError: String? = null
    init {
        worker.execute {
            try {
                store.load()
                synchronized(lock) { storeReady = true }
            } catch (error: Exception) {
                synchronized(lock) {
                    storeError = "Face storage cannot be read; enrollment disabled: ${error.message}"
                }
            }
        }
    }
    // The fields below have a single owner: worker for inference, main for camera binding.
    private var embedder: FaceEmbedder? = null
    private var modelAttempted = false
    private var lastFrameTime = 0L
    private var lastErrorTime = 0L
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var cameraPreview: Preview? = null
    private var observedOwner: LifecycleOwner? = null
    private var observedPreview: PreviewView? = null
    private var layoutListener: android.view.View.OnLayoutChangeListener? = null
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = stop()
        override fun onDestroy(owner: LifecycleOwner) = stop()
    }

    fun start(owner: LifecycleOwner, preview: PreviewView) {
        synchronized(lock) {
            if (closed) return
            generation += 1
            active = true
            candidate = null
            val session = generation
            main.post {
                synchronized(lock) {
                    if (!isActive(session)) return@post
                    unbind()
                    observedOwner = owner
                    owner.lifecycle.addObserver(lifecycleObserver)
                    preview.scaleType = PreviewView.ScaleType.FIT_CENTER
                    worker.execute {
                        prepare(session)
                        post(session) { bind(owner, preview, session) }
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            active = false
            generation += 1
            candidate = null
            main.post { unbind() }
        }
    }

    /** The UI must obtain explicit consent before calling this method. No photograph is persisted. */
    fun enroll(name: String): Boolean = synchronized(lock) {
        val fresh = candidate ?: return false
        if (!active || closed || !storeReady || !FaceStore.validName(name) ||
            SystemClock.elapsedRealtime() - fresh.capturedAt > MAX_ENROLLMENT_AGE_MS) return false
        try {
            store.put(name, fresh.embedding)
            candidate = null
            true
        } catch (error: Exception) {
            status(generation, "Could not save enrollment: ${error.message ?: "storage error"}")
            false
        }
    }

    fun names(): List<String> = store.snapshot().keys.sorted()

    /** Throws on disk failure so the caller cannot announce a deletion that was not persisted. */
    fun forget(name: String) {
        synchronized(lock) {
            check(!closed) { "Vision is closed" }
            check(storeReady) { "Face storage is not ready" }
            store.forget(name)
            candidate = null
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            active = false
            generation += 1
            candidate = null
        }
        val cleanup = {
            unbind()
            worker.execute {
                embedder?.close()
                embedder = null
                detector.close()
            }
            worker.shutdown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup()
        } else {
            main.post(cleanup)
        }
    }

    private fun prepare(session: Long) {
        if (!isActive(session)) return
        if (!storeReady && storeError == null) {
            try {
                store.load()
                synchronized(lock) { storeReady = true }
            } catch (error: Exception) {
                storeError = "Face storage cannot be read; enrollment disabled: ${error.message}"
            }
        }
        storeError?.let { status(session, it) }
        if (!modelAttempted && isActive(session)) {
            modelAttempted = true
            try {
                embedder = FaceEmbedder(context)
            } catch (error: Exception) {
                status(session, "Face identity model unavailable. Install mobile_face_net.tflite and rebuild. Face tracking still works.")
            }
        }
        lastFrameTime = 0L
    }

    @Suppress("DEPRECATION")
    private fun bind(owner: LifecycleOwner, view: PreviewView, session: Long) {
        if (!view.isLaidOut || view.width == 0 || view.height == 0) {
            view.doOnLayout { post(session) { bind(owner, view, session) } }
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            synchronized(lock) {
            if (!isActive(session)) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                val preview = Preview.Builder().setTargetResolution(Size(640, 480)).setTargetRotation(rotation).build()
                val frames = ImageAnalysis.Builder().setTargetResolution(Size(640, 480))
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                cameraPreview = preview
                analysis = frames
                preview.setSurfaceProvider(view.surfaceProvider)
                val viewWidth = view.width
                val viewHeight = view.height
                frames.setAnalyzer(worker) { image -> analyze(image, session, viewWidth, viewHeight) }
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(frames)
                view.viewPort?.let { group.setViewPort(it) }
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, group.build())
                val listener = android.view.View.OnLayoutChangeListener { _, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom ->
                    if ((right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) &&
                        isActive(session)) start(owner, view)
                }
                observedPreview = view
                layoutListener = listener
                view.addOnLayoutChangeListener(listener)
                status(session, storeError ?: if (embedder != null) "Offline face tracking ready"
                    else "Face tracking ready; identity model missing")
            } catch (error: Exception) {
                unbind()
                status(session, "Camera unavailable: ${error.message ?: "check camera permission"}")
            }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun unbind() {
        layoutListener?.let { observedPreview?.removeOnLayoutChangeListener(it) }
        observedPreview = null
        layoutListener = null
        observedOwner?.lifecycle?.removeObserver(lifecycleObserver)
        observedOwner = null
        analysis?.clearAnalyzer()
        val cases = listOfNotNull(cameraPreview, analysis).toTypedArray()
        if (cases.isNotEmpty()) provider?.unbind(*cases)
        analysis = null
        cameraPreview = null
    }

    private fun analyze(image: ImageProxy, session: Long, viewWidth: Int, viewHeight: Int) {
        val capturedAt = SystemClock.elapsedRealtime()
        if (!isActive(session) || capturedAt - lastFrameTime < FRAME_INTERVAL_MS) {
            image.close()
            return
        }
        lastFrameTime = capturedAt
        val bitmap = try {
            converter.upright(image)
        } catch (error: Exception) {
            reportFrameError(session, error)
            return
        } finally {
            image.close()
        }
        try {
            if (!isActive(session)) return
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
            if (!isActive(session)) return
            val face = faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }
            if (face == null || faces.size > 1) {
                synchronized(lock) { candidate = null }
                post(session) { onFace(null) }
                return
            }
            val frontal = abs(face.headEulerAngleX) <= 15f && abs(face.headEulerAngleY) <= 15f &&
                abs(face.headEulerAngleZ) <= 15f
            val box = face.boundingBox
            val inside = box.left >= 0 && box.top >= 0 && box.right <= bitmap.width && box.bottom <= bitmap.height
            val embedding = if (frontal && inside && isActive(session)) embedder?.embedding(bitmap, box) else null
            synchronized(lock) {
                if (isActive(session) && faces.size == 1 && embedding != null &&
                    SystemClock.elapsedRealtime() - capturedAt <= MAX_ENROLLMENT_AGE_MS) {
                    matcher.normalized(embedding)?.let { candidate = Candidate(capturedAt, it) }
                }
            }
            val enrolled = store.snapshot()
            val name = embedding?.let { matcher.match(it, enrolled) }
            // FIT_CENTER letterboxing is included; front-camera preview is mirrored, model input is not.
            val scale = if (viewWidth > 0 && viewHeight > 0)
                min(viewWidth.toFloat() / bitmap.width, viewHeight.toFloat() / bitmap.height) else 1f
            val width = if (viewWidth > 0) viewWidth.toFloat() else bitmap.width.toFloat()
            val height = if (viewHeight > 0) viewHeight.toFloat() else bitmap.height.toFloat()
            val x = -2f * (box.exactCenterX() - bitmap.width / 2f) * scale / width
            val y = 2f * (box.exactCenterY() - bitmap.height / 2f) * scale / height
            post(session) {
                val currentName = name.takeIf { store.snapshot() === enrolled }
                onFace(FaceObservation(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f), currentName, faces.size))
            }
        } catch (error: Exception) {
            reportFrameError(session, error)
        } finally {
            bitmap.recycle()
            lastFrameTime = SystemClock.elapsedRealtime()
        }
    }

    private fun reportFrameError(session: Long, error: Exception) {
        post(session) { onFace(null) }
        val now = SystemClock.elapsedRealtime()
        if (now - lastErrorTime >= 5_000) {
            lastErrorTime = now
            status(session, "Face analysis failed: ${error.message ?: "camera frame error"}")
        }
    }

    private fun isActive(session: Long): Boolean = synchronized(lock) { active && !closed && generation == session }

    private fun status(session: Long, message: String) = post(session) { onStatus(message) }

    private fun post(session: Long, action: () -> Unit) {
        main.post { synchronized(lock) { if (isActive(session)) action() } }
    }

    private data class Candidate(val capturedAt: Long, val embedding: FloatArray)
    private companion object {
        const val FRAME_INTERVAL_MS = 200L
        const val MAX_ENROLLMENT_AGE_MS = 750L
    }
}
