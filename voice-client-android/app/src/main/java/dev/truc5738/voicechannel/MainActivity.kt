package dev.truc5738.voicechannel

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.media.*
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import android.content.pm.PackageManager

class MainActivity : Activity() {
    @Volatile private var socket: Socket? = null
    @Volatile private var wantConnection = false
    @Volatile private var connectionThread: Thread? = null
    @Volatile private var sessionToken: String? = null
    @Volatile private var roomClient: WebSocketVoiceClient? = null
    private var statusView: TextView? = null
    private val sampleRate = 16000
    private val frameBytes = sampleRate / 50 * 2
    private val magic = 0x4D564331
    private val pongType = 5

    override fun onDestroy() {
        roomClient?.disconnect()
        roomClient = null
        wantConnection = false
        connectionThread?.interrupt()
        val current = socket
        socket = null
        try { current?.close() } catch (_: Exception) {}
        connectionThread = null
        super.onDestroy()
    }

    private val bgColor = android.graphics.Color.rgb(13, 22, 18)
    private val cardColor = android.graphics.Color.rgb(24, 38, 31)
    private val green = android.graphics.Color.rgb(72, 210, 126)
    private val softText = android.graphics.Color.rgb(164, 185, 172)
    private val white = android.graphics.Color.rgb(242, 248, 244)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String, size: Float, color: Int = white, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(20).toFloat() }
    }
    private fun button(t: String, primary: Boolean = false) = Button(this).apply {
        text = t; textSize = 14f; isAllCaps = false
        setTextColor(if (primary) android.graphics.Color.rgb(9, 35, 20) else white)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (primary) green else android.graphics.Color.rgb(42, 60, 49)); cornerRadius = dp(14).toFloat()
        }
        minHeight = dp(50); elevation = 0f; stateListAnimator = null
    }
    private fun gap(h: Int = 12) = android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        val prefs = getPreferences(Context.MODE_PRIVATE)
        sessionToken = prefs.getString("session_token", null)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bgColor) }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; setPadding(dp(20), dp(18), dp(20), dp(24)) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val mark = label("◉", 28f, green, true).apply {
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.rgb(31,70,46)); cornerRadius = dp(15).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("VoiceChannel", 23f, white, true))
        brand.addView(label("VOICE CHAT  •  BEDROCK", 10f, softText, true))
        top.addView(mark)
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        val settings = button("⚙").apply { textSize = 23f }
        top.addView(settings, LinearLayout.LayoutParams(dp(52), dp(48)))
        body.addView(top); body.addView(gap(22))

        val hero = card()
        hero.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(android.graphics.Color.rgb(31,79,49), android.graphics.Color.rgb(22,43,32))
        ).apply { cornerRadius = dp(22).toFloat() }
        hero.addView(label("KẾT NỐI CÙNG ĐỒNG ĐỘI", 11f, green, true))
        hero.addView(gap(6))
        hero.addView(label("Chơi cùng nhau.\nNói chuyện tức thì.", 25f, white, true))
        hero.addView(gap(8))
        hero.addView(label("Không cần addon Minecraft. Dùng mã phòng để trò chuyện.", 13f, softText))
        body.addView(hero); body.addView(gap(16))

        val room = card()
        room.addView(label("PHÒNG THOẠI", 12f, softText, true)); room.addView(gap(8))
        val host = EditText(this).apply {
            hint = "Địa chỉ máy chủ (https://...)"; setSingleLine(true); textSize = 14f
            setTextColor(white); setHintTextColor(softText); setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(bgColor); cornerRadius = dp(12).toFloat(); setStroke(dp(1), android.graphics.Color.rgb(58,81,66)) }
            setText(prefs.getString("host", "https://minecraft-pe-voice-room.onrender.com"))
            visibility = android.view.View.GONE
        }
        val pair = EditText(this).apply {
            hint = "Danh sách phòng thoại"; setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT
            textSize = 22f; gravity = android.view.Gravity.CENTER; letterSpacing = 0.18f
            setTextColor(white); setHintTextColor(softText); setPadding(dp(12), dp(13), dp(12), dp(13))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(bgColor); cornerRadius = dp(14).toFloat(); setStroke(dp(1), android.graphics.Color.rgb(58,81,66)) }
        }
        pair.visibility = android.view.View.GONE
        room.addView(host); room.addView(pair); room.addView(gap(12))
        val statusRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val dot = label("●", 12f, softText)
        val status = label("Chưa kết nối", 12f, softText)
        statusView = status
        statusRow.addView(dot); statusRow.addView(status, LinearLayout.LayoutParams(-2,-2).apply { marginStart = dp(8) })
        room.addView(statusRow); body.addView(room); body.addView(gap(12))

        val create = button("＋   Tạo phòng mới", true)
        val join = button("Tham gia phòng")
        body.addView(create, LinearLayout.LayoutParams(-1, dp(54))); body.addView(gap(8))
        body.addView(join, LinearLayout.LayoutParams(-1, dp(54))); body.addView(gap(14))

        val controls = card()
        controls.addView(label("ĐIỀU KHIỂN NHANH", 12f, softText, true)); controls.addView(gap(10))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mic = button(if (VoiceControls.microphoneMuted.get()) "🎙  Bật mic" else "🎙  Tắt mic")
        val speaker = button(if (VoiceControls.speakerMuted.get()) "🔊  Bật loa" else "🔊  Tắt loa")
        row.addView(mic, LinearLayout.LayoutParams(0, dp(50), 1f)); row.addView(android.view.View(this), LinearLayout.LayoutParams(dp(8),1))
        row.addView(speaker, LinearLayout.LayoutParams(0, dp(50), 1f))
        controls.addView(row); controls.addView(gap(8))
        val overlay = button("▣   Bật bong bóng nổi")
        controls.addView(overlay, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(controls); body.addView(gap(12))
        val stop = button("Ngắt kết nối")
        body.addView(stop, LinearLayout.LayoutParams(-1, dp(48))); body.addView(gap(12))
        body.addView(label("MẸO  •  Cả hai người dùng cùng máy chủ và mã phòng.", 11f, softText))
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)

        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)

        fun connectRoom(makeRoom: Boolean, roomId: String? = null, password: String = "", roomName: String = "", listRooms: Boolean = false) {
            val server = host.text.toString().trim().ifBlank { "https://minecraft-pe-voice-room.onrender.com" }
            if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
                status.text = "Cấp quyền micro rồi thử lại"
                return
            }
            prefs.edit().putString("host", server).apply()
            roomClient?.disconnect()
            val client = WebSocketVoiceClient(
                onStatus = { message -> runOnUiThread {
                    statusView?.text = message
                    dot.setTextColor(if (message.contains("kết nối", true) && !message.contains("lỗi", true)) green else softText)
                } },
                onRoomCode = { roomTitle -> runOnUiThread {
                    statusView?.text = if (makeRoom) "Đã tạo: $roomTitle" else "Đã vào: $roomTitle"
                    if (makeRoom) Toast.makeText(this, "Phòng đã tạo. Bạn bè có thể tìm trong danh sách.", Toast.LENGTH_LONG).show()
                } },
                onRooms = { rooms -> runOnUiThread {
                    if (listRooms) {
                        if (rooms.isEmpty()) {
                            android.app.AlertDialog.Builder(this).setTitle("Chưa có phòng")
                                .setMessage("Chưa có ai tạo phòng. Hãy tạo phòng mới trước.")
                                .setPositiveButton("Đóng", null).show()
                        } else {
                            val labels = rooms.map { (if (it.locked) "🔒 " else "🌐 ") + it.name + "  •  " + it.members + "/8 người" }.toTypedArray()
                            android.app.AlertDialog.Builder(this).setTitle("Chọn phòng thoại")
                                .setItems(labels) { _, which ->
                                    val selected = rooms[which]
                                    fun joinRoom(secret: String) { connectRoom(false, selected.id, secret, selected.name) }
                                    if (selected.locked) {
                                        val input = EditText(this).apply {
                                            hint = "Mật khẩu phòng"; setSingleLine(true)
                                            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                                        }
                                        android.app.AlertDialog.Builder(this).setTitle(selected.name)
                                            .setMessage("Nhập mật khẩu do chủ phòng đặt.")
                                            .setView(input)
                                            .setPositiveButton("Tham gia") { _, _ -> joinRoom(input.text.toString()) }
                                            .setNegativeButton("Hủy", null).show()
                                    } else {
                                        android.app.AlertDialog.Builder(this).setTitle(selected.name)
                                            .setMessage("Phòng mở, không cần mật khẩu.")
                                            .setPositiveButton("Tham gia") { _, _ -> joinRoom("") }
                                            .setNegativeButton("Hủy", null).show()
                                    }
                                }.setNegativeButton("Đóng", null).show()
                        }
                    }
                } }
            )
            roomClient = client
            status.text = if (makeRoom) "Đang tạo phòng…" else if (listRooms) "Đang tải danh sách phòng…" else "Đang tham gia phòng…"
            dot.setTextColor(green)
            client.connect(server, roomId, password, roomName, makeRoom, listRooms)
        }
        create.setOnClickListener {
            val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
            val nameInput = EditText(this).apply { hint = "Tên phòng"; setSingleLine(true) }
            val passwordInput = EditText(this).apply {
                hint = "Mật khẩu (để trống nếu phòng mở)"; setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            fields.addView(nameInput); fields.addView(passwordInput)
            android.app.AlertDialog.Builder(this).setTitle("Tạo phòng thoại")
                .setMessage("Đặt mật khẩu nếu chỉ muốn bạn bè được phép vào.")
                .setView(fields)
                .setPositiveButton("Tạo phòng") { _, _ ->
                    connectRoom(true, password = passwordInput.text.toString(), roomName = nameInput.text.toString().trim().ifBlank { "Phòng Minecraft" })
                }
                .setNegativeButton("Hủy", null).show()
        }
        join.setOnClickListener { connectRoom(false, listRooms = true) }
        mic.setOnClickListener {
            val muted = !VoiceControls.microphoneMuted.get(); VoiceControls.microphoneMuted.set(muted)
            mic.text = if (muted) "🎙  Bật mic" else "🎙  Tắt mic"
        }
        speaker.setOnClickListener {
            val muted = !VoiceControls.speakerMuted.get(); VoiceControls.speakerMuted.set(muted)
            speaker.text = if (muted) "🔊  Bật loa" else "🔊  Tắt loa"
        }
        overlay.setOnClickListener {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                status.text = "Hãy cấp quyền hiển thị trên ứng dụng khác"
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            } else {
                val i = android.content.Intent(this, VoiceOverlayService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                status.text = "Đã bật bong bóng nổi"
            }
        }
        stop.setOnClickListener {
            roomClient?.disconnect(); roomClient = null; VoiceControls.connected.set(false)
            status.text = "Đã ngắt kết nối"; dot.setTextColor(softText)
        }
        settings.setOnClickListener {
            val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
            panel.addView(label("Cài đặt thoại", 22f, white, true)); panel.addView(gap(5))
            panel.addView(label("Tùy chỉnh âm thanh và kết nối", 13f, softText)); panel.addView(gap(14))
            fun settingSwitch(t: String, value: Boolean, changed: (Boolean) -> Unit) = Switch(this).apply {
                text = t; setTextColor(white); isChecked = value
                buttonTintList = android.content.res.ColorStateList.valueOf(green)
                setOnCheckedChangeListener { _, checked -> changed(checked) }
            }
            panel.addView(settingSwitch("Tắt micro", VoiceControls.microphoneMuted.get()) { VoiceControls.microphoneMuted.set(it); mic.text = if (it) "🎙  Bật mic" else "🎙  Tắt mic" })
            panel.addView(settingSwitch("Tắt loa", VoiceControls.speakerMuted.get()) { VoiceControls.speakerMuted.set(it); speaker.text = if (it) "🔊  Bật loa" else "🔊  Tắt loa" })
            panel.addView(settingSwitch("Nhấn giữ để nói", VoiceControls.pushToTalk.get()) { VoiceControls.pushToTalk.set(it) })
            panel.addView(settingSwitch("Tự kết nối lại", VoiceControls.reconnectEnabled.get()) { VoiceControls.reconnectEnabled.set(it) })
            panel.addView(gap(10)); panel.addView(label("Địa chỉ máy chủ", 13f, softText, true))
            val settingsHost = EditText(this).apply {
                hint = "https://địa-chỉ-máy-chủ"; setSingleLine(true); textSize = 14f
                setTextColor(white); setHintTextColor(softText); setText(host.text.toString())
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(bgColor); cornerRadius = dp(12).toFloat(); setStroke(dp(1), android.graphics.Color.rgb(58,81,66)) }
            }
            panel.addView(settingsHost); panel.addView(gap(12))
            val volLabel = label("Âm lượng loa: \${VoiceControls.speakerVolume.get()}%", 13f, white)
            val volume = SeekBar(this).apply {
                max = 100; progress = VoiceControls.speakerVolume.get()
                progressTintList = android.content.res.ColorStateList.valueOf(green)
                thumbTintList = android.content.res.ColorStateList.valueOf(green)
            }
            panel.addView(volLabel); panel.addView(volume)
            volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    VoiceControls.speakerVolume.set(progress); volLabel.text = "Âm lượng loa: " + progress + "%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            android.app.AlertDialog.Builder(this).setView(panel)
                .setPositiveButton("Lưu cài đặt") { _, _ -> host.setText(settingsHost.text.toString().trim()); prefs.edit().putString("host", settingsHost.text.toString().trim()).apply(); status.text = "Đã lưu cài đặt" }
                .setNegativeButton("Đóng", null).show()
        }
    }

    private fun runVoice(s: Socket, token: String, pair: EditText, connectionRunning: AtomicBoolean) {
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        var id = UUID(0L, 0L)
        send(output, 1, id, 0, token.toByteArray())

        val ack = ByteArray(29)
        readFully(input, ack)
        val ab = ByteBuffer.wrap(ack).order(ByteOrder.BIG_ENDIAN)
        if (ab.int != magic || ab.get().toInt() != 1) {
            if (token.startsWith("SESSION:")) throw IOException("SESSION_REJECTED")
            throw IOException("Pairing rejected")
        }
        val assignedMsb = ab.long
        val assignedLsb = ab.long
        val ackSequence = ab.int
        if (ackSequence != 0) throw IOException("Invalid gateway response")
        val ackLength = ab.int
        if (ackLength <= 0 || ackLength > 64) throw IOException("Invalid gateway response")
        val ackPayload = ByteArray(ackLength)
        readFully(input, ackPayload)
        val ackText = String(ackPayload, Charsets.UTF_8)
        if (!ackText.startsWith("OK")) {
            if (ackText.startsWith("ERROR:AUTH") && token.startsWith("SESSION:")) {
                throw IOException("SESSION_REJECTED")
            }
            throw IOException("Pairing rejected")
        }
        ackText.lineSequence().firstOrNull { it.startsWith("SESSION:") }
            ?.substringAfter("SESSION:")?.takeIf { it.isNotBlank() }?.let {
                sessionToken = it
                getPreferences(Context.MODE_PRIVATE).edit().putString("session_token", it).apply()
                if (token.startsWith("PAIR:")) runOnUiThread { pair.setText("") }
            }
        id = UUID(assignedMsb, assignedLsb)
        VoiceControls.connected.set(true)
        runOnUiThread { statusView?.text = "Đã kết nối" }

        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IOException("Microphone is not available")
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frameBytes * 4))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("Microphone initialization failed")
        }
        val trackMin = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (trackMin <= 0) {
            recorder.release()
            throw IOException("Speaker is not available")
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(trackMin, frameBytes * 4)).build()

        val receiver = Thread {
            val lastAudioSequences = HashMap<UUID, Int>()
            try {
                if (track.state != AudioTrack.STATE_INITIALIZED) throw IOException("Speaker initialization failed")
                track.play()
                while (connectionRunning.get()) {
                    track.setVolume(if (VoiceControls.speakerMuted.get()) 0f else VoiceControls.speakerVolume.get().coerceIn(0, 100) / 100f)
                    val header = ByteArray(29)
                    readFully(input, header)
                    val hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                    if (hb.int != magic) break
                    val type = hb.get()
                    val msb = hb.long
                    val lsb = hb.long
                    val seq = hb.int
                    val len = hb.int
                    if (len < 0 || len > 16384) break
                    val data = ByteArray(len)
                    readFully(input, data)
                    when (type.toInt()) {
                        2 -> {
                            if (len != frameBytes) break
                            val sender = UUID(msb, lsb)
                            if (sender == id) continue
                            val last = lastAudioSequences[sender]
                            if (last != null && Integer.compareUnsigned(seq, last) <= 0) continue
                            lastAudioSequences[sender] = seq
                            track.write(data, 0, frameBytes)
                        }
                        4 -> {
                            if (len != 0) break
                            send(output, pongType, id, seq, ByteArray(0))
                        }
                        3 -> {
                            if (len != 0) break
                            break
                        }
                        5 -> {
                            if (len != 0) break
                        }
                        else -> break
                    }
                }
            } catch (_: Exception) {
            } finally {
                connectionRunning.set(false)
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }

        receiver.start()
        val frame = ByteArray(frameBytes)
        var seq = 0
        try {
            recorder.startRecording()
            while (connectionRunning.get()) {
                var off = 0
                while (off < frame.size && connectionRunning.get()) {
                    val n = recorder.read(frame, off, frame.size - off)
                    if (n <= 0) {
                        connectionRunning.set(false)
                        break
                    }
                    off += n
                }
                if (off == frame.size && connectionRunning.get()) {
                    val shouldTransmit = !VoiceControls.microphoneMuted.get() &&
                        (!VoiceControls.pushToTalk.get() || VoiceControls.pushToTalkPressed.get())
                    if (shouldTransmit) send(output, 2, id, seq++, frame)
                    else send(output, 2, id, seq++, ByteArray(frameBytes))
                }
            }
        } finally {
            connectionRunning.set(false)
            VoiceControls.connected.set(false)
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { send(output, 3, id, 0, ByteArray(0)) } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
            receiver.join(1000)
            if (receiver.isAlive) {
                try { s.close() } catch (_: Exception) {}
                receiver.join(500)
            }
        }
    }

    private fun closeSocketIfCurrent(target: Socket) {
        if (socket === target) {
            socket = null
            try { target.close() } catch (_: Exception) {}
        } else {
            try { target.close() } catch (_: Exception) {}
        }
    }

    private fun send(out: DataOutputStream,type:Int,id:UUID,seq:Int,payload:ByteArray) {
        synchronized(out) {
            out.writeInt(magic); out.writeByte(type)
            out.writeLong(id.mostSignificantBits); out.writeLong(id.leastSignificantBits)
            out.writeInt(seq); out.writeInt(payload.size); out.write(payload); out.flush()
        }
    }

    private fun readFully(input:InputStream,data:ByteArray) {
        var off=0
        while(off<data.size) {
            val n=input.read(data,off,data.size-off)
            if(n<0) throw EOFException()
            off+=n
        }
    }
}
