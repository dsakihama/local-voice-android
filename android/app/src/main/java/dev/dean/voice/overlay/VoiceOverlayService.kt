package dev.dean.voice.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.dean.voice.MainActivity
import dev.dean.voice.R
import kotlin.math.abs

/**
 * Foreground service that draws the always-on floating mic bubble.
 *
 * The bubble is a system overlay (TYPE_APPLICATION_OVERLAY, gated by
 * SYSTEM_ALERT_WINDOW) so it floats above every other app. Tapping it brings up
 * the transparent capture UI ([MainActivity] with [MainActivity.EXTRA_AUTO_RECORD]);
 * because the previously-focused field stays in a background window,
 * VoiceAccessibilityService.injectText() can still inject into it.
 *
 * Drag to reposition; release snaps to the nearest screen edge and persists.
 */
class VoiceOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        startForegroundNotification()
        addBubble()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ── Bubble ──────────────────────────────────────────────────────────────

    private fun addBubble() {
        if (bubble != null) return

        val density = resources.displayMetrics.density
        val sizePx = (BUBBLE_DP * density).toInt()
        // Center the ~24dp glyph inside the cyan core (the orb's outer 14dp is glow halo).
        val padPx = (28 * density).toInt()

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic_overlay)
            background = ContextCompat.getDrawable(this@VoiceOverlayService, R.drawable.bg_fab_orb)
            setPadding(padPx, padPx, padPx, padPx)
            elevation = 2 * density // glow carries the depth, not shadow
            contentDescription = getString(R.string.overlay_bubble_description)
        }

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, (200 * density).toInt())
        }

        view.setOnTouchListener(dragTouchListener())
        windowManager.addView(view, params)
        bubble = view
    }

    private fun dragTouchListener(): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false

        return View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragging = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(v, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToNearestEdge(v)
                        persistPosition()
                    } else {
                        v.performClick()
                        launchCapture()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToNearestEdge(v: View) {
        val screenWidth = resources.displayMetrics.widthPixels
        params.x = if (params.x + v.width / 2 < screenWidth / 2) 0 else screenWidth - v.width
        windowManager.updateViewLayout(v, params)
    }

    private fun persistPosition() {
        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
    }

    private fun launchCapture() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_AUTO_RECORD, true)
        }
        startActivity(intent)
    }

    // ── Foreground notification ────────────────────────────────────────────

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )

        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_mic_overlay)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        isRunning = false
    }

    companion object {
        /** Reflects whether the bubble is currently drawn — read by the UI toggle. */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val PREFS = "overlay_prefs"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val BUBBLE_DP = 84 // 56dp cyan core + 14dp glow halo per side
        private const val CHANNEL_ID = "voice_overlay"
        private const val NOTIF_ID = 1001

        /** Starts the bubble. No-op if the overlay permission is not granted. */
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceOverlayService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceOverlayService::class.java))
        }
    }
}
