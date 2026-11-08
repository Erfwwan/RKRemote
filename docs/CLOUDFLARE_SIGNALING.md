# Cloudflare encrypted relay

The active architecture is no longer signaling-only. The Cloudflare Worker is the sole internet transport relay for the Android app.

- Both devices connect to one SQLite-backed Durable Object room.
- The target joins first; the controller joins using an invitation URI created by the target.
- The Worker accepts one target and one controller and forwards bounded binary frames only.
- Noise XX authenticates the two device identities. Both owners compare the same six-digit SAS and approve locally.
- After approval, every application message is optionally compressed before encryption, fragmented below the 12 KiB Worker limit, encrypted fragment-by-fragment with Noise ChaCha20-Poly1305, and forwarded unchanged by the Worker.
- The Worker does not parse, log, store, or interpret app payloads. It can still observe IP addresses, room ID, role, timing, frame sizes and total traffic volume.

No STUN, TURN, ICE, WebRTC or custom network profile is required. All application data traverses Cloudflare, which reduces NAT traversal failures but consumes Worker bandwidth and may incur plan limits or charges.

See [Cloudflare relay transport](CLOUDFLARE_RELAY_TRANSPORT.md) and [Worker deployment](../cloudflare/worker/README.md).
