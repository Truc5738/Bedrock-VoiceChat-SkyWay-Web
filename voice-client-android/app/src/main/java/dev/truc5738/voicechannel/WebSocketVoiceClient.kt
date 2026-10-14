package dev.truc5738.voicechannel

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket transport for the standalone room relay. Audio frames are raw 20 ms
 * PCM chunks (16 kHz, mono, signed 16-bit little-endian), matching the relay.
 */
data class VoiceRoomInfo(val id: String, val name: String, val members: Int, val locked: Boolean)

class WebSocketVoiceClient(
    private val onStatus: (String) -> Unit,
    private val onRoomCode: (String) -> Unit,
    private val onRooms: (List<VoiceRoomInfo>) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var roomReady = false
    private val audioRunning = AtomicBoolean(false)
    private val audioLock = Any()
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var audioThread: Thread? = null
    private val sampleRate = 16000
    private val frameBytes = 640

    fun connect(serverUrl: String, roomId: String?, password: String?, roomName: String?, createRoom: Boolean, listRooms: Boolean = false) {
        disconnect()
        val normalized = serverUrl.trim().let {
            when {
                it.startsWith("https://") -> "wss://" + it.removePrefix("https://")
                it.startsWith("http://") -> "ws://" + it.removePrefix("http://")
                else -> it
            }
        }
        if (!normalized.startsWith("wss://") && !normalized.startsWith("ws://")) {
            onStatus("URL phải bắt đầu bằng https:// hoặc wss://")
            return
        }
        onStatus("Đang kết nối máy chủ phòng thoại…")
        val request = try { Request.Builder().url(normalized).build() } catch (_: Exception) {
            onStatus("URL máy chủ không hợp lệ")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val command = when {
                    listRooms -> JSONObject().put("type", "list_rooms")
                    createRoom -> JSONObject().put("type", "create").put("name", roomName ?: "Phòng thoại").put("password", password ?: "")
                    else -> JSONObject().put("type", "join").put("roomId", roomId).put("password", password ?: "")
                }
                webSocket.send(command.toString())
                onStatus("Đã kết nối máy chủ, đang vào phòng…")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "rooms" -> {
                            val array = message.optJSONArray("rooms")
                            val list = mutableListOf<VoiceRoomInfo>()
                            if (array != null) for (i in 0 until array.length()) {
                                val item = array.optJSONObject(i) ?: continue
                                list.add(VoiceRoomInfo(item.optString("id"), item.optString("name", "Phòng thoại"), item.optInt("members", 0), item.optBoolean("locked", false)))
                            }
                            onRooms(list)
                            if (listRooms) onStatus(if (list.isEmpty()) "Chưa có phòng nào. Hãy tạo phòng mới." else "Chọn phòng muốn tham gia")
                        }
                        "created" -> {
                            roomReady = true
                            VoiceControls.connected.set(true)
                            onRoomCode(message.optString("name", "Phòng thoại"))
                            onStatus("Đã tạo phòng thoại")
                            startAudio(webSocket)
                        }
                        "joined" -> {
                            roomReady = true
                            VoiceControls.connected.set(true)
                            onRoomCode(message.optString("name", "Phòng thoại"))
                            onStatus("Đã vào phòng thoại")
                            startAudio(webSocket)
                        }
                        "peer_joined" -> onStatus("Bạn chơi đã vào phòng")
                        "peer_left" -> onStatus("Bạn chơi đã rời phòng")
                        "left" -> onStatus("Đã rời phòng")
                        "error" -> onStatus(message.optString("message", "Lỗi phòng thoại"))
                        else -> onStatus("Phản hồi máy chủ không được hỗ trợ")
                    }
                } catch (_: Exception) {
                    onStatus("Phản hồi máy chủ không hợp lệ")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val player = synchronized(audioLock) { track }
                if (roomReady && bytes.size == frameBytes && player != null && player.state == AudioTrack.STATE_INITIALIZED) {
                    try { player.write(bytes.toByteArray(), 0, frameBytes) } catch (_: Exception) {}
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                onStatus("Máy chủ đang đóng kết nối")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                roomReady = false
                VoiceControls.connected.set(false)
                stopAudio()
                onStatus("Đã ngắt kết nối")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                roomReady = false
                VoiceControls.connected.set(false)
                stopAudio()
                onStatus("Lỗi kết nối: ${t.message ?: "không rõ nguyên nhân"}")
            }
        })
    }

    private fun startAudio(webSocket: WebSocket) {
        if (!audioRunning.compareAndSet(false, true)) return
        val minRecord = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minTrack = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minRecord <= 0 || minTrack <= 0) {
            audioRunning.set(false)
            onStatus("Thiết bị không hỗ trợ thu hoặc phát âm thanh")
            return
        }
        try {
            val input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minRecord, frameBytes * 4))
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minTrack, frameBytes * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (input.state != AudioRecord.STATE_INITIALIZED || output.state != AudioTrack.STATE_INITIALIZED) {
                input.release()
                output.release()
                audioRunning.set(false)
                onStatus("Không khởi tạo được micro hoặc loa")
                return
            }
            synchronized(audioLock) { recorder = input; track = output }
            output.play()
            input.startRecording()
            audioThread = Thread {
                val frame = ByteArray(frameBytes)
                try {
                    while (audioRunning.get() && roomReady) {
                        var offset = 0
                        while (offset < frame.size && audioRunning.get()) {
                            val count = input.read(frame, offset, frame.size - offset)
                            if (count <= 0) throw IllegalStateException("Không đọc được micro")
                            offset += count
                        }
                        if (offset == frame.size && audioRunning.get()) {
                            val transmit = !VoiceControls.microphoneMuted.get() &&
                                (!VoiceControls.pushToTalk.get() || VoiceControls.pushToTalkPressed.get())
                            if (!transmit) frame.fill(0)
                            if (!webSocket.send(ByteString.of(*frame))) throw IllegalStateException("Kết nối đã đóng")
                            output.setVolume(if (VoiceControls.speakerMuted.get()) 0f else VoiceControls.speakerVolume.get().coerceIn(0, 100) / 100f)
                        }
                    }
                } catch (e: Exception) {
                    if (audioRunning.get()) onStatus("Âm thanh bị gián đoạn: ${e.message ?: "lỗi thiết bị"}")
                } finally {
                    stopAudio()
                }
            }.apply { name = "voice-audio"; isDaemon = true; start() }
        } catch (e: Exception) {
            audioRunning.set(false)
            onStatus("Không thể khởi động âm thanh: ${e.message ?: "lỗi thiết bị"}")
        }
    }

    private fun stopAudio() {
        audioRunning.set(false)
        val input: AudioRecord?
        val output: AudioTrack?
        synchronized(audioLock) {
            input = recorder; output = track
            recorder = null; track = null
        }
        try { input?.stop() } catch (_: Exception) {}
        try { input?.release() } catch (_: Exception) {}
        try { output?.pause() } catch (_: Exception) {}
        try { output?.flush() } catch (_: Exception) {}
        try { output?.stop() } catch (_: Exception) {}
        try { output?.release() } catch (_: Exception) {}
    }

    fun disconnect() {
        roomReady = false
        VoiceControls.connected.set(false)
        VoiceControls.pushToTalkPressed.set(false)
        stopAudio()
        socket?.send(JSONObject().put("type", "leave").toString())
        socket?.close(1000, "User disconnected")
        socket = null
    }
}
