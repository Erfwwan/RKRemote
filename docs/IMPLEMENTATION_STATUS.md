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
- LAN agent/controller on TCP 18765 with temporary bearer token; health check and owner-selected folder metadata listing, plus a single normalized tap command when the target owner has manually enabled RKRemote Accessibility. Client handling is bounded (four active workers, sixteen queued clients, header limits and socket timeouts); controller response reads are capped at 16 MiB and IPv4 octets are validated.
- Owner-approved screen capture through Android MediaProjection consent, visible mediaProjection foreground service, in-memory JPEG frame (maximum dimension 1600 px), and authenticated `/screen.jpg` snapshot endpoint; controller can request one snapshot or poll JPEG snapshots at roughly one per second for a low-rate preview (not continuous video). A separate AccessibilityService requires manual enablement in Android settings and accepts authenticated normalized single-tap commands only.
- JVM tests for the encryption envelope and command authorization gate.
- Existing local setup and encrypted notification store remain in place.

## Not implemented / not production-ready

- Internet relay, cross-network connection, TLS on the LAN socket, or end-to-end authenticated key exchange. The LAN token is a temporary bearer secret sent over the local network; use only on a trusted private Wi-Fi network. The AES-GCM envelope is not yet connected to the LAN transport.
- Durable paired-device identity and key rotation.
- Continuous screen video (the current preview is periodic JPEG polling), swipe/typing and full remote input (only a single tap prototype exists), camera/microphone streaming, file download/upload, contacts/call-log transfer, and push-to-talk. Screen capture is currently a single JPEG snapshot per controller request; it is not a live video stream. The remote file feature lists names and metadata only; it does not download file contents.
- Automated reconnect/backoff and network diagnostics. The LAN bearer token and snapshots still travel without TLS; worker bounds reduce local resource-exhaustion risk but do not make the protocol safe on untrusted networks.
- Device-farm tests, physical-device tests, release signing, and security review.

The WSS transport, encryption utility, and command gate remain separate foundations. The executable LAN path is a deliberately small TCP protocol with a temporary token; it is not suitable for untrusted networks or internet exposure. Screen snapshots are also sent without TLS and may contain sensitive information visible on the target display. No privileged Android operation is executed from inbound network requests. No privileged operation should be wired to inbound messages until peer authentication, authorization, replay protection, capability consent, and session revocation are integrated and tested.
