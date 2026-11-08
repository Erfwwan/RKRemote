# RKRemote custom network profiles

The Android app includes an **Open Network** page for user-managed connection profiles.

## Profile format (schema version 1)

```json
{
  "schemaVersion": 1,
  "name": "My Network",
  "signalingUrl": "wss://signal.example.com/ws",
  "iceUrls": [
    "stun:stun.example.com:3478",
    "turns:relay.example.com:5349"
  ]
}
```

- `signalingUrl` must use WSS. The endpoint must implement the RKRemote signaling protocol; a generic WebSocket endpoint is not sufficient.
- `iceUrls` accepts up to eight `stun:`, `stuns:`, `turn:`, or `turns:` entries.
- A profile is configuration, not a server implementation. Operators must deploy compatible signaling and (when needed) STUN/TURN infrastructure.
- TURN usernames and credentials are intentionally excluded from import/export files. Enter them locally in Secure Internet settings after loading a profile.
- The app limits the profile file to 32 KiB and stores up to 20 profiles locally.
- Imported addresses are validated before they are saved. Profiles do not grant capabilities or bypass peer verification, owner approval, Android permissions, or the secure handshake.

## In-app guide

Open the navigation drawer → **شبکه‌های سفارشی**. Create a profile, validate and save it, or import/export a JSON profile. Select **Apply**, then open **اتصال اینترنتی امن** and press **Load active custom network profile**. Generate/share the same room ID separately for each connection session.

Before using a profile from another person, verify the server operator and domain. A signaling operator can still observe connection metadata such as IP addresses, timing, and traffic volume.
