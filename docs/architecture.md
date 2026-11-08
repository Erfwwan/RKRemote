# RKRemote architecture

## Roles

RKRemote will have two explicit roles:

- **Controller:** the phone used by the owner to request a session and view device status.
- **Target:** the phone being administered. The target app remains visible and shows an ongoing-session notification/indicator for sensitive operations.

The first milestone uses one Android application shell. Role selection and pairing are not implemented yet.

## Trust model

1. Pairing requires a one-time approval on the target.
2. Each device receives its own cryptographic identity.
3. Session requests are authenticated and encrypted in transit.
4. Camera, microphone, screen capture, location, and notification access are separate capabilities.
5. Sensitive capabilities are off until the target user explicitly enables them for a session.
6. The target user can stop an active session locally.
7. No hidden startup, permission bypass, covert capture, or silent command execution is part of this project.

## Android platform boundaries

- Screen sharing uses the Android MediaProjection consent flow.
- Camera and microphone use runtime permissions and visible foreground-service indicators where required by the OS.
- Location sharing is user-authorized and scoped to the selected sharing mode.
- Notification forwarding requires the user to enable Notification Listener access in Android Settings.
- Ordinary apps cannot silently reboot a device, silently install/uninstall arbitrary apps, or grant themselves privileged permissions.
- Reconnection may restore an authenticated transport, but it must not restore sensitive capture without fresh platform authorization when Android requires it.

## Planned transport

A later milestone may use WebRTC for media and an authenticated signaling service for session setup. Device pairing should use a short-lived one-time code or QR code, with key confirmation on both devices. Relay credentials and private keys must never be committed to this repository.
