# Standalone Minecraft PE Voice Chat

## Product goal

Provide a companion Android APK for two or more Minecraft Bedrock / PE players to talk over the Internet while playing, without a Minecraft add-on, behavior pack, resource pack, or server plugin. The companion app is responsible for voice only; Minecraft multiplayer/world access remains separate.

## Required user flow

1. Player A opens the companion APK and creates a room.
2. The app displays a short room code.
3. Player B opens the APK and joins with that code.
4. Both grant microphone permission and can mute/unmute their microphones and speaker output.
5. Both can minimize the app and return to Minecraft PE while voice continues where Android allows it.
6. Either player can leave the room and the app stops recording/playback.

## Architecture

- Android client: Kotlin, native Android audio APIs, foreground service while in a call, persistent notification, room-code UI, reconnect and audio-route handling.
- Voice media: WebRTC audio tracks for low-latency audio and NAT traversal.
- Signaling: small HTTPS/WebSocket service to create/join rooms and exchange WebRTC offer/answer/ICE messages. It must not carry raw audio in normal operation.
- Connectivity fallback: TURN relay credentials supplied by a hosted service or a separately configured TURN server, because direct peer-to-peer connectivity is not guaranteed across mobile networks and NATs.
- Security: random unguessable room identifiers, short-lived room membership, authenticated signaling messages, TLS, rate limits, and no permanent storage of audio.

## Explicit non-goals

- No Minecraft add-on or resource pack.
- No Paper/Spigot/Floodgate plugin requirement.
- No requirement for players to expose ports on their home routers.
- The APK does not connect players to the same Minecraft world; players still use Minecraft's own multiplayer mechanism.

## Acceptance checklist

- [ ] Android build succeeds and produces an APK.
- [ ] Two physical Android devices can create/join the same room.
- [ ] Two devices on different networks can exchange two-way voice.
- [ ] Microphone permission denial is handled gracefully.
- [ ] Mute, speaker output, leave-room, reconnect and background behavior work.
- [ ] The app does not require a Minecraft server plugin or add-on.
- [ ] README explains deployment and any required hosted signaling/TURN configuration.
- [ ] Test on real devices and mobile networks before claiming release-ready status.

## Important implementation note

A standalone APK can remove the Minecraft-side add-on/plugin requirement, but it cannot guarantee Internet voice connectivity with no network coordination at all. A signaling endpoint is needed for room discovery and connection negotiation; some network pairs also require a TURN relay. These are voice-service components, not Minecraft plugins.
