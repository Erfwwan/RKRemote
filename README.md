# RKRemote

Consent-based Android remote-device management for devices owned by the operator or devices for which the operator has explicit administrative authorization.

## Current internet transport and pairing

- **Cloudflare encrypted relay:** both phones connect to a Cloudflare Worker Durable Object room. The Worker forwards encrypted binary frames only.
- **Noise XX + ChaCha20-Poly1305:** authenticates the two devices and encrypts application traffic end-to-end after both owners compare and approve the six-digit SAS.
- **Compression and framing:** payloads are compressed only when useful, then fragmented below the Worker 12 KiB frame limit and encrypted fragment-by-fragment.
- **No ICE/STUN/TURN setup:** no direct WebRTC path is required. All application data travels through Cloudflare.
- **Automatic pairing invitation:** the target creates a random room and shares an RKRemote invitation link; the controller opens it and receives the Worker URL, room ID and role automatically.

## Cloudflare account integration

Each user deploys a separate Worker into their own Cloudflare account. **GitHub account linking is not required.** The old Cloudflare Deploy button used GitHub's import integration; instead, download the public source ZIP and deploy the included Worker with Wrangler CLI:

```sh
cd cloudflare/worker
npm install
npx wrangler login
npx wrangler deploy
```

Wrangler authenticates directly with Cloudflare. The Worker source is available in the [public source ZIP](https://github.com/Erfwwan/RKRemote/archive/refs/heads/feature/remote-agent-foundation.zip). On Android, the same commands can be run from Termux after installing Node.js and extracting the ZIP.

RKRemote then needs only the Worker WSS address for normal pairing and encrypted relay operation. An optional, user-owned Cloudflare API Token can be entered in the app to update the already-created `rkremote-signaling` Worker. The token is encrypted locally with AES-GCM and an Android Keystore key; it is not shared with the paired phone. The token is optional: users who only want to connect can leave it blank.

Create a token only if you want in-app Worker updates. Use **Workers Editor** access scoped to the existing `rkremote-signaling` Worker only. See [Cloudflare manual setup](docs/CLOUDFLARE_SETUP.md).

## Feature boundaries

The source includes owner-approved screen viewing/input, owner-selected SAF file browsing and transfer, notification snapshots, call/SMS/contact/location reports, and owner-started single-photo camera, microphone and PTT functions. Each capability remains separately granted. Camera, microphone and screen capture still require local owner action, Android permissions, visible foreground notifications and local stop controls.

The app does not bypass Android permissions, silently start sensors, hide its session, or access arbitrary private app storage. Call-log/SMS/background-location access may be restricted by Android version and install channel.

## Important transport trade-offs

- All traffic is relayed through Cloudflare. This is more predictable across restrictive NATs than direct P2P, but increases Worker bandwidth usage and can incur usage charges or limits.
- Cloudflare can observe IP addresses, room IDs, connection timing, frame sizes and traffic volume, but not the encrypted app payloads.
- Compression is performed before encryption and only kept when it saves at least 32 bytes. Already-compressed images/audio/video generally receive little or no size reduction.
- Noise protects app payloads end-to-end. WSS protects each phone-to-Cloudflare hop. The Worker never receives plaintext app messages.
- The room ID is not device authentication. Peer identity verification and owner approval remain mandatory.
- The optional user API Token is encrypted at rest using Android Keystore. Removing it from the app does not revoke it at Cloudflare; revoke it separately in Cloudflare when no longer needed.

## Build and verification

Requirements: Android Studio, JDK 17, Gradle 8.11.1 and Android SDK 35.

The source changes in this milestone have **not** been built or tested. API-token-based Worker update has not been run against a real Cloudflare account. Physical tests on both phones remain deferred to the final stage. A successful build would not by itself prove transport correctness or production readiness.

See:
- [Cloudflare Worker deployment](cloudflare/worker/README.md)
- [Cloudflare setup](docs/CLOUDFLARE_SETUP.md)
- [Encrypted relay protocol](docs/CLOUDFLARE_RELAY_TRANSPORT.md)
- [Security model](docs/SECURITY.md)
- [Implementation status](docs/IMPLEMENTATION_STATUS.md)
