# Cloudflare signaling milestone

## Decision

Use Cloudflare Workers + a SQLite-backed Durable Object as an ephemeral WebSocket rendezvous. It is signaling only, not a media relay and not the P2P transport.

The target generates a cryptographically random 256-bit room ID and connects first with role `target`. The controller joins with role `controller`. A room accepts at most one connection per role, requires the target to arrive first, forwards bounded opaque binary frames, and expires after ten minutes.

## Wire protocol

Client-to-worker messages must be binary WebSocket frames containing Noise handshake or transport messages. Each frame is 1–12 KiB. The worker forwards bytes unchanged and does not parse, log, store, or interpret them. Client text frames are rejected. The worker itself emits only small JSON lifecycle events: `peer-joined`, `peer-left`, and `peer-not-ready`.

Do not send plaintext SDP/ICE, commands, session bearer tokens, notification contents, files, screenshots, camera frames, microphone audio, or PTT audio through this service. SDP/ICE is to be exchanged inside the authenticated Noise channel after the Noise handshake is complete.

## Security boundary

WSS protects the individual connection to Cloudflare; it is not end-to-end encryption. End-to-end protection is intended to be provided by RK-SecureLink v1 using Noise XX with X25519, ChaCha20-Poly1305, and SHA-256. The Android client currently exposes an opaque binary-frame interface only. It does **not** yet implement Noise cryptography, key binding, WebRTC, or a secure P2P session. Do not claim that this scaffold is end-to-end encrypted.

The 256-bit room ID is a temporary bearer invitation, not a substitute for authenticated pairing. The target owner must share it only with the intended controller and retain a local cancel/stop action. Cloudflare can still observe room ID, source IPs, roles, timing, duration, and traffic volume.

## Android client boundary

`CloudflareSignalingClient.kt` requires WSS, bounds binary frames to 12 KiB, forwards byte arrays without decoding, and accepts only known room lifecycle control events as text. It is not wired into the Compose UI and does not create a WebRTC PeerConnection.

## Deploy

From `cloudflare/worker` with Wrangler installed and authenticated:

```sh
npx wrangler deploy
```

The deployed Worker URL becomes the `baseWssUrl` setting in the Android app, for example `wss://rkremote-signaling.<account-subdomain>.workers.dev`. Do not commit account credentials or API tokens.

Durable Objects support WebSocket sessions and hibernation. Free-plan limits and quotas can change; verify the current Cloudflare dashboard before deployment. This signaling service is not guaranteed to be permanently available.

## Validation status

Source scaffold only. No Cloudflare deployment, Android build, WebSocket integration test, Noise handshake test, WebRTC test, or two-device test is claimed. The app still uses its old LAN transport for existing features. Do not expose TCP port 18765 to the internet.
