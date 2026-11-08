# RK-SecureLink v1 — authenticated encrypted relay

## Current status

The Android source contains the Noise XX handshake, encrypted X25519 static-key storage, signed P-256 identity binding, six-digit SAS confirmation, and a Cloudflare session bridge. The active transport path now carries application data over the same Cloudflare WebSocket relay after both owners approve the authenticated peer.

Application payloads are optionally DEFLATE-compressed when that saves at least 32 bytes, framed and fragmented below the Worker's 12 KiB binary-frame limit, then each fragment is encrypted using the Noise transport cipher (ChaCha20-Poly1305). The receiver validates fragment metadata, reassembles within strict bounds, and decompresses only when the authenticated envelope says it is compressed.

## Cryptographic suite

- Noise protocol: Noise_XX_25519_ChaChaPoly_SHA256.
- Ephemeral/static DH: X25519.
- AEAD: ChaCha20-Poly1305.
- Hash/HKDF: SHA-256 and Noise-defined HKDF.
- Long-lived device identity: P-256 ECDSA key in Android Keystore.
- Signed binding: P-256 identity signs the X25519 Noise static public key and peer identity.
- Fresh Noise handshake per new connection. Do not persist session transport keys.

Torann 0.3.1 is not independently audited and explicitly does not claim production readiness. Obtain an independent security review before relying on the protocol for high-risk data.

## Pairing and authorization

1. Target creates a fresh random 256-bit room ID and opens its Worker WebSocket.
2. Target shares an RKRemote invitation URI containing the Worker origin and room ID.
3. Controller opens the invitation; app fills the Worker URL, room and controller role automatically.
4. Peers perform Noise XX and verify signed identity bindings.
5. Both owners compare the exact six-digit SAS and peer ID and explicitly approve.
6. Only after mutual approval can application frames be sent.
7. The target owner separately grants each capability. Camera, microphone and screen capture still require local owner action and visible Android foreground controls.

The room ID is a rendezvous locator, not an authentication key. Never bypass identity verification or SAS approval.

## Relay frame format and bounds

- Worker maximum binary frame: 12 KiB.
- Noise application marker + AEAD tag: 17 bytes.
- Maximum plaintext fragment sent to the Noise session: 12 KiB minus 17 bytes.
- Logical application message maximum: 64 KiB.
- Reassembly uses bounded in-flight messages, strict fragment count and length checks, duplicate rejection and expiry.
- Compression runs before encryption; already-compressed payloads are sent uncompressed if DEFLATE does not materially reduce size.

## Privacy boundary

Cloudflare sees IP addresses, room ID, role, connection timing, frame sizes and traffic volume. It forwards opaque Noise ciphertext and does not persist application frames. WSS additionally protects each phone-to-Cloudflare hop; WSS is not a replacement for end-to-end Noise encryption.

## Trade-offs and remaining verification

All app traffic, including media and files, traverses Cloudflare. This avoids direct ICE/STUN/TURN dependencies but increases Worker bandwidth use and may increase latency. Automatic WebSocket reconnect/resume is not implemented; a lost connection requires a fresh handshake and owner approval.

The source changes have not been Android-built or tested in this milestone. JVM codec/handshake tests are not a substitute for Android runtime, two-device, network-failure, large-file, media, long-duration or independent security tests.
