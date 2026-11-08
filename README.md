# RKRemote

Consent-based Android remote-device management for devices owned by the operator or devices for which the operator has explicit administrative authorization.

## Current internet transport

The active development branch uses one internet connection method:

- **Cloudflare encrypted relay:** both phones connect to a Cloudflare Worker Durable Object room. The Worker forwards encrypted binary frames only.
- **Noise XX + ChaCha20-Poly1305:** authenticates the two devices, supports forward secrecy for each new session, and encrypts all application traffic end-to-end after both owners compare and approve the six-digit SAS.
- **Compression and framing:** payloads are compressed only when useful, then fragmented below the Worker 12 KiB frame limit and encrypted fragment-by-fragment.
- **No ICE/STUN/TURN setup:** no direct WebRTC path is required. All application data travels through Cloudflare.

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/Erfwwan/RKRemote/tree/feature/remote-agent-foundation/cloudflare/worker)

After deploying, enter only the assigned Worker WSS origin in RKRemote. The target phone creates a random room and shares an app invitation link; the controller opens that link to receive the Worker URL, room ID and role automatically. Both devices must still verify the peer ID and compare/approve the same SAS.

## Feature boundaries

The source includes owner-approved screen viewing/input, owner-selected SAF file browsing and transfer, notification snapshots, call/SMS/location reports, and owner-started camera/microphone/PTT functions. Each capability remains separately granted. Camera, microphone and screen capture still require local owner action, Android permissions, visible foreground notifications and local stop controls.

The app does not bypass Android permissions, silently start sensors, hide its session, or access arbitrary private app storage. Call-log/SMS/background-location access may be restricted by Android version and install channel.

## Important transport trade-offs

- All traffic is relayed through Cloudflare. This is more predictable across restrictive NATs than direct P2P, but increases Worker bandwidth usage and can incur usage charges or limits.
- Cloudflare can observe IP addresses, room IDs, connection timing, frame sizes and traffic volume, but not the encrypted app payloads.
- Compression is performed before encryption and only kept when it saves at least a small threshold. Already-compressed images/audio/video generally receive little or no size reduction.
- Noise protects app payloads end-to-end. WSS protects each phone-to-Cloudflare hop. The Worker never receives plaintext app messages.
- The room ID is not device authentication. Peer identity verification and owner approval remain mandatory.
- No independent security audit, physical two-device validation, long-duration soak test or network-failure test has been completed.

## Build and verification

Requirements: Android Studio, JDK 17, Gradle 8.11.1 and Android SDK 35.

The source changes in this milestone have **not** been built or tested. Physical tests on both phones remain deferred to the final stage. A successful build would not by itself prove transport correctness or production readiness.

See:
- [Cloudflare Worker deployment](cloudflare/worker/README.md)
- [Encrypted relay protocol](docs/CLOUDFLARE_RELAY_TRANSPORT.md)
- [Security model](docs/SECURITY.md)
- [Implementation status](docs/IMPLEMENTATION_STATUS.md)
