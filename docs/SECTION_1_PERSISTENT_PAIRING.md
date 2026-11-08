# Section 1 — persistent identity and internet P2P

## Product scope

RKRemote manages one target phone from one controller phone. The devices are paired explicitly by their owners. After pairing, the controller should rediscover and reconnect without manual IP or bearer-token copying.

The intended network is decentralized P2P:
- Kademlia DHT for discovery of signed, short-lived peer records.
- Direct encrypted peer connection as the preferred data path.
- Temporary, end-to-end encrypted community relay only when direct connectivity fails.
- No RKRemote-operated central server or central media/file store.

## Security and Android boundaries

- The target retains a visible owner-approved foreground service and a local emergency stop.
- The owner can revoke the paired controller at any time.
- Android runtime permissions, MediaProjection, Notification Access, and Accessibility remain controlled by Android and the device owner.
- Pairing does not silently grant or reactivate camera, microphone, screen capture, notifications, files, or Accessibility.
- No plaintext downgrade is permitted. If secure direct/relay transport cannot be established, the connection remains offline.
- DHT records contain public identity and short-lived connection hints only; never publish session keys, bearer tokens, media, notification contents, or file data.

## Implemented — Stage 1

- `P2pIdentityStore.kt` creates a persistent P-256 signing identity in Android Keystore. The private key is non-exportable.
- The peer ID is SHA-256 of the encoded public key.
- `P2pPeerRecord.kt` defines a bounded, versioned, signed ECDSA/SHA-256 advertisement containing peer ID, public key, sequence, issue/expiry times and transport multiaddresses.
- Record verification checks signature, peer-ID/public-key binding, expiry, clock skew, maximum 10-minute lifetime, record size and address syntax.
- `P2pPeerRecordTest.kt` covers valid round-trip, tampering, expiry, invalid TTL and invalid address syntax.

Stage 1 is a cryptographic identity and record-format foundation only. It does not publish/query a DHT, perform NAT traversal, create an internet socket, or make existing LAN routes internet-safe.

## Remaining stages

1. **DHT discovery:** integrate a maintained Android-compatible Kademlia implementation, publish only signed short-lived records, and support multiple bootstrap peers.
2. **Direct transport:** add authenticated NAT traversal/hole punching and a peer channel with fresh session keys, replay protection and bounded binary streams.
3. **Relay fallback:** use independently operated/community Circuit Relay v2 peers only when direct connectivity fails; preserve end-to-end encryption and expose relay status.
4. **Feature migration:** move video, audio, camera, notifications, input and SAF file streams onto the authenticated peer channel.
5. **Revocation and validation:** test peer revocation, replay, key rotation, network changes, reconnect, relay fallback and two physical Android devices.

## Library evaluation

The JVM libp2p implementation was inspected as a possible dependency. Its current component matrix does not list Kademlia DHT as implemented and lists Circuit Relay v2 as prototype/beta. It is not approved as the production transport until these gaps are resolved and Android compatibility is validated. See [the P2P migration plan](INTERNET_P2P_MIGRATION.md).

## Current critical limitation

The executable app still uses the legacy plaintext bearer-token HTTP/TCP agent on port 18765. It remains LAN-only and must not be exposed to the public internet, port-forwarded, or used on untrusted networks. Existing TLS WebSocket and AES-GCM utilities are not integrated into that executable path. The new P2P identity does not change this boundary.

The target-side stop action, foreground notification, Android permission controls and local revocation boundary remain mandatory.
