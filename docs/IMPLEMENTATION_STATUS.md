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
- SAF document repository limited to the tree URI selected by the device owner; bounded file reads and permission-checked deletion. The LAN agent now lists direct-child document IDs, downloads selected direct-child files, and accepts uploads into the selected tree only; transfer limit is 8 MiB per file, names are sanitized, existing files are not overwritten, and the owner-selected SAF grant is the only storage boundary.
- LAN agent/controller on TCP 18765 with temporary bearer token; health check and owner-selected folder metadata listing, plus normalized tap, swipe, and long-press gestures and focused editable-field text insertion when the target owner has manually enabled RKRemote Accessibility. Client handling is bounded (four active workers, sixteen queued clients, header limits and socket timeouts); controller response reads are capped at 16 MiB and IPv4 octets are validated.
- Owner-approved screen capture through Android MediaProjection consent, visible mediaProjection foreground service, in-memory JPEG frame (maximum dimension 1600 px), and authenticated `/screen.jpg` snapshot endpoint;\n- Owner-started camera capture through Android CAMERA permission and a dedicated camera foreground service with a persistent stop notification; low-rate JPEG frames (up to 1280×720) are held in memory and read through authenticated `/camera.jpg`. Remote requests cannot start the camera.\n- Owner-started microphone capture through RECORD_AUDIO permission and a dedicated microphone foreground service with a persistent stop notification; mono 16 kHz / 16-bit PCM chunks are held briefly in memory and polled through authenticated `/audio/chunk`. Remote requests cannot start the microphone. The controller plays received chunks using AudioTrack. controller can request one snapshot or poll JPEG snapshots at roughly one per second for a low-rate preview (not continuous video). A separate AccessibilityService requires manual enablement in Android settings and accepts authenticated normalized gestures (tap, swipe, long press) and text insertion into the currently focused editable field. The controller screen image supports direct tap and long press; swipe is currently entered with normalized start/end coordinates. Text insertion uses ACTION_SET_TEXT rather than simulated key events.
- JVM tests for the encryption envelope and command authorization gate.
- Existing local setup and encrypted notification store remain in place.

## Not implemented / not production-ready

- Internet relay, cross-network connection, TLS on the LAN socket, or end-to-end authenticated key exchange. The LAN token is a temporary bearer secret sent over the local network; use only on a trusted private Wi-Fi network. The AES-GCM envelope is not yet connected to the LAN transport.
- Durable paired-device identity and key rotation.
- Continuous screen video (the current preview is periodic JPEG polling), keyboard-event emulation and full remote input (gesture and focused-field text insertion are now implemented, but not yet device-validated), continuous camera video, production-grade/low-latency audio streaming, file download/upload, contacts/call-log transfer, and push-to-talk. Screen capture is currently a single JPEG snapshot per controller request; it is not a live video stream. File transfer now supports direct-child download and upload in the owner-selected SAF folder with an 8 MiB cap; subfolder navigation, larger/resumable transfers, and camera/microphone and file-transfer physical-device validation remain outstanding.
- Automated reconnect/backoff and network diagnostics. The LAN bearer token and snapshots still travel without TLS; worker bounds reduce local resource-exhaustion risk but do not make the protocol safe on untrusted networks.
- Device-farm tests, physical-device tests, release signing, and security review.

The WSS transport, encryption utility, and command gate remain separate foundations. The executable LAN path is a deliberately small TCP protocol with a temporary token; it is not suitable for untrusted networks or internet exposure. Screen snapshots are also sent without TLS and may contain sensitive information visible on the target display. No privileged Android operation is executed from inbound network requests. No privileged operation should be wired to inbound messages until peer authentication, authorization, replay protection, capability consent, and session revocation are integrated and tested.


## Camera and microphone prototype boundary

Camera and microphone capture must be started by the owner from the target phone while the app is visible and after the corresponding Android runtime permission is granted. Each sensor runs in its own foreground service with a persistent notification and a stop action. The LAN endpoints only expose frames or queued audio chunks from a service that is already running; they cannot switch either sensor on. Camera frames and PCM audio chunks are memory-only and are discarded when capture stops. The LAN transport is still plaintext bearer-token HTTP on trusted Wi-Fi only; do not use these media endpoints on public networks or expose port 18765 to the internet. Audio currently uses short polling chunks and is not a jitter-buffered, lossless, low-latency voice transport. Physical-device validation is still required.
