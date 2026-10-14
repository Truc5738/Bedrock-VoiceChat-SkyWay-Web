# Standalone Voice Chat Roadmap

This branch adds a floating controller and expands Android controls. It is a work-in-progress companion app, not a finished release.

## 10 UI features implemented or scaffolded
1. Vietnamese main screen and status label.
2. Saved gateway host.
3. Saved gateway port.
4. Pair-code input.
5. Connect button.
6. Disconnect button.
7. Permission flow for draw-over-apps.
8. Draggable floating bubble.
9. Floating mic mute/unmute control.
10. Floating speaker mute/unmute control.
11. Push-to-talk mode toggle (the hold gesture still needs implementation).
12. Auto-reconnect preference.
13. Open app from the overlay.
14. Collapse/expand overlay controls.
15. Stop overlay service control.
16. Persistent foreground-service notification.

## 10 other features implemented or scaffolded
1. Android microphone permission declaration.
2. Android foreground microphone service declaration.
3. Android overlay permission declaration.
4. WebSocket room relay service skeleton.
5. Six-digit room code generation.
6. Join-by-room-code validation.
7. Room capacity limit.
8. Binary audio frame relay.
9. Heartbeat checks for stale WebSocket connections.
10. Health-check endpoint for hosting.
11. Render deployment blueprint in Singapore.
12. Existing TCP gateway audio client retained for compatibility.

## Important engineering status
- This is not yet a production-ready, one-tap independent voice app.
- The new WebSocket relay is not yet integrated with the Android client; the existing Android audio client still speaks the project's TCP gateway protocol.
- The current overlay service starts a foreground service and draws controls, but the microphone/audio engine still belongs to the activity. Android lifecycle integration must be completed before audio can be claimed to continue reliably after the activity is backgrounded.
- Push-to-talk currently has a mode toggle but no press-and-hold gesture yet.
- A room relay needs authenticated room ownership, rate limiting, TLS deployment verification, and abuse protections before public use.
- A CI build and physical-device tests are required. Do not distribute as a finished APK until these pass.
- Android 13+ notification permission and Android 14+ foreground microphone service rules must be tested on device.
