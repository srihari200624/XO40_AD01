package dev.onlookermonitor.app.protectedapps

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.onlookermonitor.app.R
import dev.onlookermonitor.app.core.MonitorPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Protected Apps picker: an editable checklist of launcher apps whose selection is persisted
 * via [MonitorPreferences.setProtectedApps]. UI + storage only — nothing here touches the camera,
 * foreground-app detection, or the detection pipeline. Reachable from [dev.onlookermonitor.app
 * .MainActivity] any time, not just during onboarding.
 */
class ProtectedAppsActivity : AppCompatActivity() {

    private lateinit var iconExecutor: ExecutorService

    // The working selection. Mutated by the adapter on each toggle, then persisted. Until the first
    // toggle it mirrors the on-screen suggestions but is NOT written, so an unconfigured install
    // stays null (= always-on / fail-open) if the user just looks and leaves.
    private val checked = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_protected_apps)

        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        iconExecutor = Executors.newFixedThreadPool(2)

        val recycler = findViewById<RecyclerView>(R.id.apps_recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { loadApps() }
            val saved = MonitorPreferences.getProtectedApps(this@ProtectedAppsActivity)
            // Saved selection wins on re-entry; on first run, the heuristic is a suggestion only.
            checked.clear()
            checked.addAll(saved ?: data.preChecked)
            recycler.adapter = ProtectedAppsAdapter(
                rows = data.rows,
                checked = checked,
                packageManager = packageManager,
                iconExecutor = iconExecutor,
            ) { _, _ ->
                // Persist the full set on every toggle. The first toggle materializes the value
                // (null -> concrete), so the fail-open default no longer applies from then on.
                MonitorPreferences.setProtectedApps(this@ProtectedAppsActivity, checked)
            }
        }
    }

    private class AppData(val rows: List<AppRow>, val preChecked: Set<String>)

    private fun loadApps(): AppData {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(launcherIntent, 0)

        val rows = resolved
            .asSequence()
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != packageName } // exclude our own app
            .distinct()
            .map { pkg -> AppRow(pkg = pkg, label = labelFor(pkg)) }
            .sortedBy { it.label.lowercase() }
            .toList()

        val preChecked = rows
            .asSequence()
            .map { it.pkg }
            .filter { it in SensitiveAppCatalog.PACKAGES || requestsBiometric(it) }
            .toSet()

        return AppData(rows, preChecked)
    }

    private fun labelFor(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    /** One (noisy) pre-check signal: does the app request a biometric permission? */
    private fun requestsBiometric(pkg: String): Boolean = runCatching {
        packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions
    }.getOrNull()?.any {
        it == "android.permission.USE_BIOMETRIC" || it == "android.permission.USE_FINGERPRINT"
    } ?: false

    override fun onDestroy() {
        iconExecutor.shutdownNow()
        super.onDestroy()
    }
}
