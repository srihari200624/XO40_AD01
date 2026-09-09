package dev.onlookermonitor.app

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.onlookermonitor.app.core.MonitorPreferences
import dev.onlookermonitor.app.core.MonitorState
import dev.onlookermonitor.app.databinding.ActivityMainBinding
import dev.onlookermonitor.app.monitor.MonitorSnapshot
import dev.onlookermonitor.app.monitor.MonitorStatusStore
import dev.onlookermonitor.app.monitor.OnlookerMonitorService
import dev.onlookermonitor.app.overlay.PrivacyShieldMode
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshCapabilities()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.grantPermissionsButton.setOnClickListener { requestRuntimePermissions() }
        binding.overlaySettingsButton.setOnClickListener { openOverlaySettings() }
        binding.armButton.setOnClickListener { armProtection() }
        binding.stopButton.setOnClickListener { OnlookerMonitorService.stop(this) }

        val currentMode = MonitorPreferences.getShieldMode(this)
        if (currentMode == PrivacyShieldMode.POPUP_ALERT) {
            binding.modePopupAlert.isChecked = true
        } else {
            binding.modeBlackScreen.isChecked = true
        }

        binding.responseModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.mode_popup_alert) {
                PrivacyShieldMode.POPUP_ALERT
            } else {
                PrivacyShieldMode.BLACK_SCREEN
            }
            MonitorPreferences.setShieldMode(this, newMode)
            OnlookerMonitorService.updateResponseMode(this, newMode)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MonitorStatusStore.status.collect(::renderStatus)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCapabilities()
    }

    private fun requestRuntimePermissions() {
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray(),
        )
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri(),
            ),
        )
    }

    private fun armProtection() {
        val missing = buildList {
            if (!hasPermission(Manifest.permission.CAMERA)) add(getString(R.string.camera_permission_name))
            if (!hasNotificationPermission()) {
                add(getString(R.string.notification_permission_name))
            }
            if (!Settings.canDrawOverlays(this@MainActivity)) add(getString(R.string.overlay_permission_name))
        }
        if (missing.isNotEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.permissions_missing, missing.joinToString()),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        try {
            OnlookerMonitorService.start(this)
        } catch (_: ForegroundServiceStartNotAllowedException) {
            Toast.makeText(this, R.string.arm_while_visible_error, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.permission_changed_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshCapabilities() {
        val cameraReady = hasPermission(Manifest.permission.CAMERA)
        val notificationReady = hasNotificationPermission()
        val overlayReady = Settings.canDrawOverlays(this)
        updateBadge(binding.cameraPermissionStatus, cameraReady)
        updateBadge(binding.notificationPermissionStatus, notificationReady)
        updateBadge(binding.overlayPermissionStatus, overlayReady)
        binding.armButton.isEnabled = cameraReady && notificationReady && overlayReady
        binding.grantPermissionsButton.isEnabled = !cameraReady || !notificationReady
        binding.overlaySettingsButton.isEnabled = !overlayReady
    }

    private fun updateBadge(view: android.widget.TextView, granted: Boolean) {
        view.text = capabilityText(granted)
        if (granted) {
            view.setBackgroundResource(R.drawable.badge_ready)
            view.setTextColor(ContextCompat.getColor(this, R.color.badge_ready_text))
        } else {
            view.setBackgroundResource(R.drawable.badge_needed)
            view.setTextColor(ContextCompat.getColor(this, R.color.badge_needed_text))
        }
    }

    private fun renderStatus(snapshot: MonitorSnapshot) {
        binding.monitorState.text = snapshot.state.displayName()
        val stateColor = when (snapshot.state) {
            MonitorState.ACTIVE -> R.color.primary
            MonitorState.SHIELD_ACTIVE -> R.color.primary_dark
            MonitorState.CANDIDATE_DETECTED -> R.color.primary
            MonitorState.DEGRADED -> R.color.primary_dark
            else -> R.color.primary_dark
        }
        binding.monitorState.setTextColor(ContextCompat.getColor(this, stateColor))
        binding.monitorMessage.text = snapshot.message
        binding.faceCount.text = resources.getQuantityString(
            R.plurals.face_count,
            snapshot.visibleFaces,
            snapshot.visibleFaces,
        )
        binding.performance.text = if (snapshot.analyzedFrames == 0L) {
            getString(R.string.no_measurements)
        } else {
            getString(
                R.string.performance_summary,
                snapshot.processingMillis,
                snapshot.analyzedFrames,
                snapshot.skippedFrames,
            )
        }
        val running = snapshot.serviceRunning
        binding.stopButton.isEnabled = running
        binding.armButton.isEnabled = !running && capabilitiesReady()
    }

    private fun capabilitiesReady(): Boolean =
        hasPermission(Manifest.permission.CAMERA) &&
            hasNotificationPermission() &&
            Settings.canDrawOverlays(this)

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun capabilityText(granted: Boolean): String = getString(
        if (granted) R.string.permission_ready else R.string.permission_needed,
    )

    private fun MonitorState.displayName(): String = when (this) {
        MonitorState.DISARMED -> getString(R.string.state_disarmed)
        MonitorState.STARTING -> getString(R.string.state_starting)
        MonitorState.ACTIVE -> getString(R.string.state_active)
        MonitorState.CANDIDATE_DETECTED -> getString(R.string.state_candidate)
        MonitorState.SHIELD_ACTIVE -> getString(R.string.state_shield)
        MonitorState.DEGRADED -> getString(R.string.state_degraded)
    }
}
