package dev.onlookermonitor.app.monitor

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import dev.onlookermonitor.app.camera.FaceFrameAnalyzer
import dev.onlookermonitor.app.camera.FrameAnalysis
import dev.onlookermonitor.app.core.LightingCondition
import dev.onlookermonitor.app.core.MonitorConfig
import dev.onlookermonitor.app.core.MonitorPreferences
import dev.onlookermonitor.app.core.MonitorState
import dev.onlookermonitor.app.overlay.PrivacyShieldController
import dev.onlookermonitor.app.overlay.PrivacyShieldMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OnlookerMonitorService : LifecycleService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val config = MonitorConfig()
    private val running = AtomicBoolean(false)
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var notifications: MonitorNotifications
    private lateinit var shield: PrivacyShieldController
    private lateinit var cameraManager: CameraManager
    private lateinit var displayManager: DisplayManager
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var analyzer: FaceFrameAnalyzer? = null
    private var lastState = MonitorState.DISARMED
    private var inferenceFailures = 0
    private var userRequestedStop = false
    private var bindRetryCount = 0
    private var bindRetryScheduled = false
    private var cameraBindInProgress = false
    private var cameraOpen = false
    private var foregroundStarted = false
    private var cameraOpenedMillis: Long? = null
    private var lastSuccessfulAnalysisMillis: Long? = null
    // Per-app gating state.
    private var foregroundWatcher: ForegroundAppWatcher? = null
    private var cameraDesired = false
    private var perAppGatingUnavailable = false
    private var lastForegroundPackage: String? = null
    private var bindTriggerPackage: String? = null
    private var bindRequestedElapsed = 0L
    private val awaitingFirstFrame = AtomicBoolean(false)
    private var pendingUnbindScheduled = false
    private var shieldSuppressedUntilMillis = 0L
    private var lastFaceMeteringMillis = 0L
    @Volatile
    private var currentTargetRotation: Int = Surface.ROTATION_0

    private val bindRetry = Runnable {
        bindRetryScheduled = false
        if (running.get() && cameraDesired && !cameraOpen) bindCamera()
    }

    // Hysteresis: after leaving a protected app we hold the camera bound for a cooldown before
    // actually unbinding, so rapid switching (and protected->protected hops) don't thrash it.
    private val pendingUnbind = Runnable {
        pendingUnbindScheduled = false
        if (!running.get()) return@Runnable
        val pkg = lastForegroundPackage
        // A protected app may have returned to the foreground during the cooldown; if so, keep it.
        if (pkg != null && MonitorPreferences.shouldMonitorApp(this, pkg)) return@Runnable
        unbindCameraForStandby()
    }

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (
                running.get() &&
                !cameraOpen &&
                isFrontCamera(cameraId) &&
                lastState == MonitorState.DEGRADED
            ) {
                scheduleBindRetry("Front camera is available again; reconnecting", immediate = true)
            }
        }
    }

    private val healthCheck = object : Runnable {
        override fun run() {
            if (!running.get()) return
            checkRuntimeHealth()
            if (running.get()) mainHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MILLIS)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
                updateTargetRotation(display.rotation)
            }
        }
    }

    private val orientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                updateTargetRotation(rotation)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "onlooker-analysis").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        notifications = MonitorNotifications(this).also { it.createChannels() }
        shield = PrivacyShieldController(this, ::dismissShieldTemporarily).apply {
            mode = MonitorPreferences.getShieldMode(this@OnlookerMonitorService)
        }
        cameraManager = getSystemService(CameraManager::class.java)
        cameraManager.registerAvailabilityCallback(mainExecutor, cameraAvailabilityCallback)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, mainHandler)
        val initialDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (initialDisplay != null) {
            currentTargetRotation = initialDisplay.rotation
        }
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
    }

    private fun updateTargetRotation(newRotation: Int) {
        if (currentTargetRotation == newRotation) return
        currentTargetRotation = newRotation
        mainHandler.post {
            imageAnalysis?.targetRotation = newRotation
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            userRequestedStop = true
            stopMonitoring()
            return Service.START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_RESPONSE_MODE) {
            val modeName = intent.getStringExtra(EXTRA_RESPONSE_MODE)
            val mode = runCatching { PrivacyShieldMode.valueOf(modeName ?: "") }
                .getOrDefault(PrivacyShieldMode.BLACK_SCREEN)
            shield.mode = mode
            return Service.START_NOT_STICKY
        }
        startMonitoring()
        return Service.START_NOT_STICKY
    }

    private fun startMonitoring() {
        if (!running.compareAndSet(false, true)) return
        userRequestedStop = false

        if (!hasCameraPermission()) {
            publishDegraded("Camera permission is unavailable")
            running.set(false)
            stopSelf()
            return
        }
        if (!hasNotificationPermission()) {
            publishDegraded("Notification access is unavailable")
            running.set(false)
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            publishDegraded("Display-over-other-apps access is unavailable")
            running.set(false)
            stopSelf()
            return
        }

        publish(MonitorState.STARTING, "Starting the front camera")
        try {
            ServiceCompat.startForeground(
                this,
                MonitorNotifications.FOREGROUND_NOTIFICATION_ID,
                notifications.foreground(MonitorState.STARTING, "Starting the front camera"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
            foregroundStarted = true
        } catch (error: SecurityException) {
            publishDegraded("Android refused camera foreground-service access")
            running.set(false)
            stopSelf()
            return
        } catch (error: ForegroundServiceStartNotAllowedException) {
            publishDegraded("Monitoring must be armed while the app is visible")
            running.set(false)
            stopSelf()
            return
        }
        mainHandler.post(healthCheck)
        startForegroundGating()
    }

    /**
     * Decide how the camera is driven: gated by the foreground app when Usage Access is granted, or
     * always-on fallback when it is not. When gating is active the camera starts OFF (STANDBY) and
     * the watcher's first poll drives the initial bind/standby decision.
     */
    private fun startForegroundGating() {
        val watcher = ForegroundAppWatcher(this, onForegroundPackage = ::onForegroundPackage)
        foregroundWatcher = watcher
        if (watcher.start()) {
            perAppGatingUnavailable = false
            cameraDesired = false
            publish(MonitorState.STANDBY, "Waiting for a protected app to open")
        } else {
            // Usage Access not granted -> fall back to today's always-on behaviour, surfaced via
            // the perAppGatingUnavailable flag rather than stopping.
            perAppGatingUnavailable = true
            cameraDesired = true
            publish(MonitorState.STARTING, "Usage Access off; monitoring every app")
            bindCamera()
        }
    }

    private fun onForegroundPackage(pkg: String) {
        if (!running.get()) return
        lastForegroundPackage = pkg
        if (MonitorPreferences.shouldMonitorApp(this, pkg)) {
            // Protected app: cancel any pending cooldown unbind and ensure the camera is bound.
            cancelPendingUnbind()
            if (!cameraDesired) {
                cameraDesired = true
                bindTriggerPackage = pkg
                bindCamera()
            }
        } else {
            // Unprotected app: hold the camera for the cooldown window before unbinding.
            if (cameraDesired) {
                schedulePendingUnbind()
            } else {
                publish(MonitorState.STANDBY, "Camera off; this app isn't protected")
            }
        }
    }

    private fun schedulePendingUnbind() {
        if (pendingUnbindScheduled) return
        pendingUnbindScheduled = true
        mainHandler.postDelayed(pendingUnbind, PROTECTED_APP_UNBIND_COOLDOWN_MILLIS)
    }

    private fun cancelPendingUnbind() {
        if (!pendingUnbindScheduled) return
        pendingUnbindScheduled = false
        mainHandler.removeCallbacks(pendingUnbind)
    }

    private fun unbindCameraForStandby() {
        cameraDesired = false
        mainHandler.removeCallbacks(bindRetry)
        bindRetryScheduled = false
        awaitingFirstFrame.set(false)
        // Remove observers first so the resulting CLOSED callback isn't mistaken for a fault.
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        val result = runCatching { cameraProvider?.unbindAll() }
        camera = null
        cameraOpen = false
        cameraOpenedMillis = null
        imageAnalysis = null
        analyzer?.close()
        analyzer = null
        logTransition("UNBIND", lastForegroundPackage, result.isSuccess, result.exceptionOrNull())
        publish(MonitorState.STANDBY, "Camera off; this app isn't protected")
    }

    private fun logTransition(action: String, pkg: String?, ok: Boolean, error: Throwable? = null) {
        val outcome = if (ok) "OK" else "FAILED: ${error?.javaClass?.simpleName}: ${error?.safeMessage()}"
        Log.i(TAG, "$action pkg=$pkg $outcome")
    }

    private fun bindCamera() {
        if (!running.get() || cameraOpen || cameraBindInProgress) return
        cameraBindInProgress = true
        bindRequestedElapsed = SystemClock.elapsedRealtime()
        awaitingFirstFrame.set(true)
        Log.i(TAG, "bindCamera() initiated at t=$bindRequestedElapsed")
        val providerFuture = runCatching { ProcessCameraProvider.getInstance(this) }
            .getOrElse { error ->
                cameraBindInProgress = false
                Log.e(TAG, "ProcessCameraProvider.getInstance failed", error)
                scheduleBindRetry(bindErrorMessage(error))
                return
            }
        providerFuture.addListener(
            {
                cameraBindInProgress = false
                if (!running.get()) return@addListener
                runCatching {
                    val provider = providerFuture.get()
                    camera?.cameraInfo?.cameraState?.removeObservers(this)
                    provider.unbindAll()
                    camera = null
                    cameraOpen = false
                    lastFaceMeteringMillis = 0L
                    analyzer?.close()

                    val newAnalyzer = FaceFrameAnalyzer(
                        context = this@OnlookerMonitorService,
                        executor = analysisExecutor,
                        config = config,
                        onResult = ::onFrameResult,
                        onFailure = ::onInferenceFailure,
                    )
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(resolutionSelector)
                        .setTargetRotation(currentTargetRotation)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, newAnalyzer) }
                    this@OnlookerMonitorService.imageAnalysis = imageAnalysis
                    cameraProvider = provider
                    analyzer = newAnalyzer
                    Log.i(TAG, "Binding DEFAULT_FRONT_CAMERA to lifecycle")
                    camera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        imageAnalysis,
                    ).also { boundCamera -> observeCameraState(boundCamera) }
                    bindRetryCount = 0
                    bindRetryScheduled = false
                }.onSuccess {
                    logTransition("BIND", bindTriggerPackage, true)
                }.onFailure { error ->
                    this@OnlookerMonitorService.imageAnalysis = null
                    analyzer?.close()
                    analyzer = null
                    awaitingFirstFrame.set(false)
                    Log.e(TAG, "BIND FAILED", error)
                    logTransition("BIND", bindTriggerPackage, false, error)
                    scheduleBindRetry(bindErrorMessage(error))
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun observeCameraState(boundCamera: Camera) {
        boundCamera.cameraInfo.cameraState.observe(this) { state ->
            Log.i(TAG, "CameraState transition: type=${state.type}, error=${state.error?.code}")
            if (!running.get() || !cameraDesired) return@observe
            when (state.type) {
                CameraState.Type.OPEN -> {
                    cameraOpen = true
                    bindRetryCount = 0
                    bindRetryScheduled = false
                    cameraOpenedMillis = SystemClock.elapsedRealtime()
                    lastSuccessfulAnalysisMillis = null
                    publish(MonitorState.STARTING, "Front camera open; waiting for face analysis")
                }
                CameraState.Type.PENDING_OPEN, CameraState.Type.OPENING -> {
                    cameraOpen = false
                    cameraOpenedMillis = null
                    if (state.error != null) {
                        publishDegraded(cameraErrorMessage(state.error!!))
                    } else {
                        publish(MonitorState.STARTING, "Opening front camera")
                    }
                }
                CameraState.Type.CLOSING, CameraState.Type.CLOSED -> {
                    cameraOpen = false
                    cameraOpenedMillis = null
                    if (cameraDesired && state.error != null) {
                        publishDegraded(cameraErrorMessage(state.error!!))
                    }
                }
            }
            if (cameraDesired && state.error != null) {
                Log.w(TAG, "Camera error detected (code ${state.error?.code}): scheduling bind retry")
                scheduleBindRetry(cameraErrorMessage(state.error!!))
            }
        }
    }

    private fun onFrameResult(frame: FrameAnalysis) {
        mainHandler.post {
            if (!running.get()) return@post
            if (awaitingFirstFrame.compareAndSet(true, false)) {
                val latency = SystemClock.elapsedRealtime() - bindRequestedElapsed
                Log.i(TAG, "FIRST_FRAME pkg=$bindTriggerPackage bind->firstFrame=${latency}ms")
            }
            lastSuccessfulAnalysisMillis = SystemClock.elapsedRealtime()
            if (!hasCameraPermission()) {
                publishDegraded("Camera permission was revoked")
                return@post
            }
            if (!Settings.canDrawOverlays(this)) {
                shield.hide()
                publishDegraded("Display-over-other-apps access was revoked")
                return@post
            }
            if (!hasNotificationPermission()) {
                publishDegraded("Notification access was revoked; alert fallback is unavailable")
                return@post
            }
            inferenceFailures = 0
            if (frame.onlookerCrop != null) {
                shield.onlookerBitmap = frame.onlookerCrop
            }
            val decision = frame.decision
            val nowMillis = SystemClock.elapsedRealtime()
            val shieldSuppressed = nowMillis < shieldSuppressedUntilMillis
            when (decision.state) {
                MonitorState.SHIELD_ACTIVE -> {
                    if (!shieldSuppressed) {
                        val requestTime = SystemClock.elapsedRealtime()
                        Log.i(TAG, "OVERLAY_REQUESTED at t=$requestTime")
                        val success = activateShield()
                        val visibleTime = SystemClock.elapsedRealtime()
                        if (success) {
                            val secondFaceTime = frame.secondFaceDetectedMillis
                            if (secondFaceTime != null) {
                                val totalLatency = visibleTime - secondFaceTime
                                val requestToVisible = visibleTime - requestTime
                                Log.i(
                                    TAG,
                                    "LATENCY_TIMESTAMPS: secondFaceDetected=$secondFaceTime, " +
                                        "overlayRequested=$requestTime, overlayVisible=$visibleTime | " +
                                        "totalLatency=${totalLatency}ms (requestToVisible=${requestToVisible}ms)",
                                )
                            }
                        } else {
                            return@post
                        }
                    }
                }
                MonitorState.ACTIVE -> deactivateShield()
                else -> Unit
            }
            val effectiveState = if (decision.state == MonitorState.SHIELD_ACTIVE && shieldSuppressed) {
                MonitorState.CANDIDATE_DETECTED
            } else {
                decision.state
            }
            meterOnFaceIfBacklit(frame)
            val usableFaceCount = decision.faces.count { it.usable }
            val message = when {
                shieldSuppressed && decision.state == MonitorState.SHIELD_ACTIVE ->
                    "Shield dismissed; rearming in 1 second"
                effectiveState == MonitorState.SHIELD_ACTIVE -> "Privacy shield active"
                // Darkness produces phantom candidates, so say why rather than reporting
                // detections the app has already decided it cannot trust.
                decision.lighting == LightingCondition.TOO_DARK ->
                    activeMessage(decision.lighting, usableFaceCount)
                effectiveState == MonitorState.CANDIDATE_DETECTED ->
                    "Checking ${decision.candidateTrackIds.size} additional face(s)"
                effectiveState == MonitorState.ACTIVE ->
                    activeMessage(decision.lighting, usableFaceCount)
                else -> decision.reason
            }
            publish(
                state = effectiveState,
                message = message,
                visibleFaces = usableFaceCount,
                candidates = decision.candidateTrackIds.size,
                processingMillis = frame.processingMillis,
                analyzedFrames = frame.analyzedFrames,
                skippedFrames = frame.skippedFrames,
                lighting = decision.lighting,
                meanLuma = frame.quality.meanLuma,
                contrast = frame.quality.contrast,
                motionScore = frame.quality.motionScore,
            )
        }
    }

    /**
     * When the scene is strongly backlit the camera exposes for the background and the user's
     * face collapses into a silhouette. Ask CameraX to meter exposure on the face instead.
     * Throttled, because auto-exposure needs time to converge between requests.
     */
    private fun meterOnFaceIfBacklit(frame: FrameAnalysis) {
        if (frame.quality.lighting != LightingCondition.BACKLIT) return
        val center = frame.primaryFaceCenter ?: return
        val cameraControl = camera?.cameraControl ?: return
        val nowMillis = SystemClock.elapsedRealtime()
        if (nowMillis - lastFaceMeteringMillis < FACE_METERING_INTERVAL_MILLIS) return
        lastFaceMeteringMillis = nowMillis
        runCatching {
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(center.x, center.y)
            cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(FACE_METERING_CANCEL_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }

    private fun activeMessage(lighting: LightingCondition, usableFaceCount: Int): String =
        when (lighting) {
            LightingCondition.TOO_DARK -> "Too dark to monitor reliably"
            LightingCondition.LOW_LIGHT -> "Low light; reduced detection confidence"
            LightingCondition.BACKLIT -> "Strong backlight; reduced detection confidence"
            else -> "Monitoring; $usableFaceCount usable face(s) visible"
        }

    private fun dismissShieldTemporarily() {
        shieldSuppressedUntilMillis = SystemClock.elapsedRealtime() + SHIELD_DISMISS_COOLDOWN_MILLIS
        shield.hide()
        notifications.clearAlert()
        val current = MonitorStatusStore.status.value
        publish(
            state = MonitorState.CANDIDATE_DETECTED,
            message = "Shield dismissed; rearming in 1 second",
            visibleFaces = current.visibleFaces,
            candidates = current.candidateFaces,
            processingMillis = current.processingMillis,
            analyzedFrames = current.analyzedFrames,
            skippedFrames = current.skippedFrames,
            lighting = current.lighting,
        )
    }

    private fun onInferenceFailure(error: Throwable) {
        mainHandler.post {
            if (!running.get()) return@post
            inferenceFailures += 1
            if (inferenceFailures >= MAX_CONSECUTIVE_INFERENCE_FAILURES) {
                publishDegraded("Face analysis repeatedly failed: ${error.safeMessage()}")
            }
        }
    }

    private fun activateShield(): Boolean {
        val result = shield.show()
        result.onFailure { error ->
            shield.hide()
            publishDegraded("Privacy overlay failed: ${error.safeMessage()}")
        }
        if (result.isFailure) return false
        if (lastState != MonitorState.SHIELD_ACTIVE) {
            notifications.showAlert()
            vibrateAlert()
        }
        return true
    }

    private fun deactivateShield() {
        if (lastState != MonitorState.SHIELD_ACTIVE) return
        shield.hide()
        notifications.clearAlert()
    }

    private fun scheduleBindRetry(message: String, immediate: Boolean = false) {
        // Never retry (or report degraded) while the camera is intentionally off in STANDBY.
        if (!cameraDesired) return
        publishDegraded(message)
        if (!running.get() || cameraOpen) return
        if (immediate) {
            mainHandler.removeCallbacks(bindRetry)
            bindRetryScheduled = true
            mainHandler.postDelayed(bindRetry, CAMERA_AVAILABLE_RETRY_MILLIS)
            return
        }
        if (bindRetryScheduled) return
        bindRetryScheduled = true
        bindRetryCount += 1
        val delayMillis = (2_000L shl (bindRetryCount - 1).coerceAtMost(3)).coerceAtMost(30_000L)
        mainHandler.postDelayed(bindRetry, delayMillis)
    }

    private fun bindErrorMessage(error: Throwable): String = if (hasSystemFrontCamera()) {
        "Front camera is temporarily unavailable: ${error.safeMessage()}"
    } else {
        "Android is not currently reporting a front camera"
    }

    private fun hasSystemFrontCamera(): Boolean = runCatching {
        cameraManager.cameraIdList.any(::isFrontCamera)
    }.getOrDefault(false)

    private fun isFrontCamera(cameraId: String): Boolean = runCatching {
        cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
    }.getOrDefault(false)

    private fun checkRuntimeHealth() {
        when {
            !hasCameraPermission() -> publishDegraded("Camera permission was revoked")
            !Settings.canDrawOverlays(this) -> {
                shield.hide(remove = true)
                publishDegraded("Display-over-other-apps access was revoked")
            }
            !hasNotificationPermission() -> publishDegraded(
                "Notification access was revoked; alert fallback is unavailable",
            )
            cameraDesired && cameraOpenedMillis != null -> {
                val nowMillis = SystemClock.elapsedRealtime()
                val lastHealthyMillis = lastSuccessfulAnalysisMillis ?: cameraOpenedMillis!!
                if (nowMillis - lastHealthyMillis > FRAME_WATCHDOG_MILLIS) {
                    Log.w(TAG, "FRAME WATCHDOG TRIGGERED: no frames received for ${nowMillis - lastHealthyMillis}ms. Initiating camera rebind recovery.")
                    publishDegraded("Front camera frames stopped; recovering camera connection")
                    runCatching {
                        camera?.cameraInfo?.cameraState?.removeObservers(this)
                        cameraProvider?.unbindAll()
                    }
                    camera = null
                    cameraOpen = false
                    cameraOpenedMillis = null
                    scheduleBindRetry("Frame watchdog recovery", immediate = true)
                }
            }
        }
    }

    private fun stopMonitoring() {
        running.set(false)
        foregroundWatcher?.stop()
        foregroundWatcher = null
        cancelPendingUnbind()
        cameraDesired = false
        shieldSuppressedUntilMillis = 0L
        lastFaceMeteringMillis = 0L
        mainHandler.removeCallbacksAndMessages(null)
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        cameraProvider?.unbindAll()
        camera = null
        cameraOpen = false
        cameraBindInProgress = false
        imageAnalysis = null
        analyzer?.close()
        analyzer = null
        shield.hide(remove = true)
        notifications.clearAlert()
        notifications.clearDegradedAlert()
        MonitorStatusStore.publish(MonitorSnapshot())
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun publishDegraded(message: String) {
        publish(MonitorState.DEGRADED, message)
    }

    private fun publish(
        state: MonitorState,
        message: String,
        visibleFaces: Int = 0,
        candidates: Int = 0,
        processingMillis: Long = 0,
        analyzedFrames: Long = 0,
        skippedFrames: Long = 0,
        lighting: LightingCondition = LightingCondition.UNKNOWN,
        meanLuma: Float = 0f,
        contrast: Float = 0f,
        motionScore: Float = 0f,
    ) {
        MonitorStatusStore.publish(
            MonitorSnapshot(
                state = state,
                message = message,
                serviceRunning = running.get(),
                visibleFaces = visibleFaces,
                candidateFaces = candidates,
                processingMillis = processingMillis,
                analyzedFrames = analyzedFrames,
                skippedFrames = skippedFrames,
                lighting = lighting,
                meanLuma = meanLuma,
                contrast = contrast,
                motionScore = motionScore,
                perAppGatingUnavailable = perAppGatingUnavailable,
            ),
        )
        if (foregroundStarted && running.get() && state != lastState) {
            val notification = notifications.foreground(state, message)
            runCatching {
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(MonitorNotifications.FOREGROUND_NOTIFICATION_ID, notification)
            }
        }
        if (state == MonitorState.DEGRADED && lastState != MonitorState.DEGRADED) {
            notifications.showDegradedAlert(message)
            vibrateAlert()
        } else if (state != MonitorState.DEGRADED && lastState == MonitorState.DEGRADED) {
            notifications.clearDegradedAlert()
        }
        lastState = state
    }

    private fun vibrateAlert() {
        getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 180), -1),
        )
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun cameraErrorMessage(error: CameraState.StateError): String = when (error.code) {
        CameraState.ERROR_CAMERA_IN_USE -> "Front camera is being used by another app"
        CameraState.ERROR_MAX_CAMERAS_IN_USE -> "No camera slot is currently available"
        CameraState.ERROR_CAMERA_DISABLED -> "Camera access is disabled by device policy"
        CameraState.ERROR_CAMERA_FATAL_ERROR -> "The camera reported a fatal hardware error"
        CameraState.ERROR_STREAM_CONFIG -> "The camera rejected the analysis configuration"
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Camera is blocked by Do Not Disturb mode"
        else -> "Camera is unavailable (error ${error.code})"
    }

    private fun Throwable.safeMessage(): String =
        message?.take(160)?.replace(Regex("[\\r\\n]+"), " ") ?: javaClass.simpleName

    override fun onDestroy() {
        running.set(false)
        foregroundWatcher?.stop()
        foregroundWatcher = null
        mainHandler.removeCallbacksAndMessages(null)
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        cameraProvider?.unbindAll()
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.disable()
        }
        displayManager.unregisterDisplayListener(displayListener)
        cameraManager.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        imageAnalysis = null
        analyzer?.close()
        shield.hide(remove = true)
        notifications.clearAlert()
        notifications.clearDegradedAlert()
        analysisExecutor.shutdown()
        if (!userRequestedStop && lastState != MonitorState.DEGRADED) {
            MonitorStatusStore.publish(
                MonitorSnapshot(MonitorState.DEGRADED, "Monitoring service stopped unexpectedly"),
            )
        } else if (!userRequestedStop) {
            MonitorStatusStore.publish(MonitorStatusStore.status.value.copy(serviceRunning = false))
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.onlookermonitor.app.action.START"
        const val ACTION_STOP = "dev.onlookermonitor.app.action.STOP"
        const val ACTION_SET_RESPONSE_MODE = "dev.onlookermonitor.app.action.SET_RESPONSE_MODE"
        const val EXTRA_RESPONSE_MODE = "dev.onlookermonitor.app.extra.RESPONSE_MODE"
        private const val MAX_CONSECUTIVE_INFERENCE_FAILURES = 3
        private const val HEALTH_CHECK_INTERVAL_MILLIS = 2_000L
        private const val FRAME_WATCHDOG_MILLIS = 5_000L
        private const val CAMERA_AVAILABLE_RETRY_MILLIS = 250L
        private const val SHIELD_DISMISS_COOLDOWN_MILLIS = 1_000L
        private const val FACE_METERING_INTERVAL_MILLIS = 2_000L
        private const val FACE_METERING_CANCEL_SECONDS = 3L
        private const val TAG = "OnlookerBindGate"

        // CALIBRATION (placeholder, NOT tuned): how long the camera stays bound after leaving a
        // protected app before it is unbound. Absorbs app-switch thrash and gives zero-gap
        // switching between two protected apps. Needs on-device measurement.
        private const val PROTECTED_APP_UNBIND_COOLDOWN_MILLIS = 1_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OnlookerMonitorService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OnlookerMonitorService::class.java).setAction(ACTION_STOP),
            )
        }

        fun updateResponseMode(context: Context, mode: PrivacyShieldMode) {
            if (MonitorStatusStore.status.value.serviceRunning) {
                context.startService(
                    Intent(context, OnlookerMonitorService::class.java)
                        .setAction(ACTION_SET_RESPONSE_MODE)
                        .putExtra(EXTRA_RESPONSE_MODE, mode.name),
                )
            }
        }
    }
}
