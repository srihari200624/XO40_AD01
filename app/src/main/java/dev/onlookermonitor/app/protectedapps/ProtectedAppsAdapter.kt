package dev.onlookermonitor.app.protectedapps

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import dev.onlookermonitor.app.R
import dev.onlookermonitor.app.core.ConfidentialityEvaluator
import dev.onlookermonitor.app.core.ConfidentialityLevel
import java.util.concurrent.ExecutorService

/**
 * Renders the launcher-app checklist. Checked state is owned by [checked] (the activity's working
 * set); [onToggle] is fired on every user tap. Icons are loaded off the main thread on
 * [iconExecutor] and cached per package to keep scrolling smooth and avoid recycling flicker.
 */
class ProtectedAppsAdapter(
    private val rows: List<AppRow>,
    private val checked: MutableSet<String>,
    private val packageManager: PackageManager,
    private val iconExecutor: ExecutorService,
    private val onToggle: (pkg: String, isChecked: Boolean) -> Unit,
) : RecyclerView.Adapter<ProtectedAppsAdapter.RowHolder>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Drawable>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_protected_app, parent, false)
        return RowHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = rows[position]
        holder.label.text = row.label

        val sensitivityLevel = ConfidentialityEvaluator.appConfidentialityLevel(row.pkg)
        if (sensitivityLevel == ConfidentialityLevel.CRITICAL) {
            holder.sensitivityBadge.visibility = View.VISIBLE
            holder.sensitivityBadge.text = sensitivityLevel.label
            holder.sensitivityBadge.setBackgroundResource(R.drawable.badge_needed)
            holder.sensitivityBadge.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.badge_needed_text))
        } else {
            holder.sensitivityBadge.visibility = View.GONE
        }

        // Checkbox: detach the listener while setting state so recycling never fires a spurious toggle.
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = row.pkg in checked
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checked.add(row.pkg) else checked.remove(row.pkg)
            onToggle(row.pkg, isChecked)
        }
        // Whole-row tap toggles the checkbox too.
        holder.itemView.setOnClickListener { holder.checkbox.toggle() }

        loadIcon(holder, row.pkg)
    }

    private fun loadIcon(holder: RowHolder, pkg: String) {
        holder.boundPkg = pkg
        val cached = iconCache[pkg]
        if (cached != null) {
            holder.icon.setImageDrawable(cached)
            return
        }
        holder.icon.setImageDrawable(null)
        iconExecutor.execute {
            val drawable = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
            mainHandler.post {
                if (drawable != null) {
                    iconCache[pkg] = drawable
                    // Guard against the holder having been rebound to a different row.
                    if (holder.boundPkg == pkg) holder.icon.setImageDrawable(drawable)
                }
            }
        }
    }

    class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val label: TextView = view.findViewById(R.id.app_label)
        val sensitivityBadge: TextView = view.findViewById(R.id.app_sensitivity_badge)
        val checkbox: CheckBox = view.findViewById(R.id.app_checkbox)
        var boundPkg: String? = null
    }
}
