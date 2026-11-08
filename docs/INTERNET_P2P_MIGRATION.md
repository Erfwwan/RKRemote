# RKRemote Internet P2P migration

## Target architecture

The controller and target are peers with stable cryptographic identities. Discovery is used only to locate signed, short-lived peer advertisements. It never stores credentials, commands, notification contents, files, camera frames, screen video, or audio.

Preferred connection order:

1. Discover the target's signed advertisement.
2. Attempt a direct authenticated peer connection using ICE/STUN and UDP hole punching where supported.
3. Authenticate the exact paired peer using RK-SecureLink v1.
4. If direct connectivity fails, offer a temporary independently operated/community relay path. Relay traffic remains end-to-end encrypted.
5. If neither direct nor relay connectivity is available, report offline. Never downgrade to plaintext HTTP.

There is no RKRemote-operated central application server. Bootstrap peers are entry points, not authoritative coordinators. A temporary relay is a transport fallback, not a central data store.

## Stage 1 — device identity and signed discovery record

Implemented foundation:

- Stable P-256 EC signing key generated and retained by Android Keystore.
- Private key is non-exportable and is never written to preferences or source control.
- Peer ID is SHA-256 of the encoded public key.
- Peer advertisements bind peer ID, public key, sequence, issue/expiry times, and transport multiaddresses with ECDSA/SHA-256.
- Advertisements are bounded to 8 KiB, 8 addresses, and a maximum 10-minute lifetime.
- Parsing verifies signature, peer-ID/public-key binding, expiry, clock skew, size and address syntax.

This is identity/record code only. DHT publication/lookup, NAT traversal, direct peer transport, relay fallback, and internet access remain unimplemented.

## Stage 2 — DHT integration

- Select and pin a maintained Android-compatible libp2p implementation only after confirming Kademlia DHT, peer identity, Android API 26+, UDP behavior, and dependency supply-chain support.
- Use multiple independent bootstrap peers.
- Publish only signed, short-lived advertisements under a deterministic key derived from target Peer ID.
- Validate signatures and monotonic sequence on every retrieved record; ignore expired or older records.
- Never publish tokens, session keys, personal data, LAN-only addresses, or media metadata.
- Add bounded lookup concurrency, rate limits, refresh, stale-record cleanup, and backoff.

The previously reviewed JVM libp2p candidate is not approved for production DHT/relay support; do not claim Kademlia or Circuit Relay v2 support until verified.

## Stage 3 — authenticated transport and Cloudflare rendezvous

The design and binary rendezvous scaffold are specified in [RK-SecureLink v1](RK_SECURELINK_V1.md). The Cloudflare worker now accepts opaque binary frames only and rejects client text messages. The Android client forwards byte arrays without parsing them.

The cryptographic Noise engine, P-256-to-X25519 key binding, authenticated WebRTC fingerprint exchange, and WebRTC PeerConnection are **not implemented yet**. Therefore this stage is not complete and must not be treated as secure Internet access.

The legacy Cloudflare JSON offer/answer/ICE protocol has been removed from the worker. Do not deploy the worker as a usable secure connection until the handshake engine and peer authentication are implemented and tested.

## Stage 4 — direct transport and relay

- Prefer ICE direct UDP paths. STUN is for address discovery; it does not carry application payloads.
- Bind WebRTC DTLS fingerprints to the peer identity authenticated through Noise.
- Use WebRTC DTLS for DataChannels and SRTP for media. Do not add a redundant application encryption layer over those transports.
- If direct connectivity fails, use an independently operated/community relay only after peer authentication. Relay nodes forward ciphertext and see network metadata, not plaintext or session keys.
- Bound bandwidth, connection duration and queue sizes. Drop to offline rather than downgrade encryption.
- Relay usage must be visible in connection diagnostics.

## Stage 5 — migrate feature traffic

Migrate screen video, camera, microphone/PTT, notification snapshots, remote input, and SAF file streams only after the authenticated peer channel and explicit capability grants are complete. Preserve Android owner-consent requirements. Sensor capture must remain locally started and visibly stoppable.

## Acceptance criteria

- Noise XX official test vectors pass on Android API 26+.
- Two physical Android devices authenticate the exact previously paired identities on separate networks.
- Wrong keys, MITM attempts, replay, malformed frames, stale records, revoked peers, and DTLS fingerprint mismatch are rejected.
- Direct path works where NAT topology permits; diagnostics distinguish direct, relay, and offline.
- Relay fallback never exposes plaintext to relay operator.
- Network handover and changing public IP trigger bounded reconnect.
- No public endpoint exposes the legacy plaintext TCP protocol.
- Target retains visible session notification, local emergency stop, and ability to revoke pairing.
- CI, security review, and two-device tests pass before Internet access is described as implemented.

## Current security boundary

The existing executable agent still uses plaintext bearer-token HTTP/TCP on port 18765. It is not safe for public Internet exposure. Do not configure port forwarding or expose that port. The P2P identity and Cloudflare binary signaling work do not change this fact.
