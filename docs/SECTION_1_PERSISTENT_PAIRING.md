# Section 1 revision — persistent single-target pairing

## Agreed product scope

RKRemote will manage exactly one target phone from one controller phone. The controller is the primary management surface. After an explicit one-time pairing performed by the device owner, ordinary reconnection should require no manual token copying or repeated approval.

The intended behavior is:

- Pair one controller identity with one target identity.
- Persist the pairing across app restarts and device reboots, subject to Android lifecycle and permission rules.
- Keep the target address discoverable or refreshable on the trusted local network; do not assume a DHCP address is permanent.
- Establish a fresh, short-lived session credential for each authenticated connection. The credential is negotiated automatically from the persistent paired identity; it is not a static token copied between phones.
- Reconnect automatically after temporary Wi-Fi loss, IP changes, or transient server errors.
- Let the controller display connection state and manage ordinary connection settings.
- Do not require the target owner to repeat pairing for every connection.

## Security and Android boundaries

- The target must retain a visible, owner-approved agent service and Android's normal foreground-service notification.
- The target owner must retain a local emergency stop and the ability to revoke the paired controller. These controls cannot be hidden, disabled, or made impossible to use.
- Android runtime permissions, MediaProjection consent, Notification Access, and Accessibility enablement remain governed by Android and the device owner. Pairing does not grant or silently renew those permissions.
- Android may stop background work or require the app to be reopened after force-stop, permission revocation, or OEM power management. Persistent pairing does not guarantee uninterrupted execution.
- Only one target is in scope for this revision; multi-target management is not required.

## Required implementation sequence

The existing executable LAN protocol is raw HTTP with a bearer token and no TLS. It cannot safely carry a persistent pairing secret or automatically negotiated session tokens: credentials and commands would be observable and replayable by an attacker on an untrusted network.

Therefore implementation must proceed in this order:

1. Integrate an authenticated encrypted transport for the LAN path (TLS with certificate/public-key pinning, or an equivalently reviewed authenticated channel).
2. Implement a first-time pairing handshake that explicitly binds the controller's public identity to the target.
3. Persist private identity material using Android Keystore-backed encryption; never store pairing secrets in source control or plaintext preferences.
4. Add a challenge-response handshake that derives a fresh, short-lived per-connection credential and rejects replayed challenges.
5. Persist the single target's identity and network-discovery hints on the controller; automatically reconnect with bounded backoff.
6. Add tests for pairing, restart persistence, token freshness, replay rejection, revoked identities, and network changes.
7. Validate on two physical Android devices before describing the feature as production-ready.

## Current implementation status (important)

This document records the agreed design, not a claim that it is already implemented.

The current app still uses a manually generated temporary bearer token for the LAN agent. The pairing-code UI is a local prototype and is not connected to the LAN authentication flow. The existing reconnect monitor still requires the target IP and token to be entered on the controller. The AES-GCM utility, TLS WebSocket transport primitive, and capability gate are not yet integrated into the executable LAN path.

The current target-side stop action and Android permission controls remain in place. This revision does not remove or bypass them.
