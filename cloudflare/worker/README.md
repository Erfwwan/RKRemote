# RKRemote encrypted Cloudflare relay Worker

This Worker is the **transport relay**, not just a signaling rendezvous. Both Android devices connect to the same Durable Object room. The Worker forwards opaque binary frames byte-for-byte. The Android clients encrypt every application frame end-to-end with the established Noise XX transport before sending it.

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/Erfwwan/RKRemote/tree/feature/remote-agent-foundation/cloudflare/worker)

## Deploy

1. Open the **Deploy to Cloudflare** button above and sign in to your own Cloudflare account.
2. Review the Worker project and deploy it. Approve the `SignalRoom` Durable Object configuration/migration when Cloudflare requests it.
3. Copy the resulting `workers.dev` address and enter it in RKRemote as `wss://...`.
4. Normal relay use does not require an API Token. If you later want RKRemote to update the existing Worker source from inside the app, create a separate API Token with **Workers Editor** access scoped only to the existing `rkremote-signaling` Worker. Enter that token and your Account ID in the app. The app does not need your Cloudflare password or Global API Key.

The app's token update action only uploads the current Worker module to an already-existing script named `rkremote-signaling`; it does not create accounts, enumerate accounts, or change account-level workers.dev subdomains. If you only need the relay, leave the API Token field empty.

The Worker subdirectory is self-contained: it includes src/index.ts, src/index.js, wrangler.toml, and package.json. Alternatively, deploy manually from this directory with `npx wrangler deploy`.

## Wire behavior

- WebSocket path: /signal/<64-character-lowercase-hex-room-id>?role=target or ?role=controller.
- Exactly one target and one controller socket may occupy a room.
- The target must connect first.
- Only binary frames from 1 byte through 12 KiB are accepted from clients. Text client frames are rejected.
- The Worker forwards binary frames opaquely; it does not parse, log, persist, or interpret their bytes.
- Small Worker-generated lifecycle events (peer-joined, peer-left, peer-not-ready) are plain control messages and contain no app content.
- A room expires after 10 minutes without traffic. Valid encrypted app frames and a tiny encrypted heartbeat every 60 seconds refresh the inactivity lease once both owners have approved.
- Each room has at most two connected devices.

## Encryption and privacy

- Noise XX authenticates the paired device identities and derives fresh session transport keys.
- Each application message is optionally DEFLATE-compressed only when compression materially reduces its size, then fragmented to fit the 12 KiB Worker frame limit.
- Every fragment is encrypted independently with Noise ChaCha20-Poly1305 before it reaches the Worker.
- The Worker sees source IPs, room ID, role, connection timing, frame sizes and traffic volume. It cannot read commands, notifications, file contents, reports, location records, or media payloads.
- The Worker does not store message bodies. Durable Object storage is used only for room lifecycle/alarm state, not app payloads.
- The room ID is an invitation locator, not a substitute for Noise authentication or the six-digit SAS check. Both owners must compare the same SAS and approve the peer locally.

## Operational limits

All application traffic (including files and media) passes through the Worker. This avoids dependence on direct ICE/STUN/TURN connectivity, but consumes Workers/Durable Objects bandwidth and may be subject to Cloudflare plan limits or pricing. The relay is not an unlimited free bandwidth service.

The Android app must remain active with its visible session notification while the current session is in use. Network loss requires a fresh connection and a fresh peer approval in this source milestone.

This implementation has not undergone an independent security audit or physical two-device acceptance testing. Do not treat source integration as production certification.
