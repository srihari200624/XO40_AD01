package dev.onlookermonitor.app.core

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.onlookermonitor.app.monitor.MonitorSnapshot
import dev.onlookermonitor.app.protectedapps.SensitiveAppCatalog

enum class ConfidentialityLevel(val label: String) {
    CRITICAL("HIGH SENSITIVITY"),
    STANDARD("STANDARD"),
}

data class ConfidentialityRating(
    val scorePercent: Int,
    val levelText: String,
    val summary: String,
    val streamQualitySummary: String,
    val processingSummary: String,
    val recommendations: List<String>,
)

object ConfidentialityEvaluator {

    fun evaluate(context: Context, snapshot: MonitorSnapshot): ConfidentialityRating {
        var score = 0
        val recommendations = mutableListOf<String>()

        // --- 1. Video Stream Quality Score (up to 45 pts) ---
        var streamPts = 0
        val lighting = snapshot.lighting
        when (lighting) {
            LightingCondition.GOOD -> {
                streamPts += 20
            }
            LightingCondition.LOW_LIGHT, LightingCondition.BACKLIT -> {
                streamPts += 10
                recommendations.add("Increase scene lighting for clearer video stream quality")
            }
            LightingCondition.TOO_DARK -> {
                streamPts += 0
                recommendations.add("Environment is too dark for video face analysis")
            }
            LightingCondition.UNKNOWN -> {
                streamPts += 8
            }
        }

        // Luma / contrast clarity
        if (snapshot.contrast >= 10f && snapshot.meanLuma in 30f..220f) {
            streamPts += 15
        } else if (snapshot.contrast > 0f) {
            streamPts += 7
        } else {
            streamPts += 3
        }

        // Camera stability (motion)
        if (snapshot.motionScore < 12f) {
            streamPts += 10
        } else {
            streamPts += 3
            recommendations.add("Hold the camera steady to prevent motion blur")
        }

        score += streamPts

        // --- 2. Processing & Inference Performance Score (up to 35 pts) ---
        var procPts = 0
        val procMs = snapshot.processingMillis
        when {
            procMs in 1..45 -> procPts += 15
            procMs in 46..100 -> procPts += 10
            procMs > 100 -> {
                procPts += 4
                recommendations.add("Frame processing latency is elevated (${procMs}ms)")
            }
            else -> procPts += 8
        }

        // Frame pipeline skip ratio
        val totalFrames = snapshot.analyzedFrames + snapshot.skippedFrames
        val skipRatio = if (totalFrames > 0) snapshot.skippedFrames.toFloat() / totalFrames else 0f
        when {
            skipRatio < 0.15f -> procPts += 10
            skipRatio < 0.40f -> procPts += 5
            else -> {
                procPts += 2
                recommendations.add("Frame pipeline is skipping frames; reduce background app load")
            }
        }

        // Monitor state
        when (snapshot.state) {
            MonitorState.ACTIVE, MonitorState.CANDIDATE_DETECTED, MonitorState.SHIELD_ACTIVE -> procPts += 10
            MonitorState.STANDBY -> procPts += 7
            MonitorState.STARTING -> procPts += 5
            MonitorState.DEGRADED -> {
                procPts += 2
                recommendations.add("Protection is degraded: ${snapshot.message}")
            }
            MonitorState.DISARMED -> {
                procPts += 0
                recommendations.add("Arm protection to start live video stream analysis")
            }
        }

        score += procPts

        // --- 3. Permissions & Security Access (up to 20 pts) ---
        var permPts = 0
        if (hasPermission(context, Manifest.permission.CAMERA)) permPts += 5
        if (Settings.canDrawOverlays(context)) permPts += 5
        if (hasNotificationPermission(context)) permPts += 5
        if (hasUsageAccess(context)) permPts += 5 else recommendations.add("Allow usage access for per-app confidentiality protection")

        score += permPts

        val clampedScore = score.coerceIn(0, 100)
        val levelText = when {
            clampedScore >= 85 -> "MAXIMUM CONFIDENTIALITY"
            clampedScore >= 65 -> "HIGH CONFIDENTIALITY"
            clampedScore >= 40 -> "MEDIUM CONFIDENTIALITY"
            else -> "LOW CONFIDENTIALITY"
        }

        val summary = when {
            clampedScore >= 85 -> "Excellent video stream quality and real-time processing performance."
            clampedScore >= 65 -> "Good confidentiality rating. Optimize lighting or latency to maximize score."
            clampedScore >= 40 -> "Moderate rating. Video stream or processing performance is degraded."
            else -> "Low confidentiality rating! Stream processing is inactive or degraded."
        }

        val streamQualitySummary = "Stream: ${if (lighting == LightingCondition.GOOD) "Clear Video" else lighting.name.lowercase().replace("_", " ")} · Luma ${snapshot.meanLuma.toInt()} · Motion ${snapshot.motionScore.toInt()}"
        val processingSummary = "Processing: ${if (procMs > 0) "${procMs}ms" else "Idle"} · ${snapshot.analyzedFrames} frames analyzed"

        return ConfidentialityRating(
            scorePercent = clampedScore,
            levelText = levelText,
            summary = summary,
            streamQualitySummary = streamQualitySummary,
            processingSummary = processingSummary,
            recommendations = recommendations,
        )
    }

    fun appConfidentialityLevel(pkgName: String): ConfidentialityLevel = when {
        pkgName in SensitiveAppCatalog.PACKAGES -> ConfidentialityLevel.CRITICAL
        else -> ConfidentialityLevel.STANDARD
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)

    private fun hasUsageAccess(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
