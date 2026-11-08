# Security model

RKRemote is intended only for devices owned by the operator or devices for which the operator has explicit administrative authorization.

## Required user-visible controls

- Pairing must be initiated and approved on the target device.
- Pairing codes must be short-lived, single-use, and rate-limited.
- Every remote session must be visible on the target device and stoppable locally.
- Screen capture must use Android MediaProjection consent.
- Camera and microphone capture must use runtime permission and a visible foreground service notification.
- Notification access must be enabled manually in Android Settings.
- File access must be limited to user-selected Storage Access Framework locations or documented runtime media permissions.
- No AccessibilityService may be enabled silently or used to bypass Android consent surfaces.

## Prohibited implementation patterns

- Hidden installation, stealth persistence, or attempts to hide the app or its foreground notification.
- Silent activation of camera, microphone, screen capture, or location.
- Permission bypass, root exploits, or disabling Android security controls.
- Credential harvesting, collection from other apps' private sandboxes, or exfiltration without explicit opt-in.
- Publicly exposed unauthenticated control endpoints.

## Transport requirements before remote control is enabled

1. Use TLS 1.3 where supported, with certificate/public-key pinning or an authenticated private relay.
2. Bind every command to an authenticated paired device and a fresh session nonce.
3. Reject replayed, expired, malformed, or out-of-order commands.
4. Require explicit capability grants for screen, camera, microphone, files, contacts, call log, and notifications.
5. Do not persist pairing secrets in source control or plaintext preferences.
6. Log security-relevant events without logging message bodies, passwords, tokens, or private file contents.

## Data retention

The current milestone stores at most 500 notification events in app-private storage encrypted with AES-GCM and an Android Keystore key. No notification content is transmitted in the current build.
