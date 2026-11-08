# RKRemote Internet P2P migration

## Target architecture

The controller and target are peers with stable cryptographic identities. The DHT is used only to discover signed, short-lived peer advertisements. It never stores credentials, commands, notification contents, files, camera frames, screen video, or audio.

Preferred connection order:

1. Discover the target's signed advertisement through a public, distributed Kademlia DHT.
2. Attempt a direct authenticated peer connection using NAT discovery and hole punching.
3. Upgrade the connection to an authenticated encrypted channel and verify the paired peer identity.
4. If direct connectivity fails, offer/attempt a temporary community relay path. Relay traffic remains end-to-end encrypted; relay nodes must not receive plaintext payloads or session keys.
5. If neither direct nor relay connectivity is available, report offline. Do not silently downgrade to plaintext HTTP.

There is no RKRemote-operated central application server. A small set of public DHT bootstrap peers may be used to join the distributed network; bootstrap peers are entry points, not authoritative coordinators. A temporary relay is a transport fallback, not a central data store.

## Stage 1 — device identity and signed discovery record

Implemented in this pass:

- A stable P-256 EC signing key is generated and retained by Android Keystore.
- The private key is non-exportable and is never written to preferences or source control.
- Peer ID is SHA-256 of the encoded public key.
- Peer advertisements bind peer ID, public key, monotonic sequence, issue/expiry times, and transport multiaddresses with ECDSA/SHA-256.
- Advertisements are bounded to 8 KiB, 8 addresses, and a maximum 10-minute lifetime.
- Parsing verifies the signature, peer-ID/public-key binding, expiry, clock skew, size and address syntax.
- Unit tests cover valid round-trip, tampering, expiry, overlong TTL, and invalid address syntax.

This stage creates the signed identity/record format only. It does **not** yet publish or retrieve records from a DHT, open a public listener, perform NAT traversal, establish an internet connection, or activate any remote capability.

## Stage 2 — DHT integration

- Select and pin a maintained Android-compatible libp2p implementation only after confirming Kademlia DHT, peer identity, Android API 26+, UDP behavior, and dependency supply-chain support.
- Use bootstrap peers only to enter the DHT; keep a configurable list and support multiple independent peers.
- Publish only the signed, short-lived peer advertisement under a deterministic key derived from the target Peer ID.
- Validate signatures and monotonic sequence on every retrieved record; ignore expired or older records.
- Never publish static bearer tokens, session keys, personal data, LAN-only addresses, or media metadata.
- Add bounded lookup concurrency, rate limits, record refresh, stale-record cleanup, and backoff.

The current JVM libp2p candidate is not yet approved for production: its published component matrix marks Kademlia DHT as not implemented and Circuit Relay v2 as prototype/beta. Do not add it as a dependency and claim DHT support until this gap is resolved.

## Stage 3 — direct internet transport

- Prefer an authenticated direct connection after DHT discovery.
- Use a reviewed NAT traversal implementation (ICE/STUN with UDP hole punching where supported).
- STUN is used for address discovery only; it must not carry application payloads.
- Negotiate fresh session keys using an authenticated handshake bound to the paired public keys and fresh nonces.
- Carry the existing versioned message envelope over a transport-neutral channel; add bounded binary streams for media and file transfer.
- Preserve replay protection, capability grants, owner-visible foreground state, and local stop/revoke controls.
- Keep the existing LAN transport available only as a compatibility path until internet transport passes acceptance tests.

## Stage 4 — temporary encrypted relay fallback

- Try relay only after direct connectivity fails or is explicitly unavailable.
- Prefer independently operated/community libp2p Circuit Relay v2 peers, not a single RKRemote relay.
- Authenticate both endpoints end-to-end before forwarding application data.
- Encrypt all application payloads end-to-end; a relay sees peer IDs, traffic timing and volume, but not plaintext content or session keys.
- Bound relay bandwidth, connection duration and queue sizes. Drop to offline rather than downgrade encryption.
- Relay usage must be visible in connection diagnostics.

## Stage 5 — migrate feature traffic

Migrate screen video, camera, microphone/PTT, notification snapshots, remote input, and SAF file streams only after the secure peer channel is complete. Preserve each feature's existing Android owner-consent requirements. Sensor capture must remain locally started and visibly stoppable; network pairing does not grant Android permissions.

## Acceptance criteria

- Two physical Android devices on different mobile/Wi-Fi networks can pair once and discover each other without manual IP/token entry.
- DHT records are signed, short-lived, replay-resistant, bounded, and contain no secrets.
- Direct path works where NAT topology permits; diagnostics distinguish direct, relay, and offline.
- Relay fallback works without revealing plaintext to the relay.
- Invalid signatures, expired records, replayed messages, revoked peers and stale sequence numbers are rejected.
- Wi-Fi/mobile handover and changing public IP trigger bounded rediscovery/reconnect.
- No public endpoint exposes the legacy plaintext TCP protocol.
- Target retains visible session notification, local emergency stop, and ability to revoke pairing.
- CI plus two-device tests pass before internet access is described as implemented.

## Current security boundary

The existing executable agent still uses plaintext bearer-token HTTP/TCP on port 18765. It is not safe for public internet exposure. Do not configure port forwarding or expose that port. The signed identity work in Stage 1 does not change this fact. No internet P2P transport is currently active.
