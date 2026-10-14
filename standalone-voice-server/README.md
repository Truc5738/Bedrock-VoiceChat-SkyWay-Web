# Minecraft PE Voice Room Relay

This is the WebSocket relay used by the standalone Android companion app. It does not require a Minecraft Bedrock addon, behavior pack, or server plugin.

## Deploy on Render

1. Push this branch to GitHub and create a new Blueprint in Render using this repository.
2. Render reads `render.yaml` and creates the `minecraft-pe-voice-room` web service in Singapore.
3. Wait for the service to become live. Open `https://YOUR-SERVICE.onrender.com/health`; the response should contain `"ok":true`.
4. In the Android app, enter the base service URL, for example `https://YOUR-SERVICE.onrender.com`. Do not append `/health`. The app converts HTTPS to secure WebSocket automatically.

## Use

- Person A enters the service URL and taps **Tạo phòng mới**.
- Share the six-digit room code with Person B.
- Person B enters the same service URL and code, then taps **Vào phòng**.
- Grant microphone permission. The floating controller is optional and needs Android's display-over-other-apps permission.
- Both players need internet access. The voice app is separate from Minecraft, so it can be used while Minecraft PE is open.

## Current limitations and safety

- This relay is a prototype. Room codes are six digits, there is no account authentication, room ownership verification, rate limiting, or abuse reporting. Do not treat room codes as private credentials.
- Room state is held in memory and disappears when the service restarts or sleeps.
- The Android foreground service currently controls the overlay, but the audio engine is not yet owned by that service. Reliable audio after the activity is destroyed is not guaranteed.
- Test the app on actual Android devices and on the chosen hosting plan before relying on it for long sessions.
- The relay forwards raw 20 ms PCM frames. It does not record or persist audio intentionally.
