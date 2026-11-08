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

## Engineering documentation

- [Security model](docs/SECURITY.md)
- [Remote-control protocol and milestone design](docs/REMOTE_CONTROL_DESIGN.md)

A GitHub Actions workflow now runs a debug APK compilation on pushes, pull requests, and manual dispatches. The workflow uses Gradle 8.11.1 with JDK 17.

**Important:** the repository is not yet a complete remote-control product. The current Android application is the local capability-setup milestone. Remote pairing, authenticated network transport, remote screen interaction, camera/audio streaming, and push-to-talk remain future implementation work. Do not rely on the app for remote access until those features are implemented and tested on real devices.



## Development branch

The current development foundation is being prepared in [PR #1](https://github.com/Erfwwan/RKRemote/pull/1). It adds a versioned message envelope, one-time pairing-code prototype, TLS-pinned WebSocket transport, centralized capability checks, a visible session notification with a stop action, and initial JVM tests.

See [implementation status](docs/IMPLEMENTATION_STATUS.md) for the exact boundary between implemented code and unfinished features. The project is not yet a complete remote-control system and should not be deployed for remote administration until the authenticated relay handshake, controller app, capability-specific operations, and device testing are completed.
