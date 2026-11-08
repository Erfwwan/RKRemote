# RKRemote

Consent-based Android remote-device management for devices owned or explicitly administered by the operator.

## Current implementation

The active development branch contains two separate connection paths:

- **Secure Internet P2P (Android 13 / API 33+):** Cloudflare Worker signaling, Noise XX peer authentication with mutual short-authentication-string verification, WebRTC ICE and DTLS DataChannel transport, and per-session owner-approved capabilities.
- **Legacy LAN connection:** TCP bearer-token transport on port 18765. This path is **plaintext** and must be used only on a trusted private Wi-Fi network. Never expose it through port forwarding or to the public internet.

The secure Internet path is integrated at source level for:

- H.264 live screen viewing and owner-approved touch/text input.
- Owner-selected SAF folder browsing, upload and download, with bounded transfers and SHA-256 verification.
- Owner-approved notification snapshot synchronization.
- Owner-started camera frame streaming and microphone monitoring.
- Half-duplex push-to-talk from controller to target, with the separately owner-started microphone path for return audio.
- Custom network configuration profiles (signaling and ICE server settings).

## Important operating limits

- The Internet session is currently owned by the visible app UI; it is not a persistent background remote-control session.
- Cloudflare is a signaling rendezvous only. It does not relay media or application data.
- STUN is configured by default. A separately provisioned TURN service may be needed for restrictive NATs.
- DHT discovery and automatic public peer discovery are not implemented.
- The legacy LAN transport is not encrypted and is not safe for untrusted networks.
- Camera, microphone, screen capture and PTT speaker playback require local owner action, Android permissions/settings, visible foreground notifications, and local stop controls. Remote commands cannot silently start these sensors.
- PTT is half-duplex, not full-duplex VoIP. Audio quality and feedback behavior depend on the devices and environment.
- Durable upload resume after a session/process ends is not implemented. Some Android SAF providers may also impose their own limits.
- No independent security audit or physical two-device, NAT, TURN, long-duration, or Samsung-device validation has been completed. A successful CI build is not proof of runtime or production readiness.

See [implementation status](docs/IMPLEMENTATION_STATUS.md) for the detailed feature boundary and historical implementation notes.

## Build and automated checks

Requirements:

- Android Studio with a compatible Android SDK
- JDK 17
- Gradle 8.11.1
- Android Gradle Plugin 8.9.1

Run the automated checks from the repository root:

```bash
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:assembleDebug
```

GitHub Actions runs both commands and uploads the debug APK as a workflow artifact. The debug APK is suitable for development/device validation; it is not a production release signed with the owner's release key.

## Secure Internet setup

1. Deploy the Cloudflare Worker using [the Worker guide](cloudflare/worker/README.md).
2. Follow [the direct transport guide](docs/WEBRTC_DIRECT_TRANSPORT.md) to configure the signaling endpoint and room settings.
3. Provision a TURN server separately if the tested networks cannot establish a direct ICE path.
4. Pair both devices, verify the same short authentication string on both phones, and grant only the capabilities needed for that session.
5. Start screen/camera/microphone/PTT services locally on the target when required. Keep their visible notifications and stop actions available.

Cloudflare Worker deployment and TURN provisioning are operator tasks; no public signaling endpoint or TURN credentials are bundled in the repository.

## Security and privacy

- No covert camera/microphone capture, hidden persistence, permission bypass, stealth installation, or access to other apps' private sandboxes.
- The target owner must explicitly approve the peer and the requested capabilities.
- Android permission and privacy-indicator behavior remains in force.
- Notification contents can be sensitive; enable Notification Access only when needed.
- Private keys and TURN credentials must not be committed to source control.
- The legacy LAN service must remain confined to trusted private Wi-Fi.

## Project documentation

- [Implementation status](docs/IMPLEMENTATION_STATUS.md)
- [Security model](docs/SECURITY.md)
- [SecureLink protocol](docs/RK_SECURELINK_V1.md)
- [Cloudflare signaling](docs/CLOUDFLARE_SIGNALING.md)
- [WebRTC direct transport and TURN](docs/WEBRTC_DIRECT_TRANSPORT.md)
- [File transfer](docs/SECTION_3_FILE_TRANSFER.md)
- [Camera and microphone](docs/SECTION_4_CAMERA_MICROPHONE.md)
- [Two-way audio / PTT](docs/SECTION_5_TWO_WAY_AUDIO.md)
- [Custom network profiles](docs/OPEN_NETWORK_PROFILES.md)
