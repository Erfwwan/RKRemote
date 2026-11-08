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

- Internet relay, cross-network connection, TLS on the LAN socket, or end-to-end authenticated key exchange. The LAN token is a temporary bearer secret sent over the local network; use only on a trusted private Wi-Fi network. The AES-GCM envelope is not yet connected to the LAN transport.
- Durable paired-device identity and key rotation.
- Remote screen capture/input, camera/microphone streaming, file download/upload, contacts/call-log transfer, and push-to-talk. The current remote file feature lists names and metadata only; it does not download file contents.
- Automated reconnect/backoff and network diagnostics.
- Device-farm tests, physical-device tests, release signing, and security review.

The WSS transport, encryption utility, and command gate remain separate foundations. The first executable LAN path is a deliberately small read-only HTTP-like TCP protocol with a temporary token; it is not suitable for untrusted networks or internet exposure. No privileged Android operation is executed from inbound network requests. No privileged operation should be wired to inbound messages until peer authentication, authorization, replay protection, capability consent, and session revocation are integrated and tested.
