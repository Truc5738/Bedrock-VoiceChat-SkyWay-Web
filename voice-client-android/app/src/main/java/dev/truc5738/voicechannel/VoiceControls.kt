package dev.truc5738.voicechannel

import java.util.concurrent.atomic.AtomicBoolean

/** Shared controls used by the activity and the floating overlay. */
object VoiceControls {
    val microphoneMuted = AtomicBoolean(false)
    val speakerMuted = AtomicBoolean(false)
    val reconnectEnabled = AtomicBoolean(true)
    val connected = AtomicBoolean(false)
    val pushToTalk = AtomicBoolean(false)
    val pushToTalkPressed = AtomicBoolean(false)
    val noiseReductionHint = AtomicBoolean(true)
    val compactOverlay = AtomicBoolean(false)
}
