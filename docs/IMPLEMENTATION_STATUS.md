# Implementation status

This branch is a development foundation for the next major RKRemote iteration.

## Implemented in this branch

- Versioned JSON message envelope with an allow-list of message types and a 64 KiB limit.
- One-time, five-minute pairing challenge generator with single-attempt verification and PBKDF2-HMAC-SHA256 derivation.
- TLS-only WebSocket transport requiring a caller-supplied SHA-256 certificate pin.
- Central Android runtime-permission and notification-listener checks.
- Visible foreground-session notification with a local stop action.
- Pairing-code UI prototype that explicitly reports that no peer has connected.
- AES-256-GCM payload envelope with random nonces, authenticated associated data, strict size limits, and tamper detection (requires a separately authenticated session key).
- Session command authorization gate requiring an owner-approved active session, exact peer identity, per-capability grants, monotonic sequence numbers, and explicit revocation.
- SAF document repository limited to the tree URI selected by the device owner; bounded file reads and permission-checked deletion.
- JVM tests for the encryption envelope and command authorization gate.
- Existing local setup and encrypted notification store remain in place.

## Not implemented / not production-ready

- A deployed relay server, controller application, or end-to-end authenticated key-exchange / pairing handshake. The AES-GCM envelope is a building block, not proof of a completed E2E secure channel.
- Durable paired-device identity and key rotation.
- Network-connected remote command execution, screen capture/input, camera/microphone streaming, file transfer, contacts/call-log transfer, or push-to-talk. SAF file access is local-only at this stage.
- Automated reconnect/backoff and network diagnostics.
- Device-farm tests, physical-device tests, release signing, and security review.

The transport is a generic pinned WSS client, not a complete relay protocol. The command gate and authenticated-encryption utility are not yet wired to a network handshake or privileged operations. No privileged operation should be wired to inbound messages until peer authentication, authorization, replay protection, capability consent, and session revocation are integrated and tested.
