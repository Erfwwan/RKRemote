# RKRemote

A consent-based Android remote-device management project for devices owned or explicitly administered by the operator.

## Security boundary

RKRemote does not implement covert camera/microphone capture, hidden persistence, permission bypasses, silent reboot, stealth installation, or collection from other apps' private sandboxes. Sensitive capabilities require Android's own permission or settings flow. Active camera, microphone, and screen-sharing sessions must be visible and stoppable on the target device.

## Current status

**Milestone 1 — Local capability setup**

- Kotlin + Jetpack Compose Android app shell
- Runtime permission setup for camera, microphone, foreground location, contacts, call history, and media
- User-selected persistent folder access through Android Storage Access Framework
- Notification listener service, enabled manually in Android Notification Access settings
- Bounded local notification event history (500 events), encrypted using AES-GCM with a non-exportable Android Keystore key
- Notification contents are stored locally only; this milestone does not transmit them

**Available in the current development branch:** an owner-started, visible read-only LAN agent and same-app controller. On a trusted Wi-Fi network, the controller can test the target phone connection and list names/metadata from a folder explicitly selected on that phone. This is an early runnable milestone, not full remote control.

**Screen-sharing prototype added:** the target phone can request Android MediaProjection consent, start a visible foreground capture session, and expose the latest JPEG screen snapshot to the authenticated controller on the same trusted Wi-Fi. The controller can request a single snapshot or poll snapshots at roughly one per second for a low-rate preview. This remains JPEG polling, not a continuous video stream, remote touch/input, or a secure internet transport.

**Still not implemented:** internet relay, secure TLS LAN transport, continuous screen video/input, camera/audio streaming, file content transfer, contacts/call-log transfer, and two-way walkie-talkie.

## Build

- Android Studio with a recent stable Android SDK
- JDK 17
- Gradle 8.11.1
- Android Gradle Plugin 8.9.1

Open the repository in Android Studio and run the app configuration on an Android device or emulator.

## Planned milestones

1. Local permission and capability setup (current).
2. Authenticated device pairing and encrypted transport.
3. User-approved screen sharing with Android MediaProjection and visible session controls (initial JPEG snapshot path added; continuous video and input remain).
4. User-started camera/microphone sessions with foreground-service indicators.
5. Scoped file/gallery browsing, contacts and call-history access.
6. Notification history forwarding after explicit pairing and user opt-in.
7. Two-way push-to-talk audio.
8. Connection recovery, diagnostics, and long-duration battery/stability testing.

## Privacy and security

- Target-device owner explicitly enables each special access.
- Notification Access can expose sensitive message contents; keep it disabled unless needed.
- Local notification history is bounded to the latest 500 events and encrypted at rest.
- No credentials, pairing secrets, or private keys belong in source control.
- Never expose a relay or admin endpoint to the public internet without authentication, rate limits, and TLS.

## Engineering documentation

- [Security model](docs/SECURITY.md)
- [Remote-control protocol and milestone design](docs/REMOTE_CONTROL_DESIGN.md)

A GitHub Actions workflow now runs a debug APK compilation on pushes, pull requests, and manual dispatches. The workflow uses Gradle 8.11.1 with JDK 17.

**Important:** the repository is not yet a complete remote-control product. The current Android application includes local capability setup and a minimal read-only LAN connection path. Full remote control, media streaming, and secure internet access remain future work. Do not rely on the app for remote access until those features are implemented and tested on real devices.



## Development branch

The current development branch is [PR #1](https://github.com/Erfwwan/RKRemote/pull/1). It adds the LAN agent/controller prototype, security foundations, a visible session notification with a stop action, and JVM tests.

See [implementation status](docs/IMPLEMENTATION_STATUS.md) for the exact boundary between implemented code and unfinished features. The LAN prototype is read-only and intended only for a trusted private Wi-Fi network. Do not expose it to the internet. Full remote-control features and real-device validation are still required.

## Part 1 quick start (trusted Wi-Fi only)

1. Install the same RKRemote debug APK on both Android phones.
2. Connect both phones to the same trusted private Wi-Fi network.
3. On the target phone, open RKRemote and choose the folder to share using **Choose folder**.
4. In **Part 1 · Local Wi-Fi connection**, tap **Start agent**. Keep RKRemote open; the foreground notification indicates the active session.
5. Share the target phone's local IPv4 address and temporary token with the controller phone.
6. On the controller phone, enter that IP and token, tap **Test connection**, then **List selected folder**.
7. Use **Stop** on the target phone or **Stop session** in its notification to end the LAN agent. For screen capture, use **Stop screen** or the screen-sharing notification's stop action. To share a screen, tap **Share this screen** on the target and approve Android's capture dialog; on the controller, tap **View screen snapshot**. The screen snapshot endpoint is authenticated with the same temporary token.

The LAN file browser remains read-only and lists names and metadata only. Screen sharing sends JPEG snapshots of the target display only after Android consent; it does not allow remote touch/input. The token is transmitted without TLS, so do not use public Wi-Fi, untrusted networks, or port forwarding. Never expose TCP port 18765 to the internet.
