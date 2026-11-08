# RKRemote encrypted Cloudflare relay Worker

This Worker is the **transport relay**, not just a signaling rendezvous. Both Android devices connect to the same Durable Object room. The Worker forwards opaque binary frames byte-for-byte. Android clients encrypt application frames end-to-end with the established Noise XX transport before sending them.

## Recommended deployment: GitHub Actions

You do **not** need to connect GitHub to Cloudflare or use Cloudflare's GitHub import integration.

1. In your Cloudflare dashboard, create a narrowly scoped API Token with the Workers deployment permissions Wrangler requires. Do not use the Global API Key.
2. Copy your Cloudflare Account ID.
3. In GitHub, open **Settings → Secrets and variables → Actions** for the RKRemote repository.
4. Add repository secrets named `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
5. Run **Actions → Deploy Cloudflare Worker → Run workflow** once.

After the first deployment, every push that changes `cloudflare/worker/**` on `feature/remote-agent-foundation` automatically deploys the updated Worker. The workflow summary reports the Worker address when available. Enter it in RKRemote as `wss://<worker-name>.<your-workers-subdomain>.workers.dev`.

The token stays in GitHub Secrets. It is not committed to the repository, embedded in the APK, printed in logs, or sent to the paired phone.

See [the complete setup guide](../../docs/CLOUDFLARE_SETUP.md) for troubleshooting and manual deployment.

## Manual alternative

If you prefer not to use GitHub Actions, download and extract the public repository ZIP, open a terminal in this directory, and run:

```sh
npm install
npx wrangler login
npx wrangler deploy
```

Wrangler authenticates directly with Cloudflare. This path also does not link a GitHub account.

## Wire behavior

- WebSocket path: `/signal/<64-character-lowercase-hex-room-id>?role=target` or `?role=controller`.
- Exactly one target and one controller socket may occupy a room.
- The target must connect first.
- Only binary frames from 1 byte through 12 KiB are accepted from clients. Text client frames are rejected.
- The Worker forwards binary frames opaquely; it does not parse, log, persist, or interpret their bytes.
- Small Worker-generated lifecycle events (peer-joined, peer-left, peer-not-ready) are plain control messages and contain no app content.
- A room expires after 10 minutes without traffic. Valid encrypted app frames and a tiny encrypted heartbeat refresh the inactivity lease.
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
