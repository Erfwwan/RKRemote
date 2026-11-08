# Cloudflare signaling and secure Internet transport

## Decision

Cloudflare Workers + a SQLite-backed Durable Object provide an ephemeral WebSocket rendezvous. This is signaling only: it is not a media relay, TURN service, DHT, or authoritative application server.

The target generates a cryptographically random 256-bit room ID and connects first with role `target`. The controller joins with role `controller`. A room accepts at most one connection per role, requires the target to arrive first, forwards bounded opaque binary frames, and expires after ten minutes.

## Wire protocol and privacy boundary

Client-to-worker messages must be binary WebSocket frames containing Noise handshake or transport ciphertext. Each frame is 1–12 KiB. The worker forwards bytes unchanged and does not parse, log, store, or interpret frame bytes. Client text frames are rejected. The worker itself emits only small JSON lifecycle events: `peer-joined`, `peer-left`, and `peer-not-ready`.

Do not send plaintext SDP/ICE, commands, session bearer tokens, notification contents, files, screenshots, camera frames, microphone audio, or PTT audio through this service. SDP/ICE is exchanged inside the authenticated Noise channel. Once WebRTC is established, application messages travel over the DTLS-protected WebRTC DataChannel, not through Cloudflare.

WSS protects each device-to-Cloudflare connection; it is not itself end-to-end encryption. End-to-end signaling protection is provided by RK-SecureLink using Noise XX with X25519, ChaCha20-Poly1305, and SHA-256, with a P-256 Android-Keystore signing identity binding the Noise static key. The owners must compare the six-digit short authentication string and peer identity and approve locally before application data is enabled.

The 256-bit room ID is a temporary bearer invitation, not a substitute for identity verification. Share it only with the intended peer. Cloudflare can still observe room ID, source IPs, roles, timing, duration, and traffic volume. The Worker must never be treated as a trusted command coordinator.

## Android implementation

- `CloudflareSignalingClient.kt` requires WSS, a Worker origin URL without embedded credentials/custom paths/query/fragment, a 256-bit lowercase-hex room ID, and bounded binary frames. It accepts only known room lifecycle events as text.
- `SecureLinkHandshake.kt` implements the three-message Noise XX handshake. P-256 signatures bind the long-lived RKRemote peer identity to each Noise X25519 static key. A local owner must approve the peer ID and SAS on each device before transport encryption/decryption is enabled.
- `SecureLinkCloudflareSession.kt` carries only opaque Noise ciphertext through the Worker.
- `SecureInternetPeer.kt` coordinates the authenticated signaling session and WebRTC peer. SDP and ICE are carried inside Noise-protected application messages.
- `SecureWebRtcPeer.kt` creates the WebRTC DataChannel. WebRTC DTLS protects application payloads after the peer-identity-bound SDP fingerprint is verified. STUN is configured by default; TURN is optional and must be supplied by the user.
- Remote commands remain disabled until the target owner explicitly grants per-session capabilities. The target owner can revoke all capabilities. Screen capture, camera, microphone, PTT speaker, Notification Access, Accessibility, and SAF folder access each retain their separate local Android permission/owner-start requirement.
- The app has a dedicated secure Internet page in the hamburger navigation. The target must connect first; the controller then joins using the same room ID.

## Deploy

From `cloudflare/worker` with Wrangler installed and authenticated:

```sh
npx wrangler deploy
```

The deployed Worker URL becomes the `baseWssUrl` setting in the Android app, for example `wss://rkremote-signaling.<account-subdomain>.workers.dev`. Do not commit account credentials or API tokens.

Durable Objects support WebSocket sessions and hibernation. Free-plan limits and quotas can change; verify the current Cloudflare dashboard before deployment. This signaling service is not guaranteed to be permanently available. It does not relay media or guarantee NAT traversal. If direct ICE fails, the user must configure a separate TURN service; none is hosted by this repository.

## Validation status

The Worker and Android secure transport are source-integrated. GitHub Actions verifies JVM unit tests and debug APK assembly; check the latest branch workflow for the exact current revision. The Worker has not yet been deployed from the user's Cloudflare account, and no live WebSocket, real NAT traversal, TURN fallback, network-switching, or two-device Android test has been completed. Those deployment and physical-device checks are deliberately reserved for the final validation stage.

Do not expose the legacy LAN TCP port 18765 to the public internet. The Internet transport must use the secure signaling + Noise + WebRTC path; do not silently fall back to plaintext LAN transport.
