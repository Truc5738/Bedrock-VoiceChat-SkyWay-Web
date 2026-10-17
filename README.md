# Minecraft-VoiceChannel

Cross-platform voice channel backend for Paper 26.2 / Java 25.

## Target environment

- Paper 26.2
- Java 25
- Alpine Linux / musl libc compatible
- Third-party Minecraft hosting/container environments
- Floodgate for Bedrock player detection and Bedrock UI
- Default voice gateway port: TCP 26467
- No FFmpeg
- No JAVE2
- No glibc dependency
- No native Linux executable

Paper 26.2 officially supports Java 25. The build uses Paper's 26.2.build.+ API format.

## Main command

/voicechannel

The command opens a Java inventory UI or a Floodgate/Cumulus Bedrock form.

## UI features

1. Microphone mute/unmute state
2. Voice output mute/unmute state
3. Live speaking player count
4. Voice channels
5. Private-channel configuration foundation
6. Proximity voice range
7. Per-player volume state
8. Per-player mute state foundation
9. Channel moderation foundation
10. Voice settings foundation

## Alpine-safe gateway

The plugin contains an embedded TCP voice gateway on port 26467.

The gateway is implemented with Java standard networking APIs. It does not execute FFmpeg, JAVE2, glibc binaries, shell commands, or native Linux libraries.

On first startup, the default token change-me is replaced with a random 256-bit URL-safe token and saved to config.yml.

The host must expose TCP port 26467 to the client voice bridge.

## Voice transport protocol

Frame layout:

- 4 bytes: ASCII magic MVC1
- 1 byte: packet type
- 16 bytes: player UUID
- 4 bytes: sequence number
- 4 bytes: payload length
- N bytes: payload

Packet types:

- 1 HELLO: UTF-8 gateway token, sequence 0
- 2 AUDIO: encoded voice frame, normally Opus
- 3 GOODBYE: empty payload

A client must send HELLO before AUDIO. The UUID in every packet must match the authenticated session.

The server routes audio using channel, proximity range, world, output mute, per-player mute and microphone mute state.

## Important client limitation

A normal Paper plugin cannot read a player's microphone. Vanilla Java and vanilla Bedrock clients do not expose microphone capture to a server plugin.

Therefore the project separates:

- Paper plugin: UI, permissions, channels, moderation, proximity rules and gateway
- Voice client/bridge: microphone capture, audio encoding and speaker playback

Floodgate provides Bedrock identity and forms; it does not provide microphone capture.

The gateway protocol is designed so a Java client mod and a compatible Bedrock companion client can share the same voice backend without FFmpeg or glibc.

## Build

GitHub Actions builds the plugin with Java 25 and uploads the generated JAR as a workflow artifact.

For a fixed Paper build, replace 26.2.build.+ with a stable build such as 26.2.build.112-stable.

## Hosting notes

Do not place the voice gateway on the same UDP port used by Geyser. The voice gateway in this project uses TCP 26467.

If the hosting provider only exposes TCP and does not provide an additional TCP port, use the assigned secondary TCP port in config.yml.

If the provider blocks custom inbound ports entirely, the gateway cannot accept direct client connections from the Internet.

## No-emoji rule

Plugin UI, config, permissions and protocol messages intentionally avoid emoji characters.


## Documentation / Wiki

A polished project wiki is maintained in [docs/wiki](docs/wiki/Home.md).

- [Wiki home](docs/wiki/Home.md)
- [Installation](docs/wiki/Installation.md)
- [Configuration](docs/wiki/Configuration.md)
- [Clients](docs/wiki/Clients.md)
- [Protocol](docs/wiki/Protocol.md)
- [Architecture](docs/wiki/Architecture.md)
- [Troubleshooting](docs/wiki/Troubleshooting.md)


## Bedrock Voice web client

A browser-based SkyWay voice client is available at [web/index.html](web/index.html). It is intended to run beside Minecraft Bedrock on Android or another device.

- Uses SkyWay WebRTC audio rooms; no custom WebSocket audio transport is implemented.
- The SkyWay SDK may use WebSocket for signaling, and WebRTC may use UDP or a fallback route depending on the network.
- Paste a short-lived SkyWay Auth Token into the page. Never put a SkyWay Secret Key in public client code.
- GitHub Pages serves the static client only. A secure token-issuing backend must be configured separately for production.
- The client does not automatically read Minecraft world/player positions, so proximity audio needs an additional trusted position bridge.

The GitHub Pages workflow is defined in `.github/workflows/deploy-pages.yml`. If Pages has not been enabled for this repository, open **Settings → Pages** and select **GitHub Actions** as the build and deployment source.
