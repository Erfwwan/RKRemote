# RKRemote Cloudflare encrypted relay protocol

## Transport flow

1. The target app creates a fresh 256-bit random room ID and connects to the configured Worker over WSS.
2. The target app shares an invitation URI containing only the Worker origin and room ID. The controller opens the URI; RKRemote automatically fills the Worker origin, room ID and controller role.
3. The controller joins the same Durable Object room. The Worker accepts one target and one controller.
4. The devices run the existing Noise XX handshake. Signed P-256 identity bindings authenticate each X25519 static key. Both owners compare the same six-digit short authentication string and explicitly approve the peer identity.
5. Only after mutual approval can the application send data.
6. The application optionally compresses each message when DEFLATE saves at least 32 bytes, prefixes a small versioned envelope, and fragments the result into bounded binary frames.
7. Each fragment is independently encrypted by the Noise transport using ChaCha20-Poly1305 and sent over WSS. The Worker forwards the opaque bytes unchanged.
8. The receiving app authenticates/decrypts each fragment, validates fragment metadata and bounded reassembly, decompresses only if flagged, and then parses the application message.

## Frame limits

- Cloudflare Worker binary frame maximum: 12 KiB.
- Noise overhead per frame: one application marker byte plus a 16-byte ChaChaPoly tag.
- Maximum plaintext relay-fragment payload: 12 KiB minus 17 bytes.
- Logical application message maximum: 64 KiB.
- Reassembly is bounded to four incomplete messages, strict fragment counts/lengths, duplicate rejection and a 30-second assembly lifetime.

## Compression

Compression runs **before** encryption. It uses DEFLATE at the fast-compression level and is retained only when it saves at least 32 bytes. This is intended to reduce repetitive JSON/text/report payloads without spending time expanding payloads that are already compressed. JPEG, H.264, AAC/PCM-derived compressed formats, ZIP and other already-compressed data will generally not become materially smaller.

Encryption itself does not make the network payload smaller. Noise adds a small authentication tag per fragment; the framing layer minimizes additional overhead. Public-key operations happen during the handshake, not for every application fragment.

## What Cloudflare can see

The Worker can see source IP addresses, room ID, role, connection times, frame sizes, timing and total traffic volume. It can see its own small room lifecycle messages. It cannot read the encrypted application payloads or their contents. The Worker does not persist application frames.

## Trade-offs and limitations

- All app traffic, including files and media, traverses Cloudflare. This avoids ICE/STUN/TURN and direct-NAT traversal dependencies but consumes Worker bandwidth and is subject to Cloudflare usage limits/pricing.
- This is a relay architecture, not P2P. It may have higher latency than a successful direct local/Internet path.
- A lost WebSocket currently requires a new session and fresh owner approval. Automatic reconnect/resume is not claimed.
- The room ID is only a rendezvous identifier; Noise identity binding, SAS comparison and owner approval remain mandatory.
- The app must remain active with its visible session notification. Camera, microphone and screen capture still require local owner action and Android consent.
- Torann/Noise implementation has not been independently audited. Physical two-device and hostile-network tests are still outstanding.
