# Implementation status

This branch is a development foundation for the next major RKRemote iteration.

## Implemented in this branch

- Versioned JSON message envelope with an allow-list of message types and a 64 KiB limit.
- One-time, five-minute pairing challenge generator with single-attempt verification and PBKDF2-HMAC-SHA256 derivation.
- TLS-only WebSocket transport requiring a caller-supplied SHA-256 certificate pin.
- Central Android runtime-permission and notification-listener checks.
- Visible foreground-session notification with a local stop action.
- Pairing-code UI prototype that explicitly reports that no peer has connected.
- AES-256-GCM payload envelope with random nonces, authenticated associated data, strict size limits, and tamper detection (requires a separately authenticated session key).
- Session command authorization gate requiring an owner-approved active session, exact peer identity, per-capability grants, monotonic sequence numbers, and explicit revocation.
- SAF-based file access under the tree explicitly selected by the target owner. The controller can browse nested folders and list all provider-returned child documents, download target files, and upload into the currently browsed target folder. Both directions stream through 64 KiB buffers; the former 8 MiB transfer cap and 200-item listing cap are removed. Existing files are not overwritten. Controller downloads are saved automatically under `Download/RKRemote/Received/` on Android 10+, with an app-specific Downloads fallback on Android 8/9. No remote delete, rename, move, or copy operations are exposed.
- LAN agent/controller on TCP 18765 with temporary bearer token; health check and owner-selected folder metadata listing, plus normalized tap, swipe, and long-press gestures and focused editable-field text insertion when the target owner has manually enabled RKRemote Accessibility. Client handling is bounded (four active workers, sixteen queued clients, header limits and socket timeouts); controller response reads are capped at 16 MiB and IPv4 octets are validated.
- Owner-approved screen capture through Android MediaProjection consent, visible mediaProjection foreground service, in-memory JPEG frame (maximum dimension 1600 px), and authenticated `/screen.jpg` snapshot endpoint;
- Owner-started camera capture through Android CAMERA permission and a dedicated camera foreground service with a persistent stop notification; separate target-side buttons select the rear or front lens. Low-rate camera frames (up to 1280×720) are held in memory and read through authenticated `/camera.jpg`. Frames are re-encoded as JPEG at quality 82 for messaging-app-style lossy compression; camera dimensions remain capped at up to 1280×720, with original JPEG fallback if conversion fails. Remote requests cannot start or switch the camera.
- Owner-started microphone capture through RECORD_AUDIO permission and a dedicated microphone foreground service with a persistent stop notification; mono 16 kHz / 16-bit PCM chunks are held briefly in memory and polled through authenticated `/audio/chunk`. Remote requests cannot start the microphone. The controller plays received chunks using AudioTrack.\n- Two-way audio prototype: target owner locally enables PttPlaybackService (mediaPlayback foreground service with persistent stop notification); authenticated POST /audio/ptt accepts only 1–4096-byte PCM chunks while that local service is enabled. The controller captures 16 kHz mono 16-bit PCM only while the PTT surface is held, sends approximately 100 ms chunks, and stops capture on release. Queue is capped at 8 chunks (about 0.8 seconds at 100 ms per chunk) and drops the oldest when full to bound latency. Odd-sized PCM chunks are rejected to preserve 16-bit sample alignment. The existing target microphone polling path provides audio in the reverse direction. controller can request one snapshot or poll JPEG snapshots at roughly one per second for a low-rate preview (not continuous video). A separate AccessibilityService requires manual enablement in Android settings and accepts authenticated normalized gestures (tap, swipe, long press) and text insertion into the currently focused editable field. The controller screen image supports direct tap and long press; swipe is currently entered with normalized start/end coordinates. Text insertion uses ACTION_SET_TEXT rather than simulated key events.
- JVM tests for the encryption envelope and command authorization gate.
- Notification capture stores posted and removed events in an Android Keystore AES-256-GCM encrypted store bounded to the latest 500 events. Each newly stored event receives a monotonically increasing eventId. The separate notification-sharing switch has been removed. The authenticated LAN /notifications endpoint returns up to the latest 500 events only while Android Notification Access remains enabled; the controller can still load this snapshot manually. Automatic five-event batch delivery, acknowledgement/retry, and durable paired-device discovery are not yet integrated.
- Existing local setup and encrypted notification store remain in place.
- Section 8 owner-approved LAN service recovery: the connected-device foreground service uses START_STICKY and reloads its session token from Android Keystore-encrypted storage after system process reclaim. An opt-in BOOT_COMPLETED receiver can restore only the LAN agent after reboot; it does not restart camera, microphone, screen capture, or PTT services. The boot receiver does not put the decrypted token in an Intent. The foreground notification retains a local Stop action.

## Not implemented / not production-ready

- Internet relay, cross-network connection, TLS on the LAN socket, or end-to-end authenticated key exchange. The LAN token is a temporary bearer secret sent over the local network; use only on a trusted private Wi-Fi network. The AES-GCM envelope is not yet connected to the LAN transport.
- Durable paired-device identity and key rotation.
- Continuous screen video (the current preview is periodic JPEG polling), keyboard-event emulation and full remote input (gesture and focused-field text insertion are now implemented, but not yet device-validated), continuous camera video, production-grade/low-latency/jitter-buffered voice, synchronized full-duplex VoIP, resumable file transfers, contacts/call-log transfer. Notification forwarding currently exposes a latest-500 snapshot after Android Notification Access is enabled; event IDs are now available, but automatic five-event batch delivery, incremental sync, remote acknowledgement/retry, and per-app filters are not implemented. Screen capture is currently a single JPEG snapshot per controller request; it is not a live video stream. Section 3 now supports nested SAF folder browsing, streaming uploads/downloads without an app-imposed file-size cap, and automatic controller-side saving under `Download/RKRemote/Received/` (Android 10+). Transfers are not resumable and do not yet show percentage progress. SAF access remains limited to the owner-selected tree and Android/provider restrictions. Physical-device validation remains outstanding.
- Section 7 connection recovery prototype: controller can monitor `/health` and retry idempotent health probes with bounded 1s/2s/4s/8s/15s backoff (5s interval after recovery); the screen-preview loop retries after transient failures instead of stopping immediately. The server now enables address reuse, TCP_NODELAY and keepalive on accepted sockets, reports uptime/active/queued clients in `/health`, surfaces fatal listener errors in the persistent target notification, and closes active sockets/workers on stop. The controller uses TCP_NODELAY/keepalive and endpoint-specific read timeouts. This does not silently restart the target agent after Android kills the process; the owner must start a new visible session. Battery optimization exemptions are not requested.
- The LAN bearer token and snapshots still travel without TLS; worker bounds reduce local resource-exhaustion risk but do not make the protocol safe on untrusted networks.
- Device-farm tests, physical-device and long-duration battery/network tests, release signing, and security review.

The WSS transport, encryption utility, and command gate remain separate foundations. The executable LAN path is a deliberately small TCP protocol with a temporary token; it is not suitable for untrusted networks or internet exposure. Screen snapshots are also sent without TLS and may contain sensitive information visible on the target display. No privileged Android operation is executed from inbound network requests. No privileged operation should be wired to inbound messages until peer authentication, authorization, replay protection, capability consent, and session revocation are integrated and tested.


## Camera and microphone prototype boundary

Implementation details and the two-device acceptance checklist are maintained in [docs/SECTION_4_CAMERA_MICROPHONE.md](SECTION_4_CAMERA_MICROPHONE.md). Camera and microphone run as separate owner-started foreground services; the controller can only read published media. The screenshot standby service uses a distinct notification ID so its persistent notification cannot overwrite the camera notification.

Camera and microphone capture must be started by the owner from the target phone while the app is visible and after the corresponding Android runtime permission is granted. Separate local buttons select the rear and front camera; Android's granted CAMERA permission is reused until revoked. Each sensor runs in its own foreground service with a persistent notification and a stop action. The LAN endpoints only expose frames or queued audio chunks from a service that is already running; they cannot switch either sensor on. Camera frames (JPEG re-encoded at quality 82 for transfer) and PCM audio chunks are memory-only and are discarded when capture stops. The LAN transport is still plaintext bearer-token HTTP on trusted Wi-Fi only; do not use these media endpoints on public networks or expose port 18765 to the internet. Audio currently uses short polling chunks and is not a jitter-buffered, lossless, low-latency voice transport. Physical-device validation is still required.


## Live walkie-talkie push-to-talk prototype boundary

The implementation notes and two-device acceptance checklist are in [docs/SECTION_5_TWO_WAY_AUDIO.md](SECTION_5_TWO_WAY_AUDIO.md).

The target owner must enable the PTT speaker locally from the app. This starts a mediaPlayback foreground service with a persistent notification and a local stop action. The controller requires Android RECORD_AUDIO permission and presses/holds the PTT control; it streams short 16 kHz mono 16-bit PCM chunks during the press, and the target plays chunks as they arrive rather than waiting for a complete recording. Releasing stops transmission. This is half-duplex walkie-talkie behavior, not a call and not simultaneous two-way audio. Playback uses the normal media route/main loudspeaker; the app does not switch Android into communication mode. Target and controller microphone capture use the normal MIC source and enable Acoustic Echo Canceler / Noise Suppressor when the platform exposes them; effectiveness varies by device and cannot guarantee zero acoustic feedback. Chunks are bounded to 4096 bytes and queued in memory (maximum 8 chunks; oldest chunks are dropped under pressure). Audio remains transient and LAN bearer-token transport is plaintext; trusted private Wi-Fi only. Physical-device and acoustic-feedback tests remain outstanding.

## Notification history forwarding boundary

The notification listener captures posted and removed events only after the device owner manually enables RKRemote in Android Notification Access settings. Events are stored in the existing bounded (500-event) Android Keystore AES-GCM encrypted local store, and each event is assigned a monotonically increasing eventId. The separate notification-sharing switch has been removed. The authenticated LAN /notifications endpoint re-checks that Notification Access remains enabled and exports at most the latest 500 events, with each text field capped at 2,000 characters. The controller still fetches this snapshot manually. Automatic transmission after every five new events, durable acknowledgements/retries, and automatic device discovery are not implemented yet. The LAN bearer-token transport remains plaintext; do not automatically transmit private notification content over it. Secure paired TLS transport must be integrated first. Physical-device validation remains outstanding.


## Section 7 · Connection recovery and diagnostics

The controller's **Auto reconnect** control performs idempotent `/health` probes. After a failed probe it retries with bounded backoff of 1, 2, 4, 8, then 15 seconds; after a successful probe it checks every 5 seconds. The live screen stream retries after transient network errors. **Check now** performs a single manual health probe. Both automatic and manual diagnostics display agent uptime, active client count, queued client count, and any reported listener error. The target foreground notification is updated if the LAN listener fails to bind or terminates unexpectedly. Accepted sockets use TCP_NODELAY/keepalive and the client uses separate timeouts for PTT, media, file and normal requests. Retry logic is limited to read-only health checks and screen snapshots; non-idempotent commands and file transfers are never automatically replayed.

The LAN foreground service now uses START_STICKY. Android does not retain or redeliver the last start Intent; after system process reclaim it recreates the service with a null Intent, and the service reloads the active token from the Android-Keystore-encrypted store. This avoids placing the bearer token in an Intent retained by the system. Reboot restoration is separately opt-in. The BOOT_COMPLETED receiver sends only a restore action and never includes the decrypted token. It restores only the LAN agent; sensor and media services are not started. Force-stop, permission revocation, OEM task killers and device shutdown remain outside the restart guarantee. No battery-optimization exemption is requested automatically. The retry schedule is covered by JVM unit tests. Physical-device and long-duration tests remain outstanding.


## Section 2 · agreed continuous video specification (not yet implemented)

The user has approved replacing screen JPEG snapshots/polling with a continuous H.264 video stream, encoded/decoded with Android MediaCodec, with the longest image dimension capped at 480 px, a target of at least 5 fps where supported, bounded latest-frame delivery that drops stale frames, and direct touch interaction on the rendered stream. Remote rotation control is not required. The detailed requirements and acceptance criteria are in [docs/SECTION_2_LIVE_VIDEO.md](SECTION_2_LIVE_VIDEO.md). This is currently a specification-only commit: executable code still uses JPEG snapshots up to 1600 px and approximately one-second polling. The current plaintext HTTP LAN transport must be replaced by the authenticated encrypted transport before live screen data is sent over it. Do not claim continuous video is implemented or tested yet.


## Section 2 · implementation pass committed (2026-11-08)

The first executable implementation is now present: MediaProjection -> AVC MediaCodec -> latest-only frame store -> persistent /screen/video RKV1 stream -> controller MediaCodec decoder -> SurfaceView. The stream is capped at 480 px on its longest side. The old screen JPEG endpoint and periodic controller polling UI have been removed. The live surface supports tap, long-press, and drag gesture forwarding. Automatic reconnect is in the video player. CI and real two-device validation are pending; do not call this production-verified. The existing bearer-token HTTP/TCP transport is still plaintext, so this video feature must remain on a trusted private LAN until the Section 1 encrypted transport work is completed.


## Section 8 · owner-approved service recovery

The LAN session foreground service now returns START_STICKY after a valid owner-started session. If Android reclaims the app process under memory pressure, the system can recreate the service with a null start Intent. The service reloads the active token from the AES-GCM encrypted session store using an Android Keystore key. The bearer token is not written to preferences as plaintext and is not included in an Intent retained or redelivered by Android.

The target UI includes an explicit **Start agent after phone reboot** option, disabled by default. When enabled and an active owner-started session still exists, BOOT_COMPLETED asks the system to restore only the LAN agent. The receiver sends no token in its service Intent; the service decrypts the saved token from Android Keystore storage. The camera, microphone, MediaProjection/screen capture and PTT playback services are never auto-started. The persistent foreground notification and local Stop action remain visible. Stop clears the active encrypted token so the session cannot be restored after a later reboot; the reboot preference itself remains selected until the owner turns it off. Android 15 restricts several other foreground-service types from BOOT_COMPLETED, but connectedDevice is not among the prohibited types; this service declares its matching permission and CHANGE_NETWORK_STATE prerequisite.

This does not bypass Android's force-stop state. A user force-stop, app-data clear, uninstall, revoked permissions, an invalidated Keystore key, OEM power-management termination, or system policy can prevent restoration. Android may delay a sticky service restart until resources are available. Reboot restoration is also subject to the device's foreground-service and boot policies; failures must be diagnosed on the actual device. No permanent always-on guarantee is made. Battery-optimization settings remain an explicit owner choice.

Validation status: source changes committed. No Gradle build, CI workflow result, reboot test, process-kill test, or physical Samsung-device test has yet verified this implementation. A rejected boot-time start is logged and leaves the owner preference available for a manual retry.


## Internet P2P migration · Stage 1 committed

The first P2P foundation is present:
- Android Keystore-backed P-256 peer identity (P2pIdentityStore); private key is non-exportable.
- Stable peer ID derived from SHA-256 of the encoded public key.
- Signed, bounded, short-lived peer advertisement format (P2pPeerRecord) with sequence, issue/expiry timestamps, peer ID/public-key binding and transport-address validation.
- JVM tests for record round-trip, tampering, expiry, TTL bounds and address syntax.

This is **not yet internet connectivity**. DHT publication/lookup, NAT traversal, direct peer transport, relay fallback, and migration of existing feature traffic are not implemented. The old plaintext TCP bearer-token path remains LAN-only; do not expose port 18765 to the internet. See [INTERNET_P2P_MIGRATION.md](INTERNET_P2P_MIGRATION.md) for the staged architecture and acceptance criteria.

The current jvm-libp2p candidate is not yet suitable to claim production DHT/relay support: its component matrix does not list Kademlia DHT as implemented and marks Circuit Relay v2 as prototype/beta. No dependency was added pending a validated Android-compatible implementation.

Validation for this pass: source and JVM tests were committed, but no Gradle build, CI result, DHT integration test, NAT traversal test, or two-device internet test has yet been verified.


## Cloudflare signaling scaffold · source committed

- Added a Workers + Durable Objects WebSocket rendezvous under `cloudflare/worker`.
- Added an Android `CloudflareSignalingClient` with random 256-bit room IDs and a strict signaling-message allow-list.
- The service is intended only to exchange WebRTC offer/answer/ICE signaling messages. It does not relay commands, files, notifications, screen, camera, or audio.
- The Android client is not yet connected to the Compose pairing UI; no WebRTC PeerConnection is implemented.
- The Worker has not been deployed to a Cloudflare account. No Android build, Worker deployment, WebSocket integration test, or physical two-device internet test is verified.
- Existing LAN TCP 18765 remains plaintext and LAN-only. Do not expose it publicly.


## RK-SecureLink v1 · encrypted signaling design and binary worker scaffold

- Added `docs/RK_SECURELINK_V1.md` specifying Noise XX / X25519 / ChaCha20-Poly1305 / SHA-256, P-256 identity binding, explicit first-pair approval, session freshness, replay/idempotency framing, WebRTC DTLS/SRTP separation, and direct-path-first latency policy.
- Changed the Cloudflare Durable Object to accept client binary frames only, enforce a 12 KiB bound, and forward opaque bytes unchanged. Client text/JSON messages are rejected; only server-generated room lifecycle events remain textual.
- Changed the Android WSS client to send/receive bounded binary frames without parsing payloads and to accept only known room lifecycle events as text.
- **Not implemented:** a Noise cryptographic engine, persistent X25519 static-key store and P-256 binding verifier, authenticated WebRTC fingerprint exchange, WebRTC PeerConnection, or encrypted migration of existing app capabilities.
- This is not yet end-to-end encrypted remote access. WSS protects only each hop; do not deploy as a working secure session or expose the plaintext LAN TCP server. No Android build, Worker deployment, Noise vector, integration, or physical-device test has been verified.


## RK-SecureLink · Noise XX engine source implementation

- Added pinned dependency `com.stonygrey:torann:0.3.1`, using `Noise_XX_25519_ChaChaPoly_SHA256`. The library documents Android API 33+ support, shared Noise test vectors and cross-implementation tests; it explicitly says it has not been independently audited and is not production-ready. SecureLink key storage fails closed below API 33; the overall app minSdk remains 26.
- Added `SecureLinkStaticKeyStore`: creates X25519 static keys, wraps private bytes with AES-256-GCM using a non-exportable Android Keystore key, stores only IV/ciphertext/generation in app-private preferences, and supports rotation/clear. X25519 private bytes are not hardware-backed.
- Added `SecureLinkIdentityBinding`: signs the peer ID, P-256 public key, X25519 public key, version and generation with the existing P-256 Android Keystore identity. The binding is carried inside encrypted Noise handshake payloads.
- Added `SecureLinkHandshake`: initiator/responder Noise XX three-message state machine; validates signed peer bindings and optional pinned Peer ID; derives a 6-digit SAS from the final handshake hash; blocks transport read/write until local SAS confirmation; closes the session on handshake/decryption failure.
- Added a factory joining the existing P-256 identity store and encrypted X25519 key store. Cloudflare signaling remains a separate opaque-binary transport and is not yet wired to this engine.
- Added JVM tests for two-party handshake/transport, matching SAS, wrong pinned peer rejection, and forged binding rejection.
- **Still not complete:** app-level trust acknowledgement from both peers, persistent paired-peer pin/generation/revocation database, Cloudflare client integration, WebRTC PeerConnection and DTLS fingerprint binding, migration of existing feature traffic, upstream Noise vector run on Android, independent security review, Android build/CI and physical two-device tests.
- Do not expose the legacy plaintext LAN TCP port 18765. This source implementation is not a production-verified secure Internet connection.


## RK-SecureLink · Cloudflare Noise bridge source implementation

- Added `SecureLinkCloudflareSession`, which connects the existing Noise XX handshake state machine to the Cloudflare WSS client's opaque binary-frame interface.
- Controller initiates the three-message Noise XX exchange; target responds. Noise handshake frames are forwarded as binary WebSocket messages; the Worker does not parse their contents.
- After handshake completion, the session surfaces the authenticated peer ID and six-digit SAS to the app callback. The caller must present these to the owner and call `approvePeer(code, expectedPeerId)` only after manual comparison.
- Added a mutual-approval barrier: each locally approved peer sends an encrypted READY frame; application payloads are accepted/sent only after the remote READY arrives too. Encrypted inbound frames received before local approval are buffered up to eight frames; overflow or malformed frames closes the session.
- Room lifecycle loss closes the Noise session and clears pending ciphertext. The session callback API is currently a source-level integration point; no Compose pairing UI calls it yet.
- Added a JVM bridge test with an in-memory paired channel: verifies both peers receive matching SAS, data is blocked until both owners approve, and application bytes are delivered only after mutual READY. This test is committed but has not yet been confirmed passing in CI.
- **Not yet implemented:** persistent peer pin/revocation UI and database, WebRTC PeerConnection, authenticated DTLS fingerprint exchange, ICE/STUN/direct path, relay fallback, migration of existing remote-control capabilities, and automatic reconnect/rekey.
- **Validation:** source committed only. No successful Gradle build, CI result for the current head, deployed Worker, live WSS integration test, Android API 33+ run, or physical two-device test has been verified. Do not expose TCP 18765 or treat this branch as production-secure.


## Secure Internet P2P · WebRTC direct data-channel implementation

- Added `io.github.webrtc-sdk:android:150.7871.04` from Maven Central.
- Added `SecureWebRtcPeer`: controller-created ordered binary DataChannel; target accepts it; SDP and trickled ICE are only sent through the Noise-encrypted SecureLink application channel.
- SHA-256 DTLS fingerprint is extracted from local SDP and included in the authenticated Noise message. The receiver checks the envelope against the SDP fingerprint; libwebrtc validates the remote DTLS certificate during the DTLS handshake.
- ICE uses STUN by default and accepts optional user-supplied TURN server configuration. No TURN provider, credentials, or Cloudflare media relay are included.
- Added `SecureInternetPeer` coordinator: Cloudflare rendezvous -> Noise XX mutual approval -> WebRTC PeerConnection/DataChannel; application payloads use DTLS-protected DataChannel after connection.
- Added `SecureInternetPanel` to the main screen for WSS URL, room ID, role selection, target-first connection, SAS comparison/approval, and session stop.
- Added `docs/WEBRTC_DIRECT_TRANSPORT.md` with deployment steps, relay requirements and two-device test checklist.
- **Not yet migrated:** existing remote input, screen video, files, notifications, camera, microphone and PTT feature traffic. This milestone provides an authenticated data-channel transport and manual connection panel, not complete remote-control functionality.
- **Not deployed or verified:** Worker deployment, TURN service, Android API 33+ runtime, real two-device internet connection, NAT traversal matrix, DataChannel payload test, independent security review, and current-head CI/build. The WebRTC artifact is documented at Maven Central; see [the SDK project](https://github.com/webrtc-sdk/android).
- The legacy LAN port 18765 remains plaintext and must stay on trusted private LAN only.
