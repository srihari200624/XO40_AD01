package dev.onlookermonitor.app.core

import dev.onlookermonitor.app.monitor.MonitorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidentialityEvaluatorTest {

    @Test
    fun appConfidentialityLevelCategorizesSensitivePackages() {
        val upiLevel = ConfidentialityEvaluator.appConfidentialityLevel("com.google.android.apps.nbu.paisa.user")
        val standardLevel = ConfidentialityEvaluator.appConfidentialityLevel("com.example.randomapp")

        assertEquals(ConfidentialityLevel.CRITICAL, upiLevel)
        assertEquals(ConfidentialityLevel.STANDARD, standardLevel)
    }

    @Test
    fun monitorSnapshotCarriesStreamQualityFields() {
        val snapshot = MonitorSnapshot(
            lighting = LightingCondition.GOOD,
            meanLuma = 120f,
            contrast = 35f,
            motionScore = 2.5f,
            processingMillis = 28,
        )

        assertEquals(LightingCondition.GOOD, snapshot.lighting)
        assertEquals(120f, snapshot.meanLuma, 0.01f)
        assertEquals(35f, snapshot.contrast, 0.01f)
        assertEquals(2.5f, snapshot.motionScore, 0.01f)
        assertEquals(28L, snapshot.processingMillis)
    }
}
