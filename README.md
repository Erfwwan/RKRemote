# RKRemote

Consent-based Android remote-device management for devices owned by the operator or devices for which the operator has explicit administrative authorization.

## Current internet transport and pairing

- **Cloudflare encrypted relay:** both phones connect to a Cloudflare Worker Durable Object room. The Worker forwards encrypted binary frames only.
- **Noise XX + ChaCha20-Poly1305:** authenticates the two devices and encrypts application traffic end-to-end after both owners compare and approve the six-digit SAS.
- **Compression and framing:** payloads are compressed only when useful, then fragmented below the Worker 12 KiB frame limit and encrypted fragment-by-fragment.
- **No ICE/STUN/TURN setup:** no direct WebRTC path is required. All application data travels through Cloudflare.
- **Automatic pairing invitation:** the target creates a random room and shares an RKRemote invitation link; the controller opens it and receives the Worker URL, room ID and role automatically.

## Cloudflare account integration

The Android source now includes OAuth Authorization Code + PKCE login, encrypted token storage using Android Keystore, account selection, and Cloudflare API deployment of the Worker and Durable Object binding. It does not ask for a Cloudflare password, Global API Key, or API token.

One-time setup is still required: a Cloudflare account administrator must register a private mobile OAuth client and provide its public Client ID to the build. The redirect URI and scopes are documented in [Cloudflare OAuth setup](docs/CLOUDFLARE_OAUTH_SETUP.md). Until that Client ID is configured, use the manual Deploy button.

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/Erfwwan/RKRemote/tree/feature/remote-agent-foundation/cloudflare/worker)

## Feature boundaries

The source includes owner-approved screen viewing/input, owner-selected SAF file browsing and transfer, notification snapshots, call/SMS/location reports, and owner-started camera/microphone/PTT functions. Each capability remains separately granted. Camera, microphone and screen capture still require local owner action, Android permissions, visible foreground notifications and local stop controls.

The app does not bypass Android permissions, silently start sensors, hide its session, or access arbitrary private app storage. Call-log/SMS/background-location access may be restricted by Android version and install channel.

## Important transport trade-offs

- All traffic is relayed through Cloudflare. This is more predictable across restrictive NATs than direct P2P, but increases Worker bandwidth usage and can incur usage charges or limits.
- Cloudflare can observe IP addresses, room IDs, connection timing, frame sizes and traffic volume, but not the encrypted app payloads.
- Compression is performed before encryption and only kept when it saves at least 32 bytes. Already-compressed images/audio/video generally receive little or no size reduction.
- Noise protects app payloads end-to-end. WSS protects each phone-to-Cloudflare hop. The Worker never receives plaintext app messages.
- The room ID is not device authentication. Peer identity verification and owner approval remain mandatory.
- All OAuth tokens are encrypted at rest using Android Keystore. A Cloudflare OAuth grant can be revoked from the Cloudflare account.

## Build and verification

Requirements: Android Studio, JDK 17, Gradle 8.11.1 and Android SDK 35.

The source changes in this milestone have **not** been built or tested. OAuth deployment has not been run against a real Cloudflare account. Physical tests on both phones remain deferred to the final stage. A successful build would not by itself prove transport correctness or production readiness.

See:
- [Cloudflare Worker deployment](cloudflare/worker/README.md)
- [Cloudflare OAuth setup](docs/CLOUDFLARE_OAUTH_SETUP.md)
- [Encrypted relay protocol](docs/CLOUDFLARE_RELAY_TRANSPORT.md)
- [Security model](docs/SECURITY.md)
- [Implementation status](docs/IMPLEMENTATION_STATUS.md)
