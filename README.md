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

Run the automated checks from the repository root with JDK 17 and Gradle 8.11.1:

```bash
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:assembleDebug
gradle --no-daemon :app:assembleRelease
```

GitHub Actions runs all three tasks and uploads separate debug and release APK artifacts. The CI release APK is unsigned unless the operator deliberately supplies release-signing environment variables. Debug builds are for development/device validation, not public distribution.

### Release signing

Create and safeguard your own keystore outside the repository. For example, use `keytool -genkeypair` with a long-lived RSA key and back up the keystore and passwords securely. Never commit the keystore or passwords.

Set these environment variables before running `:app:assembleRelease`:

- `RKREMOTE_RELEASE_STORE_FILE`: path to your keystore file.
- `RKREMOTE_RELEASE_STORE_PASSWORD`: keystore password.
- `RKREMOTE_RELEASE_KEY_ALIAS`: key alias.
- `RKREMOTE_RELEASE_KEY_PASSWORD`: key password.

All four values are required together. If none are set, Gradle intentionally creates an unsigned release APK. If only some are set or the keystore file is missing, the build fails instead of silently producing an unexpected artifact. For CI signing, store the values as protected GitHub Actions secrets and map them to these environment variable names in a private release workflow; do not print them in logs.

The current package ID is `com.rkremote`, minimum Android version is API 26 (Android 8.0), target SDK is API 35, and the app version is `0.1.0 (versionCode 1)`. Increase `versionCode` for each subsequent distributed release.

## Secure Internet setup

1. Deploy the Cloudflare Worker using [the Worker guide](cloudflare/worker/README.md).
2. Follow [the direct transport guide](docs/WEBRTC_DIRECT_TRANSPORT.md) to configure the signaling endpoint and room settings.
3. Provision a TURN server separately if the tested networks cannot establish a direct ICE path.
4. Pair both devices, verify the same short authentication string on both phones, and grant only the capabilities needed for that session.
5. Start screen/camera/microphone/PTT services locally on the target when required. Keep their visible notifications and stop actions available.

Cloudflare Worker deployment and TURN provisioning are operator tasks; no public signaling endpoint or TURN credentials are bundled in the repository.

## Installation and first-run checks

1. Install the APK on the target phone. Android may require enabling installation from the source used to open the APK.
2. Open RKRemote and review the permission page. Grant Camera, Microphone, and notification runtime access only if the matching feature is needed.
3. Enable Notification Access or Accessibility manually in Android Settings only when you intend to use those specific functions.
4. Select the target folder through Android's system folder picker; file access is limited to that owner-selected tree.
5. Start each capture/session locally on the target. Keep the persistent notification and Stop action visible.
6. For secure Internet P2P, deploy the Worker and configure STUN/TURN as described above before pairing.

The app does not require contacts, call-log, location, or broad shared-media/storage permissions. File access uses the Android Storage Access Framework folder grant. Physical two-device acceptance testing is a separate final step and is not implied by a successful CI build.

## Security and privacy

- No covert camera/microphone capture, hidden persistence, permission bypass, stealth installation, or access to other apps' private sandboxes.
- The target owner must explicitly approve the peer and the requested capabilities.
- Android permission and privacy-indicator behavior remains in force.
- Notification contents can be sensitive; enable Notification Access only when needed.
- Private keys and TURN credentials must not be committed to source control.
- The legacy LAN service must remain confined to trusted private Wi-Fi.

## Project documentation

- [Release and two-device acceptance checklist](docs/RELEASE_CHECKLIST.md)
- [Implementation status](docs/IMPLEMENTATION_STATUS.md)
- [Security model](docs/SECURITY.md)
- [SecureLink protocol](docs/RK_SECURELINK_V1.md)
- [Cloudflare signaling](docs/CLOUDFLARE_SIGNALING.md)
- [WebRTC direct transport and TURN](docs/WEBRTC_DIRECT_TRANSPORT.md)
- [File transfer](docs/SECTION_3_FILE_TRANSFER.md)
- [Camera and microphone](docs/SECTION_4_CAMERA_MICROPHONE.md)
- [Two-way audio / PTT](docs/SECTION_5_TWO_WAY_AUDIO.md)
- [Custom network profiles](docs/OPEN_NETWORK_PROFILES.md)
