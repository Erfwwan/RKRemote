# RKRemote

A consent-based Android remote-device management project.

> **Security boundary:** RKRemote is designed for devices owned or explicitly administered by the operator. It does not implement covert camera/microphone capture, hidden persistence, permission bypasses, silent reboot, or stealth installation. Camera, microphone, screen capture, location, and notification access must be explicitly granted on the target device and visibly indicated while active.

## Current status

**Milestone 0 — Android starter app (in progress)**

- Native Kotlin + Jetpack Compose shell
- Permission/status dashboard
- Explicit foreground-session model (planned)
- No remote transport or surveillance features are enabled yet

This repository is a starting point, not a finished remote-control product. Android restricts unattended screen capture, camera/microphone use, background execution, package management, and reboot operations. These restrictions are intentionally respected.

## Build

Requirements:
- Android Studio with a recent stable Android SDK
- JDK 17
- Android Gradle Plugin / Gradle versions declared by the project

Open the repository in Android Studio and run the `app` configuration on an Android device or emulator.

## Planned milestones

1. Pairing and device identity with explicit approval on both devices.
2. Authenticated, encrypted signaling and session management.
3. User-approved screen sharing using Android MediaProjection.
4. Visible, user-started camera and microphone sessions using foreground services.
5. User-scoped file transfer, location sharing, and notification forwarding.
6. Connection recovery that never bypasses Android permission or consent prompts.

## Privacy and safety

- A target device must show that a remote session is active.
- The target user can stop a session locally at any time.
- No credentials, pairing secrets, or private keys belong in source control.
- Do not expose a relay or admin endpoint to the public internet without authentication, rate limits, and TLS.
