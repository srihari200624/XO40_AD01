package dev.onlookermonitor.app.monitor

import dev.onlookermonitor.app.core.LightingCondition
import dev.onlookermonitor.app.core.MonitorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MonitorSnapshot(
    val state: MonitorState = MonitorState.DISARMED,
    val message: String = "Protection is stopped",
    val serviceRunning: Boolean = false,
    val visibleFaces: Int = 0,
    val candidateFaces: Int = 0,
    val processingMillis: Long = 0,
    val analyzedFrames: Long = 0,
    val skippedFrames: Long = 0,
    val lighting: LightingCondition = LightingCondition.UNKNOWN,
    /**
     * True when per-app gating is unavailable because Usage Access was not granted, so the service
     * is running in always-on fallback (monitoring every app). Rides alongside [state] because the
     * fallback coexists with live ACTIVE/SHIELD detection; the UI surfaces it persistently.
     */
    val perAppGatingUnavailable: Boolean = false,
)

object MonitorStatusStore {
    private val mutableStatus = MutableStateFlow(MonitorSnapshot())
    val status: StateFlow<MonitorSnapshot> = mutableStatus.asStateFlow()

    fun publish(snapshot: MonitorSnapshot) {
        mutableStatus.value = snapshot
    }
}
