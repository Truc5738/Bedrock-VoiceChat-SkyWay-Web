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
    private var statusView: TextView? = null
    private val sampleRate = 16000
    private val frameBytes = sampleRate / 50 * 2
    private val magic = 0x4D564331
    private val pongType = 5

    override fun onDestroy() {
        wantConnection = false
        connectionThread?.interrupt()
        val current = socket
        socket = null
        try { current?.close() } catch (_: Exception) {}
        connectionThread = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val title = TextView(this).apply { text = "Minecraft VoiceChat"; textSize = 24f }
        val host = EditText(this).apply { hint = "Địa chỉ máy chủ thoại"; setText(prefs.getString("host", "")) }
        val port = EditText(this).apply { hint = "Cổng"; setText(prefs.getString("port", "26467")) }
        val pair = EditText(this).apply { hint = "Mã ghép đôi (6 chữ số)" }
        val micSwitch = Switch(this).apply { text = "Tắt micro"; isChecked = VoiceControls.microphoneMuted.get() }
        val speakerSwitch = Switch(this).apply { text = "Tắt loa"; isChecked = VoiceControls.speakerMuted.get() }
        val pttSwitch = Switch(this).apply { text = "Nhấn giữ để nói"; isChecked = VoiceControls.pushToTalk.get() }
        val reconnectSwitch = Switch(this).apply { text = "Tự kết nối lại"; isChecked = VoiceControls.reconnectEnabled.get() }
        val volumeLabel = TextView(this).apply { text = "Âm lượng loa: ${VoiceControls.speakerVolume.get()}%" }
        val volumeSlider = SeekBar(this).apply { max = 100; progress = VoiceControls.speakerVolume.get() }
        val save = Button(this).apply { text = "Lưu địa chỉ" }
        val help = TextView(this).apply { text = "Kết nối thoại độc lập • Không cần Addon Minecraft"; textSize = 14f }
        val status = TextView(this).apply { text = "Disconnected" }
        statusView = status
        val button = Button(this).apply { text = "Kết nối" }
        val stop = Button(this).apply { text = "Dừng kết nối" }
        val overlay = Button(this).apply { text = "Bật bong bóng nổi" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32,32,32,32)
            addView(title); addView(help)
            addView(host); addView(port); addView(pair)
            addView(status); addView(button); addView(stop); addView(overlay)
            addView(micSwitch); addView(speakerSwitch); addView(volumeLabel); addView(volumeSlider); addView(pttSwitch); addView(reconnectSwitch); addView(save)
        }
        setContentView(layout)
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
        sessionToken = prefs.getString("session_token", null)
        micSwitch.setOnCheckedChangeListener { _, checked -> VoiceControls.microphoneMuted.set(checked) }
        speakerSwitch.setOnCheckedChangeListener { _, checked -> VoiceControls.speakerMuted.set(checked) }
        pttSwitch.setOnCheckedChangeListener { _, checked -> VoiceControls.pushToTalk.set(checked) }
        reconnectSwitch.setOnCheckedChangeListener { _, checked -> VoiceControls.reconnectEnabled.set(checked) }
        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                VoiceControls.speakerVolume.set(progress)
                volumeLabel.text = "Âm lượng loa: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        save.setOnClickListener {
            prefs.edit().putString("host", host.text.toString().trim())
                .putString("port", port.text.toString().trim()).apply()
            status.text = "Đã lưu cấu hình"
        }

        button.setOnClickListener {
            if (wantConnection) return@setOnClickListener
            val hostValue = host.text.toString().trim()
            prefs.edit().putString("host", hostValue).putString("port", port.text.toString().trim()).apply()
            val portValue = port.text.toString().trim().toIntOrNull() ?: 26467
            val pairCode = pair.text.toString().trim()
            if (hostValue.isBlank()) {
                status.text = "Enter gateway host"
                return@setOnClickListener
            }
            if (portValue !in 1..65535) {
                status.text = "Invalid gateway port"
                return@setOnClickListener
            }
            wantConnection = true
            status.text = "Connecting..."
            val worker = Thread {
                var firstConnection = true
                while (wantConnection) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            runOnUiThread { statusView?.text = "Microphone permission required" }
                            wantConnection = false
                            break
                        }

                        val s = Socket()
                        s.connect(java.net.InetSocketAddress(hostValue, portValue), 5000)
                        s.tcpNoDelay = true
                        s.keepAlive = true
                        s.soTimeout = 20000
                        socket = s

                        val usePairing = pairCode.matches(Regex("\\d{6}"))
                        val credential = if (usePairing) {
                            "PAIR:" + pairCode
                        } else {
                            sessionToken?.let { "SESSION:" + it } ?: "PAIR:" + pairCode
                        }

                        val connectionRunning = AtomicBoolean(true)
                        try {
                            runVoice(s, credential, pair, connectionRunning)
                        } catch (ex: Exception) {
                            if (sessionToken != null && ex.message == "SESSION_REJECTED") {
                                sessionToken = null
                                getPreferences(Context.MODE_PRIVATE).edit().remove("session_token").apply()
                                runOnUiThread { statusView?.text = "Session expired - enter pair code" }
                                wantConnection = false
                            } else if (ex.message == "Pairing rejected") {
                                runOnUiThread { statusView?.text = "Pairing rejected - enter a new code" }
                                wantConnection = false
                            } else if (wantConnection) {
                                runOnUiThread { statusView?.text = "Disconnected - retrying" }
                            }
                        } finally {
                            connectionRunning.set(false)
                            closeSocketIfCurrent(s)
                        }
                        if (!wantConnection) break
                    } catch (_: Exception) {
                        if (wantConnection) runOnUiThread { statusView?.text = "Disconnected - retrying" }
                    }
                    if (wantConnection && VoiceControls.reconnectEnabled.get()) {
                        try {
                            Thread.sleep(if (firstConnection) 3000L else 2000L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    firstConnection = false
                }
                if (!wantConnection) runOnUiThread { statusView?.text = "Disconnected" }
            }.also { it.isDaemon = true }
            connectionThread = worker
            worker.start()
        }

        overlay.setOnClickListener {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                status.text = "Cho phép hiển thị trên ứng dụng khác"
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                val serviceIntent = android.content.Intent(this, VoiceOverlayService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                status.text = "Đã bật bong bóng nổi"
            }
        }

        stop.setOnClickListener {
            wantConnection = false
            connectionThread?.interrupt()
            val current = socket
            socket = null
            try { current?.close() } catch (_: Exception) {}
            runOnUiThread { statusView?.text = "Disconnected" }
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
