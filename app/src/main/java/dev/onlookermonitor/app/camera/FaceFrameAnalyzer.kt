package dev.onlookermonitor.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import dev.onlookermonitor.app.core.AnalysisDecision
import dev.onlookermonitor.app.core.FaceBounds
import dev.onlookermonitor.app.core.FaceObservation
import dev.onlookermonitor.app.core.FaceType
import dev.onlookermonitor.app.core.FrameQuality
import dev.onlookermonitor.app.core.LumaGrid
import dev.onlookermonitor.app.core.MonitorConfig
import dev.onlookermonitor.app.core.MonitorPreferences
import dev.onlookermonitor.app.core.MonitorState
import dev.onlookermonitor.app.core.MonitoringEngine
import dev.onlookermonitor.app.core.SensorPoint
import dev.onlookermonitor.app.core.TrackedFace
import dev.onlookermonitor.app.core.sensorPointFor
import dev.onlookermonitor.app.core.sensorX
import dev.onlookermonitor.app.core.sensorY
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class FrameAnalysis(
    val decision: AnalysisDecision,
    val processingMillis: Long,
    val analyzedFrames: Long,
    val skippedFrames: Long,
    val quality: FrameQuality,
    /** Centre of the primary face in sensor coordinates, for auto-exposure metering. */
    val primaryFaceCenter: SensorPoint?,
    /** Cropped onlooker face photo bitmap when capture is enabled and shield is active. */
    val onlookerCrop: Bitmap? = null,
)

class FaceFrameAnalyzer(
    private val context: Context,
    private val executor: Executor,
    private val config: MonitorConfig,
    private val onResult: (FrameAnalysis) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {
    private val engine = MonitoringEngine(config)
    private val detector: FaceDetector
    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private val analyzedFrames = AtomicLong(0)
    private val skippedFrames = AtomicLong(0)
    private var lastRotationDegrees: Int? = null
    private var lastCaptureMillis = 0L

    // Only touched from the single-threaded analysis executor, which runs both analyze() and
    // every detector listener.
    private var previousGrid: LumaGrid? = null

    @Volatile
    private var nextAnalysisAtMillis = 0L

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(config.minFaceWidthRatio)
            .enableTracking()
            .build()
        detector = FaceDetection.getClient(options)
    }

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val nowMillis = SystemClock.elapsedRealtime()
        if (closed.get() || nowMillis < nextAnalysisAtMillis || !inFlight.compareAndSet(false, true)) {
            skippedFrames.incrementAndGet()
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            inFlight.set(false)
            skippedFrames.incrementAndGet()
            imageProxy.close()
            return
        }

        val startedNanos = SystemClock.elapsedRealtimeNanos()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val normalizedWidth = if (rotated) imageProxy.height else imageProxy.width
        val normalizedHeight = if (rotated) imageProxy.width else imageProxy.height
        val input = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // Sample the luma plane before inference: ML Kit exposes no confidence score, so
        // brightness, contrast and frame-to-frame movement are what tell us whether the
        // detections from this frame can be trusted at all.
        val grid = sampleLumaGrid(imageProxy, rotationDegrees, normalizedWidth, normalizedHeight)
        val quality = grid?.quality(previousGrid, config) ?: FrameQuality.UNKNOWN
        previousGrid = grid

        detector.process(input)
            .addOnSuccessListener(executor) { detected ->
                if (closed.get()) return@addOnSuccessListener
                val previousRotation = lastRotationDegrees
                if (previousRotation != null && previousRotation != rotationDegrees) {
                    reset()
                }
                lastRotationDegrees = rotationDegrees
                val decision = engine.analyze(
                    detected.map { it.toObservation(normalizedWidth, normalizedHeight, grid) },
                    SystemClock.elapsedRealtime(),
                    quality,
                )
                val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000
                val analyzed = analyzedFrames.incrementAndGet()
                nextAnalysisAtMillis = SystemClock.elapsedRealtime() + decision.recommendedIntervalMillis
                val onlookerCrop = if (decision.state == MonitorState.SHIELD_ACTIVE &&
                    MonitorPreferences.isCaptureOnlookersEnabled(context)
                ) {
                    val candidateBounds = decision.faces.firstOrNull { it.trackId in decision.candidateTrackIds }?.bounds
                    OnlookerPhotoCapturer.extractOnlookerCrop(imageProxy, rotationDegrees, candidateBounds)
                } else {
                    null
                }

                onResult(
                    FrameAnalysis(
                        decision = decision,
                        processingMillis = elapsedMillis,
                        analyzedFrames = analyzed,
                        skippedFrames = skippedFrames.get(),
                        quality = quality,
                        primaryFaceCenter = decision.primaryFaceCenter(rotationDegrees),
                        onlookerCrop = onlookerCrop,
                    ),
                )
            }
            .addOnFailureListener(executor) { error ->
                if (!closed.get()) onFailure(error)
            }
            .addOnCompleteListener(executor) {
                imageProxy.close()
                inFlight.set(false)
                if (closed.get()) detector.close()
            }
    }

    fun reset() {
        lastRotationDegrees = null
        previousGrid = null
        engine.reset()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reset()
        if (!inFlight.get()) detector.close()
    }

    /**
     * Builds a coarse luma map in the rotated frame, so every [FaceBounds] lookup is a direct
     * index. Sampling is strided; at 640x480 this reads a few thousand bytes per analysed
     * frame, which is negligible next to inference.
     */
    private fun sampleLumaGrid(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        rotatedWidth: Int,
        rotatedHeight: Int,
    ): LumaGrid? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val sensorWidth = imageProxy.width
        val sensorHeight = imageProxy.height
        if (sensorWidth <= 0 || sensorHeight <= 0) return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = buffer.limit()
        val (cols, rows) = LumaGrid.dimensionsFor(rotatedWidth, rotatedHeight)
        val cells = FloatArray(cols * rows)

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var total = 0
                var count = 0
                for (subRow in 0 until SUBSAMPLES_PER_CELL) {
                    for (subCol in 0 until SUBSAMPLES_PER_CELL) {
                        val u = (col + (subCol + 0.5f) / SUBSAMPLES_PER_CELL) / cols
                        val v = (row + (subRow + 0.5f) / SUBSAMPLES_PER_CELL) / rows
                        val x = (sensorX(u, v, rotationDegrees) * sensorWidth)
                            .toInt().coerceIn(0, sensorWidth - 1)
                        val y = (sensorY(u, v, rotationDegrees) * sensorHeight)
                            .toInt().coerceIn(0, sensorHeight - 1)
                        val index = y * rowStride + x * pixelStride
                        if (index < 0 || index >= limit) continue
                        total += buffer.get(index).toInt() and 0xFF
                        count += 1
                    }
                }
                cells[row * cols + col] = if (count == 0) 0f else total.toFloat() / count
            }
        }
        return LumaGrid(cols, rows, cells)
    }

    private fun AnalysisDecision.primaryFaceCenter(rotationDegrees: Int): SensorPoint? {
        val primary = faces.firstOrNull(TrackedFace::primary) ?: return null
        return sensorPointFor(primary.bounds.centerX, primary.bounds.centerY, rotationDegrees)
    }

    private fun Face.toObservation(
        imageWidth: Int,
        imageHeight: Int,
        grid: LumaGrid?,
    ): FaceObservation {
        val width = imageWidth.toFloat().coerceAtLeast(1f)
        val height = imageHeight.toFloat().coerceAtLeast(1f)
        val bounds = FaceBounds(
            left = (boundingBox.left / width).coerceIn(0f, 1f),
            top = (boundingBox.top / height).coerceIn(0f, 1f),
            right = (boundingBox.right / width).coerceIn(0f, 1f),
            bottom = (boundingBox.bottom / height).coerceIn(0f, 1f),
        )
        val patch = grid?.statsIn(bounds)
        val isBorder = bounds.left <= 0.03f || bounds.top <= 0.03f || bounds.right >= 0.97f || bounds.bottom >= 0.97f
        val isProfile = abs(headEulerAngleY) > 35f || abs(headEulerAngleX) > 30f
        val isPartial = isBorder || isProfile
        val faceType = when {
            isProfile -> FaceType.PARTIAL_PROFILE
            isBorder -> FaceType.PARTIAL_BORDER
            else -> FaceType.FULL_FACE
        }
        return FaceObservation(
            sourceTrackingId = trackingId,
            bounds = bounds,
            yawDegrees = headEulerAngleY,
            pitchDegrees = headEulerAngleX,
            rollDegrees = headEulerAngleZ,
            patchLuma = patch?.meanLuma,
            patchContrast = patch?.contrast,
            leftEyeOpenProbability = leftEyeOpenProbability,
            rightEyeOpenProbability = rightEyeOpenProbability,
            isPartialFace = isPartial,
            faceType = faceType,
        )
    }

    private companion object {
        const val SUBSAMPLES_PER_CELL = 3
        const val CAPTURE_COOLDOWN_MILLIS = 3_000L
    }
}
