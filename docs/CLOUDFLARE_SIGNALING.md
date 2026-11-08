# Cloudflare signaling milestone

## Decision

Use Cloudflare Workers + a SQLite-backed Durable Object as an ephemeral WebSocket rendezvous. This is **signaling only**, not a media relay and not the P2P transport.

The target device generates a cryptographically random 256-bit room ID and connects first with role `target`. The controller joins with role `controller`. A room accepts at most one connection per role, requires the target to arrive first, forwards only the allow-listed WebRTC signaling message types, and expires after ten minutes.

## Message boundary

Allowed message types: `offer`, `answer`, `ice`, `ready`, `reject`, `bye`. Maximum signaling message size is 12 KiB.

Do not send commands, session bearer tokens, notification contents, files, screenshots, camera frames, microphone audio, or PTT audio through this service. Cloudflare can observe signaling metadata and the SDP/ICE content. Signaling authentication and peer identity binding must be added before trusting received descriptions or candidates.

The 256-bit room ID is a temporary bearer invitation, not a substitute for authenticated pairing. The target owner must share it only with the intended controller and retain a local cancel/stop action.

## Android client boundary

`CloudflareSignalingClient.kt` provides WSS connection, bounded JSON signaling messages, and room ID generation. It is not yet wired into the Compose UI and does not itself create a WebRTC PeerConnection. No P2P connection is established by this milestone.

The next Android stage is to add a maintained Android WebRTC implementation, exchange signed peer identities, implement ICE offer/answer/candidates, and verify a direct data channel between two physical devices on different networks. Existing LAN TCP port 18765 remains private-LAN-only and must never be exposed publicly.

## Deploy

From `cloudflare/worker` with Wrangler installed and authenticated:

```sh
npx wrangler deploy
```

The deployed Worker URL becomes the `baseWssUrl` setting in the Android app, for example `wss://rkremote-signaling.<account-subdomain>.workers.dev`. Do not commit account credentials or API tokens.

Durable Objects support WebSocket sessions and hibernation. Free-plan limits and quotas can change; verify the current Cloudflare dashboard before deployment. This signaling service is not guaranteed to be permanently available.

## Validation status

Source scaffold only. No Cloudflare account deployment, Android build, WebSocket integration test, or two-device test is claimed. The app still uses its old LAN transport for existing features.
