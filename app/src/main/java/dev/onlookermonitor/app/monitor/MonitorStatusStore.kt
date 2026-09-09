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
)

object MonitorStatusStore {
    private val mutableStatus = MutableStateFlow(MonitorSnapshot())
    val status: StateFlow<MonitorSnapshot> = mutableStatus.asStateFlow()

    fun publish(snapshot: MonitorSnapshot) {
        mutableStatus.value = snapshot
    }
}
