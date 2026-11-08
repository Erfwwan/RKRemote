# Remote control implementation plan

This document is a design contract, not a claim that remote control is already implemented.

## Components

- Android agent: runs on the device being administered; owns permissions and all user-consent flows.
- Android controller: displays paired devices and sends narrowly scoped commands.
- Shared protocol: versioned JSON messages with request IDs, session IDs, timestamps, and capability names.
- Optional relay: forwards encrypted WebSocket traffic when peers cannot reach each other directly. It must not be able to decrypt application payloads.

## Pairing sequence

1. The agent owner opens RKRemote and taps **Start pairing**.
2. The agent displays a one-time pairing code and expiry countdown.
3. The controller enters or scans the code.
4. Both endpoints authenticate the pairing exchange and derive a fresh session key.
5. The agent owner approves the controller identity and requested capabilities.
6. The agent displays the active controller and a persistent **Stop session** action.
7. Revocation deletes the paired identity and invalidates active sessions.

## Message envelope

Every message must include:

- `protocolVersion`: integer
- `messageId`: random unique identifier
- `sessionId`: authenticated session identifier
- `sentAt`: Unix timestamp in milliseconds
- `sequence`: strictly increasing per direction
- `type`: allow-listed message type
- `payload`: capability-specific data

Messages must be authenticated and encrypted in transit. Receivers must enforce size limits, timeouts, replay protection, and strict schema validation.

## Capability milestones

1. Pairing, device identity, secure transport, session revocation.
2. Notification forwarding, only after a separate opt-in on the agent.
3. User-selected folder listing and file transfer through Storage Access Framework URIs.
4. Screen viewing through MediaProjection, started by the device owner for each capture session.
5. Touch interaction only through a separately disclosed, manually enabled accessibility capability; no lock-screen, permission-dialog, or secure-window bypass.
6. Camera and microphone streaming only while the owner starts a visible foreground session.
7. Two-way push-to-talk with explicit microphone activation and a visible transmitting state.
8. Diagnostics, reconnect policy, battery impact tests, and compatibility testing.

## Current implementation status

The repository currently contains the local setup milestone only. Pairing, network transport, remote commands, screen streaming, camera streaming, and push-to-talk are not yet implemented. The build workflow added with this document verifies compilation; it does not imply runtime feature completion.
