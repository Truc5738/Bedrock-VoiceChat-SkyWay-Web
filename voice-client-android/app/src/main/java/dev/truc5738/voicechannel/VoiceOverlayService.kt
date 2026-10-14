package dev.truc5738.voicechannel

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

@Suppress("DEPRECATION")
class VoiceOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: LinearLayout? = null
    private var expanded = false
    private var params: WindowManager.LayoutParams? = null
    private var statusLabel: TextView? = null
    private var themeDark = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Minecraft VoiceChat")
                .setContentText("Điều khiển nổi đang hoạt động")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Minecraft VoiceChat").setContentText("Điều khiển nổi đang hoạt động").build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (Settings.canDrawOverlays(this)) showBubble()
    }

    private fun showBubble() {
        if (bubble != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            background = GradientDrawable().apply {
                setColor(Color.rgb(0, 121, 107))
                cornerRadius = 36f
            }
        }
        val title = TextView(this).apply {
            text = "🎙 MC"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8, 5, 8, 5)
        }
        root.addView(title)
        title.setOnClickListener { toggleControls(root) }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 180 }
        root.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            override fun onTouch(v: android.view.View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startX = lp.x; startY = lp.y; moved = false
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) moved = true
                        lp.x = startX + dx; lp.y = startY + dy
                        try { windowManager?.updateViewLayout(root, lp) } catch (_: Exception) {}
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!moved) toggleControls(root)
                        return true
                    }
                }
                return false
            }
        })
        params = lp
        bubble = root
        try { windowManager?.addView(root, lp) } catch (_: Exception) { bubble = null }
    }

    private fun toggleControls(root: LinearLayout) {
        if (expanded) {
            while (root.childCount > 1) root.removeViewAt(root.childCount - 1)
            expanded = false
            return
        }
        expanded = true
        val state = TextView(this).apply { text = if (VoiceControls.connected.get()) "● Đã kết nối" else "○ Chưa kết nối"; setTextColor(Color.WHITE); textSize = 12f }
        statusLabel = state
        val mic = Button(this).apply {
            text = if (VoiceControls.microphoneMuted.get()) "Bật micro" else "Tắt micro"
            setOnClickListener {
                val next = !VoiceControls.microphoneMuted.get()
                VoiceControls.microphoneMuted.set(next)
                text = if (next) "Bật micro" else "Tắt micro"
            }
        }
        val speaker = Button(this).apply {
            text = if (VoiceControls.speakerMuted.get()) "Bật loa" else "Tắt loa"
            setOnClickListener {
                val next = !VoiceControls.speakerMuted.get()
                VoiceControls.speakerMuted.set(next)
                text = if (next) "Bật loa" else "Tắt loa"
            }
        }
        val volumeLabel = TextView(this).apply { text = "Âm lượng: ${VoiceControls.speakerVolume.get()}%"; setTextColor(Color.WHITE); textSize = 12f }
        val volumeSlider = SeekBar(this).apply {
            max = 100
            progress = VoiceControls.speakerVolume.get()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    VoiceControls.speakerVolume.set(progress)
                    volumeLabel.text = "Âm lượng: $progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val ptt = Button(this).apply {
            text = if (VoiceControls.pushToTalk.get()) "Tắt nhấn-để-nói" else "Bật nhấn-để-nói"
            setOnClickListener {
                val next = !VoiceControls.pushToTalk.get()
                VoiceControls.pushToTalk.set(next)
                text = if (next) "Tắt nhấn-để-nói" else "Bật nhấn-để-nói"
            }
        }
        val refresh = Button(this).apply { text = "Làm mới trạng thái"; setOnClickListener { statusLabel?.text = if (VoiceControls.connected.get()) "● Đã kết nối" else "○ Chưa kết nối" } }
        val theme = Button(this).apply { text = "Đổi màu bong bóng"; setOnClickListener { themeDark = !themeDark; (root.background as? GradientDrawable)?.setColor(if (themeDark) Color.rgb(45, 45, 55) else Color.rgb(0, 121, 107)); text = if (themeDark) "Dùng màu xanh" else "Dùng màu tối" } }
        val reconnect = Button(this).apply {
            text = if (VoiceControls.reconnectEnabled.get()) "Tắt tự kết nối lại" else "Bật tự kết nối lại"
            setOnClickListener {
                val next = !VoiceControls.reconnectEnabled.get()
                VoiceControls.reconnectEnabled.set(next)
                text = if (next) "Tắt tự kết nối lại" else "Bật tự kết nối lại"
            }
        }
        val open = Button(this).apply { text = "Mở ứng dụng"; setOnClickListener {
            val i = Intent(this@VoiceOverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        } }
        val hide = Button(this).apply { text = "Thu gọn"; setOnClickListener { toggleControls(root) } }
        val stop = Button(this).apply { text = "Tắt điều khiển nổi"; setOnClickListener { stopSelf() } }
        root.addView(state); root.addView(mic); root.addView(speaker); root.addView(volumeLabel); root.addView(volumeSlider); root.addView(ptt); root.addView(refresh); root.addView(theme); root.addView(reconnect); root.addView(open); root.addView(hide); root.addView(stop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Settings.canDrawOverlays(this) && bubble == null) showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        bubble?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        bubble = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Voice chat controls", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "minecraft_voice_controls"
        private const val NOTIFICATION_ID = 26468
    }
}
