# RKRemote Cloudflare signaling Worker

This Worker is a rendezvous/signaling relay only. It does **not** relay screen, camera, microphone, files, commands, SDP, or ICE as plaintext. Android peers exchange Noise-encrypted signaling frames through a Durable Object room; media/control data travels directly over the WebRTC DTLS DataChannel when NAT permits.

## Requirements

- A Cloudflare account with Workers and Durable Objects enabled.
- Node.js/npm and Wrangler.
- Android clients configured with the deployed Worker WebSocket URL.

## Deploy

From this directory:

```sh
npm install --global wrangler
wrangler login
wrangler deploy
```

The `wrangler.toml` file declares the `SignalRoom` SQLite-backed Durable Object migration. Deploy the Worker before connecting either Android device. Wrangler prints the deployed `workers.dev` URL; use its WebSocket form in RKRemote:

```text
wss://<your-worker-host>/signal/<64-character-lowercase-hex-room-id>?role=target
wss://<your-worker-host>/signal/<same-room-id>?role=controller
```

The target must connect first. The controller then joins the same room. Generate a fresh room ID in the app for each pairing attempt.

## Room behavior and limits

- Exactly one target and one controller socket may occupy a room.
- Only binary frames from 1 byte through 12 KiB are accepted from clients. Text application frames are rejected.
- The Worker forwards binary frames opaquely and does not parse, log, store, or interpret their bytes.
- A room expires after 10 minutes without signaling traffic. The inactivity alarm is refreshed on a new peer join and each valid binary frame; this is not a ten-minute maximum session lifetime.
- A disconnected peer is notified; a new connection must rejoin using the same room ID while the room remains alive.
- The room ID is a rendezvous identifier, **not an authentication secret**. Peer identity, Noise handshake authentication, SAS comparison, and target-owner approval remain mandatory on Android.

## TURN

Cloudflare signaling is not a TURN relay. For restrictive carrier NATs or networks that block direct peer connectivity, provision a separate TURN service. Prefer short-lived, time-limited TURN credentials from your provider. Enter the TURN URL(s), username, and credential in the app on both peers. Do not commit TURN credentials to this repository or put permanent TURN secrets in a public Worker variable.

## Privacy and security notes

Cloudflare can observe connection IP addresses, room identifiers, connection times, and traffic volume. It forwards encrypted Noise frames and cannot decrypt them. The Worker does not make the Android session persistent: Android must keep RKRemote open for the current secure Internet session in this version. A target owner must still approve the secure pairing and each requested capability locally.

This Worker has not undergone an independent security audit. Test deployment, two-device connectivity, room expiration, reconnects, and TURN behavior before relying on it.
