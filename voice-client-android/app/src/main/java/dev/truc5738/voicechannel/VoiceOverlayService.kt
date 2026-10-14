package dev.truc5738.voicechannel

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
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
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            statusLabel?.text = if (VoiceControls.connected.get()) "● Đã kết nối" else "○ Chưa kết nối"
            uiHandler.postDelayed(this, 1000L)
        }
    }

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

    private val bg = Color.rgb(17, 27, 21)
    private val accent = Color.rgb(86, 220, 137)
    private val mutedText = Color.rgb(171, 191, 178)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun pill(text: String, primary: Boolean = false): Button = Button(this).apply {
        this.text = text; textSize = 12f; isAllCaps = false
        setTextColor(if (primary) Color.rgb(8, 34, 19) else Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (primary) accent else Color.rgb(49, 66, 54)); cornerRadius = dp(14).toFloat()
        }
        minHeight = dp(38); elevation = 0f; stateListAnimator = null
        setPadding(dp(5), 0, dp(5), 0)
    }
    private fun showBubble() {
        if (bubble != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = GradientDrawable().apply {
                setColor(bg); cornerRadius = dp(17).toFloat(); setStroke(dp(1), Color.rgb(64, 104, 76))
            }
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = TextView(this).apply {
            text = "●"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(accent)
            background = GradientDrawable().apply { setColor(Color.rgb(34, 71, 47)); shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }
        val name = TextView(this).apply {
            text = "VC"; textSize = 10f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.08f
            setPadding(dp(5), 0, dp(4), 0)
        }
        val state = TextView(this).apply { text = "●"; textSize = 9f; setTextColor(mutedText); gravity = Gravity.CENTER; setPadding(dp(2), 0, dp(2), 0) }
        statusLabel = state
        val close = TextView(this).apply {
            text = "×"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(dp(3), 0, dp(1), 0); contentDescription = "Đóng bong bóng"
            setOnClickListener { stopSelf() }
        }
        header.addView(icon); header.addView(name, LinearLayout.LayoutParams(0, -2, 1f)); header.addView(state); header.addView(close, LinearLayout.LayoutParams(dp(25), dp(30))); root.addView(header)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(170) }
        header.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var moved = false
            override fun onTouch(v: android.view.View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = lp.x; startY = lp.y; moved = false; return true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) moved = true
                        lp.x = (startX + dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        try { windowManager?.updateViewLayout(root, lp) } catch (_: Exception) {}
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> { if (!moved) toggleControls(root); return true }
                }
                return false
            }
        })
        params = lp; bubble = root
        try { windowManager?.addView(root, lp) } catch (_: Exception) { bubble = null }
    }
    private fun toggleControls(root: LinearLayout) {
        if (expanded) { while (root.childCount > 1) root.removeViewAt(root.childCount - 1); expanded = false; return }
        expanded = true
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5), dp(5), dp(5), dp(4)) }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "ĐIỀU KHIỂN THOẠI"; textSize = 10f; setTextColor(mutedText)
            setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.07f
        }
        val status = TextView(this).apply {
            text = if (VoiceControls.connected.get()) "Đã kết nối" else "Chưa kết nối"
            textSize = 11f; setTextColor(if (VoiceControls.connected.get()) accent else mutedText); gravity = Gravity.END
        }
        statusLabel = status
        titleRow.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); titleRow.addView(status); body.addView(titleRow)
        body.addView(android.view.View(this).apply {
            setBackgroundColor(Color.rgb(54, 72, 59))
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) }
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mic = pill(if (VoiceControls.microphoneMuted.get()) "Bật mic" else "Tắt mic", !VoiceControls.microphoneMuted.get())
        val speaker = pill(if (VoiceControls.speakerMuted.get()) "Bật loa" else "Tắt loa", !VoiceControls.speakerMuted.get())
        buttons.addView(mic, LinearLayout.LayoutParams(0, dp(38), 1f)); buttons.addView(android.view.View(this), LinearLayout.LayoutParams(dp(5), 1))
        buttons.addView(speaker, LinearLayout.LayoutParams(0, dp(38), 1f)); body.addView(buttons)
        mic.setOnClickListener {
            val next = !VoiceControls.microphoneMuted.get(); VoiceControls.microphoneMuted.set(next)
            mic.text = if (next) "Bật mic" else "Tắt mic"
            mic.background = GradientDrawable().apply { setColor(if (!next) accent else Color.rgb(49,66,54)); cornerRadius = dp(14).toFloat() }
            mic.setTextColor(if (!next) Color.rgb(8,34,19) else Color.WHITE)
        }
        speaker.setOnClickListener {
            val next = !VoiceControls.speakerMuted.get(); VoiceControls.speakerMuted.set(next)
            speaker.text = if (next) "Bật loa" else "Tắt loa"
            speaker.background = GradientDrawable().apply { setColor(if (!next) accent else Color.rgb(49,66,54)); cornerRadius = dp(14).toFloat() }
            speaker.setTextColor(if (!next) Color.rgb(8,34,19) else Color.WHITE)
        }
        val volumeLabel = TextView(this).apply {
            text = "ÂM LƯỢNG  " + VoiceControls.speakerVolume.get() + "%"
            textSize = 10f; setTextColor(mutedText); setPadding(dp(2), dp(11), 0, 0)
        }
        val volume = SeekBar(this).apply {
            max = 100; progress = VoiceControls.speakerVolume.get()
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    VoiceControls.speakerVolume.set(progress); volumeLabel.text = "ÂM LƯỢNG  " + progress + "%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        body.addView(volumeLabel); body.addView(volume)
        val ptt = pill(if (VoiceControls.pushToTalk.get()) "Nhấn giữ để nói: BẬT" else "Nhấn giữ để nói: TẮT")
        val hold = pill("GIỮ ĐỂ NÓI", true).apply {
            isEnabled = VoiceControls.pushToTalk.get(); alpha = if (isEnabled) 1f else 0.5f
            setOnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { if (VoiceControls.pushToTalk.get()) VoiceControls.pushToTalkPressed.set(true); true }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { VoiceControls.pushToTalkPressed.set(false); view.performClick(); true }
                    else -> true
                }
            }
            setOnClickListener { }
        }
        ptt.setOnClickListener {
            val next = !VoiceControls.pushToTalk.get(); VoiceControls.pushToTalk.set(next)
            if (!next) VoiceControls.pushToTalkPressed.set(false)
            ptt.text = if (next) "Nhấn giữ để nói: BẬT" else "Nhấn giữ để nói: TẮT"
            hold.isEnabled = next; hold.alpha = if (next) 1f else 0.5f
        }
        body.addView(ptt, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        body.addView(hold, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(7) })
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val open = pill("Mở ứng dụng")
        open.setOnClickListener {
            val i = Intent(this@VoiceOverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        }
        val hide = pill("Thu gọn")
        hide.setOnClickListener { toggleControls(root) }
        bottom.addView(open, LinearLayout.LayoutParams(0, dp(40), 1f)); bottom.addView(android.view.View(this), LinearLayout.LayoutParams(dp(7), 1))
        bottom.addView(hide, LinearLayout.LayoutParams(0, dp(40), 1f))
        body.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        root.addView(body)
        (root.background as? GradientDrawable)?.apply { setColor(bg); setStroke(dp(1), Color.rgb(64,104,76)); cornerRadius = dp(17).toFloat() }
        uiHandler.removeCallbacks(statusTicker); uiHandler.post(statusTicker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Settings.canDrawOverlays(this) && bubble == null) showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(statusTicker)
        VoiceControls.pushToTalkPressed.set(false)
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
