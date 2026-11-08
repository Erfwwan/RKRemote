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

**Not implemented yet:** remote pairing, network transport, remote file operations, remote screen interaction, camera/audio streaming, or two-way walkie-talkie.

## Build

- Android Studio with a recent stable Android SDK
- JDK 17
- Gradle 8.11.1
- Android Gradle Plugin 8.9.1

Open the repository in Android Studio and run the app configuration on an Android device or emulator.

## Planned milestones

1. Local permission and capability setup (current).
2. Authenticated device pairing and encrypted transport.
3. User-approved screen sharing with Android MediaProjection and visible session controls.
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
