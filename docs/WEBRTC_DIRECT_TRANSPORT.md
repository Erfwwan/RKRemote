# Deprecated: WebRTC direct transport

This guide describes the previous direct WebRTC/ICE/STUN/TURN transport and is retained only as historical implementation context.

The active connection path is now the **Cloudflare encrypted relay**. RKRemote no longer asks users to configure STUN, TURN, ICE, SDP or WebRTC. All application traffic is fragmented, optionally compressed before encryption, and protected end-to-end by Noise ChaCha20-Poly1305 before the Cloudflare Worker forwards it.

See [Cloudflare encrypted relay transport](CLOUDFLARE_RELAY_TRANSPORT.md) and [Cloudflare Worker deployment](../cloudflare/worker/README.md) for the current architecture.
