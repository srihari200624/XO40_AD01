package dev.onlookermonitor.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.onlookermonitor.app.R

class PrivacyShieldController(
    context: Context,
    private val onDismiss: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var shieldView: View? = null

    var onlookerBitmap: Bitmap? = null

    var mode: PrivacyShieldMode = PrivacyShieldMode.BLACK_SCREEN
        set(value) {
            if (field != value) {
                field = value
                if (shieldView != null) {
                    hide()
                    show()
                }
            }
        }

    fun show(): Result<Unit> = runCatching {
        check(Settings.canDrawOverlays(appContext)) { "Display-over-other-apps access was revoked" }
        if (shieldView != null) return@runCatching

        val gestureDetector = GestureDetector(
            appContext,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    onDismiss()
                    return true
                }
            },
        )

        when {
            onlookerBitmap != null -> showPopupAlert(gestureDetector)
            mode == PrivacyShieldMode.BLACK_SCREEN -> showBlackScreen(gestureDetector)
            mode == PrivacyShieldMode.POPUP_ALERT -> showPopupAlert(gestureDetector)
        }
    }

    fun hide() {
        val view = shieldView ?: return
        runCatching { windowManager.removeViewImmediate(view) }
        shieldView = null
        onlookerBitmap = null
    }

    private fun showBlackScreen(gestureDetector: GestureDetector) {
        val root = FrameLayout(appContext).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = true
            setOnTouchListener { view, event ->
                val handled = gestureDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                handled
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                windowInsetsController?.apply {
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsets.Type.systemBars())
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            dimAmount = 1f
            title = "Onlooker privacy shield"
        }
        windowManager.addView(root, params)
        root.windowInsetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
        shieldView = root
    }

    private fun showPopupAlert(gestureDetector: GestureDetector) {
        val root = FrameLayout(appContext).apply {
            setPadding(dpToPx(16), dpToPx(48), dpToPx(16), dpToPx(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnTouchListener { view, event ->
                val handled = gestureDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                handled
            }
        }

        val cardBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16).toFloat()
            setColor(Color.WHITE)
            setStroke(dpToPx(2f), Color.parseColor("#1D4ED8"))
        }

        val card = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            setPadding(dpToPx(18), dpToPx(16), dpToPx(18), dpToPx(16))
            elevation = dpToPx(10).toFloat()
        }

        val badgeBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(Color.parseColor("#EFF6FF"))
            setStroke(dpToPx(1f), Color.parseColor("#93C5FD"))
        }

        val badge = TextView(appContext).apply {
            text = "🛡️ PRIVACY MONITOR"
            setTextColor(Color.parseColor("#1D4ED8"))
            textSize = 11f
            background = badgeBackground
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            setTypeface(typeface, Typeface.BOLD)
        }
        val badgeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dpToPx(8)
        }
        card.addView(badge, badgeParams)

        val titleView = TextView(appContext).apply {
            text = appContext.getString(R.string.popup_alert_title)
            setTextColor(Color.parseColor("#1E3A8A"))
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(titleView)

        val message = TextView(appContext).apply {
            text = appContext.getString(R.string.popup_alert_message)
            setTextColor(Color.parseColor("#1E293B"))
            textSize = 13.5f
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }
        card.addView(message)

        val bitmap = onlookerBitmap
        if (bitmap != null) {
            val photoView = ImageView(appContext).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                val photoBackground = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12).toFloat()
                    setColor(Color.BLACK)
                    setStroke(dpToPx(1.5f), Color.parseColor("#1D4ED8"))
                }
                background = photoBackground
                clipToOutline = true
            }
            val photoParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(200),
            ).apply {
                topMargin = dpToPx(4)
                bottomMargin = dpToPx(12)
            }
            card.addView(photoView, photoParams)
        }

        val buttonBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(Color.parseColor("#1D4ED8"))
        }

        val dismissButton = TextView(appContext).apply {
            text = appContext.getString(R.string.popup_alert_dismiss)
            setTextColor(Color.WHITE)
            textSize = 13.5f
            gravity = Gravity.CENTER
            background = buttonBackground
            setPadding(dpToPx(18), dpToPx(8), dpToPx(18), dpToPx(8))
            isClickable = true
            isFocusable = true
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { onDismiss() }
        }
        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.END
        }
        card.addView(dismissButton, buttonParams)

        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Onlooker privacy alert"
        }
        windowManager.addView(root, params)
        shieldView = root
    }

    private fun dpToPx(dp: Float): Int =
        (dp * appContext.resources.displayMetrics.density).toInt()

    private fun dpToPx(dp: Int): Int = dpToPx(dp.toFloat())
}
