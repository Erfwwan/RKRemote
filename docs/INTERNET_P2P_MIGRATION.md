# RKRemote internet transport status

## Active architecture

The executable Android connection path in this branch is a **Cloudflare Durable Object encrypted relay**, not direct P2P.

1. The target creates a fresh 256-bit random room ID and joins its own Cloudflare Worker over WSS.
2. The target shares an RKRemote invitation URI with the intended controller.
3. The controller joins the same room. The Worker permits one target and one controller.
4. Both devices run the Noise XX handshake and bind the session keys to their stable device identities.
5. Both owners compare the six-digit short authentication string and approve the peer locally.
6. Application messages are bounded, optionally compressed before encryption, fragmented below the Worker frame limit, and encrypted with Noise ChaCha20-Poly1305.
7. The Worker forwards opaque binary frames. It does not parse or store application payloads.

All app traffic, including reports, files and media, traverses Cloudflare. The Worker can observe IP addresses, room IDs, roles, timing, frame sizes and traffic volume. It cannot read the encrypted app payloads. This is a relay architecture and must not be described as P2P.

## Device identity and consent

- Stable device identity keys are retained by Android Keystore.
- The authenticated peer identity is checked as part of the secure handshake.
- Owners compare the SAS and approve the peer on both devices.
- The target owner separately grants each remote capability.
- Persistent remember-me grants are limited to selected report capabilities and are encrypted at rest.
- Camera, microphone and screen capture require local owner action and visible Android foreground/system consent.
- Android permissions and special settings remain revocable by the device owner.

## Direct P2P / WebRTC status

Direct ICE/STUN/TURN/WebRTC transport is **not part of the active connection path** in this branch. The standalone WebRTC implementation and P2P record classes are experimental/historical code and are not invoked by the current Cloudflare session UI. Do not configure STUN/TURN or claim direct P2P connectivity.

A future direct-transport project would need a separately reviewed migration that preserves the authenticated device identity, SAS approval, capability gates, bounded framing, and a safe relay fallback. It must not silently switch transports or weaken encryption.

## Operational limitations

- Normal relay use requires each user to deploy their own Worker and enter its WSS address.
- An API Token is optional and is only used to update an already-deployed Worker.
- Cloudflare bandwidth and Durable Object usage limits/pricing apply.
- A lost WebSocket currently requires a new secure session and fresh peer approval; automatic reconnect/resume is not claimed.
- SMS and call-log permissions can be restricted by Android or distribution policy.
- Background location scheduling is subject to Android power management and may run later than the requested 15-minute interval.
- Worker deployment and in-app API-token updates have not yet been tested against a real Cloudflare account in this source milestone.

## Verification status

The code has not been built or run through the Android test suite in this review. Physical two-device testing, Cloudflare deployment validation, hostile-network tests, and an independent security review remain outstanding. Source-level changes do not establish production readiness.
