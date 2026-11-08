# Implementation status

This branch is a development foundation for the next major RKRemote iteration.

## Implemented in this branch

- Versioned JSON message envelope with an allow-list of message types and a 64 KiB limit.
- One-time, five-minute pairing challenge generator with single-attempt verification and PBKDF2-HMAC-SHA256 derivation.
- TLS-only WebSocket transport requiring a caller-supplied SHA-256 certificate pin.
- Central Android runtime-permission and notification-listener checks.
- Visible foreground-session notification with a local stop action.
- Pairing-code UI prototype that explicitly reports that no peer has connected.
- Existing local setup and encrypted notification store remain in place.

## Not implemented / not production-ready

- A deployed relay server, controller application, or end-to-end authenticated handshake.
- Durable paired-device identity and key rotation.
- Remote command execution, screen capture/input, camera/microphone streaming, file transfer, contacts/call-log transfer, or push-to-talk.
- Automated reconnect/backoff and network diagnostics.
- Device-farm tests, physical-device tests, release signing, and security review.

The transport is a generic pinned WSS client, not a complete relay protocol. No privileged operation should be wired to inbound messages until peer authentication, authorization, replay protection, capability consent, and session revocation are implemented and tested.
