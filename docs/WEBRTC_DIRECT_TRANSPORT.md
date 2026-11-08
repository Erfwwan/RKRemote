# Secure WebRTC direct transport

## What is implemented

- The target joins the Cloudflare Durable Object room first; the controller joins second.
- Cloudflare accepts bounded binary WebSocket frames only. It cannot parse SDP or ICE and forwards the Noise frames unchanged.
- Noise XX authenticates the long-lived P-256 peer identities through the signed X25519 static-key binding. Both owners must compare the six-digit SAS and approve the other peer ID.
- Application signaling is blocked until both sides have approved. WebRTC offer, answer, and trickled ICE candidates are carried as Noise-encrypted application messages.
- The SDP's SHA-256 DTLS fingerprint is checked against the fingerprint field inside the authenticated Noise message. libwebrtc then validates the remote DTLS certificate against the SDP fingerprint during the DTLS handshake.
- A WebRTC ordered binary DataChannel is created by the controller and accepted by the target. DataChannel payloads are protected by DTLS. The code does not double-encrypt every DataChannel packet with Noise.
- DataChannel application messages use a bounded RKD1 fragment format: up to 4 MiB per logical message, 12 KiB transport frames, 4 incomplete messages, 30-second assembly lifetime, and strict duplicate/length/index checks. This allows large file/video protocol frames to be carried later without exceeding the per-frame cap; it does not itself migrate those features.
- ICE uses a public STUN server by default. Optional TURN configuration is accepted by the transport class; TURN credentials are not hard-coded or sent to Cloudflare.

## App setup

The main screen contains **Secure Internet P2P**.

1. From the repository root, run `cd cloudflare/worker`, then `npx wrangler login` and `npx wrangler deploy`. Use a Cloudflare account you control and approve the Durable Objects migration when prompted. The app does not deploy the Worker automatically; the assigned `workers.dev` hostname is the WSS base URL.
2. Enter the deployed Worker base URL in the WSS field (for example, the actual `workers.dev` hostname assigned by Cloudflare).
3. On the controller, generate a 64-character room ID and share it privately with the target.
4. Select **Target** on the destination phone and **Controller** on the controlling phone.
5. Connect the target first, then the controller.
6. Compare the six-digit code shown on both phones and check the peer identity. Each owner must approve locally.
7. When the DataChannel opens, the UI reports the WebRTC connection. Stop on either device to close its local session.

The room ID is a bearer rendezvous secret. Anyone who obtains it may attempt to join before the intended peer, so share it only through a trusted channel and use a fresh room ID for each session.

## NAT and relay behavior

STUN attempts a direct ICE path. The configured public STUN provider can observe the device's public IP and request metadata. Cloudflare can observe the rendezvous room ID, source IPs, connection timing and encrypted traffic volume, but not the Noise-protected SDP/ICE contents. Direct connectivity is not guaranteed: symmetric NAT, carrier-grade NAT, enterprise firewalls, or UDP restrictions may prevent it. If a TURN server is supplied in `SecureWebRtcPeer.TurnServer`, ICE can select a relay candidate when direct candidates fail. TURN relays encrypted DTLS packets; the relay operator can observe participating IP addresses, timing and traffic volume but does not decrypt the DataChannel. No TURN service or credentials are bundled with this repository. Cloudflare Workers/Durable Objects are signaling only and are not a media relay.

For reliable use across restrictive networks, provision a separately operated TURN service (for example, coturn on infrastructure you control), protect its credentials, and provide short-lived credentials to the app. Do not put permanent TURN secrets in source control or the Worker.

## Current limits

- The current DataChannel is a transport foundation and test panel. Keep RKRemote open while testing: the Internet session is not yet owned by a persistent foreground service and will not survive app-process termination. Existing screen, remote input, file, notification, camera, microphone and PTT code paths have **not** been migrated to it.
- No media tracks are added yet; live screen/camera video and audio need separate explicit owner-started capture and WebRTC track integration.
- There is no persistent trusted-peer registry, revocation UI, or key-rotation workflow beyond the existing local key stores.
- No automatic TURN provisioning, DHT peer discovery, multi-path racing, relay fallback service, or session rekey/reconnect exists.
- Worker deployment, real internet connection between two devices, NAT matrix, Android API 33+ runtime, and physical-device performance have not been verified.
- Torann is a new, unaudited Noise implementation. Do not treat this source-only build as a production security certification.
- Legacy LAN TCP port 18765 remains plaintext and LAN-only. Never expose or port-forward it.

## Verification checklist

- [ ] Deploy Worker and confirm target-first/controller-second room join.
- [ ] Verify both phones display the same SAS and different peer IDs.
- [ ] Reject a mismatched SAS on either phone and confirm no DataChannel opens.
- [ ] Approve both phones and confirm DataChannel connects on the same Wi-Fi.
- [ ] Repeat on separate networks with direct UDP available.
- [ ] Repeat with TURN configured and direct UDP blocked; confirm relay candidate selected.
- [ ] Send small and multi-megabyte test payloads in both directions and verify fragment bounds/reassembly.
- [ ] Verify peer-left, socket failure, and local Stop close WebRTC and Noise state.
- [ ] Test Android 13+ and supported Android device architectures.
- [ ] Complete independent security review before sending private device data.
