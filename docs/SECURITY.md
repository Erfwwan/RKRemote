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

## Current internet transport

- Both devices connect to the Cloudflare Durable Object relay over WSS.
- Noise XX with X25519 and ChaCha20-Poly1305 protects application payloads end-to-end after signed device identity verification and mutual SAS approval.
- Application messages are optionally compressed before encryption, fragmented below the Worker's 12 KiB limit, and encrypted fragment-by-fragment.
- The Worker forwards opaque binary ciphertext. It can still observe IP addresses, room IDs, roles, timing, frame sizes and traffic volume.
- The room ID is not authentication. Reject replayed, expired, malformed, duplicate or out-of-order messages.
- Require explicit capability grants for screen, camera, microphone, files, contacts, call log, location and notifications.
- Never persist session keys or put API credentials, pairing secrets or private file contents in source control.
- Worker-generated room lifecycle events are limited to non-sensitive status metadata. No application payload may be sent as plaintext.

## Transport requirements

1. Use WSS for each phone-to-Cloudflare hop and Noise for end-to-end application payload protection.
2. Bind every command to an authenticated paired device and a fresh session nonce.
3. Reject replayed, expired, malformed, duplicate or out-of-order commands.
4. Require explicit capability grants for each feature and preserve owner-visible stop/revoke controls.
5. Do not persist pairing secrets in source control or plaintext preferences.
6. Log security-relevant events without logging message bodies, passwords, tokens, or private file contents.

## Data retention

The current milestone stores at most 500 notification events in app-private storage encrypted with AES-GCM and an Android Keystore key. If the target owner enables Android Notification Access and separately grants the NOTIFICATIONS capability for the active paired session, the app can send up to the latest 20 stored events end-to-end encrypted to the controller. The Cloudflare Worker never receives notification plaintext. Revoking the session capability or disabling Notification Access prevents further exports.
