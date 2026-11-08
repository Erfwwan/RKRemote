# Current implementation status

**Current transport architecture:** the active branch uses a single Cloudflare encrypted relay. Both phones connect to a Durable Object room; Noise XX authenticates the paired devices and encrypts every application frame after mutual SAS approval. Compressible messages are optionally DEFLATE-compressed before encryption, fragmented below the Worker's 12 KiB limit, and forwarded as opaque ciphertext. STUN/TURN/WebRTC setup and custom network profiles have been removed from the connection UI.

**Pairing UX:** the target creates a fresh room automatically and shares an RKRemote invitation URI once its Worker WebSocket is open. The controller opens the URI; the Worker URL, room ID and role are populated automatically. The owner still checks and approves the six-digit SAS on both phones.

**Cloudflare manual deployment and optional API Token:** users deploy their own `rkremote-signaling` Worker with the Cloudflare Deploy button. The Worker URL is entered in the app. An optional user-created API Token can update the already-existing Worker; it is encrypted with AES-GCM using Android Keystore and is never sent to the paired device. Token entry is not required for normal relay connections. The app no longer uses OAuth Client registration, Client IDs, refresh tokens, account enumeration, or account-level subdomain creation. For least privilege, scope a Workers Editor token to the existing `rkremote-signaling` Worker only. See docs/CLOUDFLARE_SETUP.md for the updated manual instructions.

**Cloudflare impact:** the manual deployment creates the Worker and its SignalRoom Durable Object in the user's own account. Optional in-app update requests modify only that existing Worker script. All app traffic traverses Cloudflare and consumes Worker bandwidth subject to Cloudflare limits/pricing. Cloudflare can observe IPs, room IDs, timing and traffic volume, but not Noise-encrypted payloads.

**Android safety boundaries:** peer verification, SAS approval, per-feature grants and visible foreground controls remain mandatory. Camera, microphone and screen capture require local owner action and Android permission flows. Android restrictions on SMS, call logs and background location still apply.

**Validation:** The manual Worker deployment and optional API-token update have not yet been run against a real Cloudflare account. This source milestone has not been Android-built or tested. Physical two-device tests remain deferred to the final stage. The Noise dependency is not independently audited. No production-readiness claim is made.

The detailed milestone notes below include historical snapshots from earlier commits. Where they conflict with this current summary, this summary describes the newer branch state.

---


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
- Owner-started **single-photo capture** through Android CAMERA permission and a dedicated camera foreground service with a persistent cancel notification; separate target-side buttons select the rear or front lens. Camera2 uses `TEMPLATE_STILL_CAPTURE` once (no repeating preview or live camera loop), then closes the camera automatically. One JPEG photo is held in memory for a single encrypted transfer and cleared after successful delivery. Remote requests cannot start the camera, switch lenses, or trigger another capture.
- Owner-started microphone capture through RECORD_AUDIO permission and a dedicated microphone foreground service with a persistent stop notification; mono 16 kHz / 16-bit PCM chunks are held briefly in memory and polled through authenticated `/audio/chunk`. Remote requests cannot start the microphone. The controller plays received chunks using AudioTrack.\n- Two-way audio prototype: target owner locally enables PttPlaybackService (mediaPlayback foreground service with persistent stop notification); authenticated POST /audio/ptt accepts only 1–4096-byte PCM chunks while that local service is enabled. The controller captures 16 kHz mono 16-bit PCM only while the PTT surface is held, sends approximately 100 ms chunks, and stops capture on release. Queue is capped at 8 chunks (about 0.8 seconds at 100 ms per chunk) and drops the oldest when full to bound latency. Odd-sized PCM chunks are rejected to preserve 16-bit sample alignment. The existing target microphone polling path provides audio in the reverse direction. controller can request a single still-photo transfer only; the app does not provide live camera preview or repeated frame polling. A separate AccessibilityService requires manual enablement in Android settings and accepts authenticated normalized gestures (tap, swipe, long press) and text insertion into the currently focused editable field. The controller screen image supports direct tap and long press; swipe is currently entered with normalized start/end coordinates. Text insertion uses ACTION_SET_TEXT rather than simulated key events.
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


### CI note for WebRTC transport milestone

- GitHub Actions completed both `:app:testDebugUnitTest` and `:app:assembleDebug` successfully for commit `16455720dc76dac9f3293adb2faed88114b9e119` (the first integrated coordinator + main-screen panel).
- The follow-up UI test-frame change at `8de82d63e974c33e23d9532e557afb64bc5fcb08` passed unit tests; its APK assembly was still running at the time this status was updated.
- Subsequent changes were an API-level annotation and documentation updates. The current branch head has a newer workflow run in progress, so do not treat the latest branch head as fully CI-verified until that run completes.


## Secure Internet P2P · DataChannel framing and TURN configuration

- Added bounded RKD1 fragmentation/reassembly for the ordered WebRTC DataChannel: logical payloads up to 4 MiB, frames up to 12 KiB, at most four incomplete inbound messages, 30-second assembly expiry, strict duplicate/index/count/length checks, and a 2 MiB outbound buffered-amount guard.
- Added JVM tests for out-of-order reassembly, duplicate fragment rejection, corrupted headers, and oversized payload rejection. Test execution for the current head is pending.
- Added optional TURN URL/username/credential fields to the secure connection panel. These values are passed only to libwebrtc and are not sent to the Cloudflare Worker. The user must supply credentials from a TURN service they control; none is hosted or provisioned by this repository.
- This remains a transport milestone. Existing remote-control routes and media pipelines are not yet migrated to the WebRTC DataChannel. Do not use the insecure LAN TCP server over the public internet.


## Secure P2P application-message protocol · first integration pass

- Added a bounded versioned application-message layer on top of the established DTLS DataChannel using the existing RemoteMessage envelope.
- The two peers derive the same per-room session identifier from the shared high-entropy room ID. Messages carry UUID message IDs, monotonic per-sender sequence numbers and send timestamps.
- Incoming messages are bounded to 64 KiB, checked against the current room session, rejected when timestamps differ by more than ten minutes, and rejected on duplicate IDs or non-increasing sequence numbers. A bounded 512-ID LRU prevents repeated message IDs within the connection.
- Added secure.ping / secure.pong message types and a UI test action that sends a ping and automatically returns a pong. This exercises bidirectional application framing after the Noise/SAS/DTLS connection.
- Extended the allow-list with explicit future request/result types for diagnostics, screen snapshots/input, and file transfer. Allow-list membership alone does not authorize an operation; feature dispatch is intentionally not connected yet.
- Added JVM tests for the new secure message type and oversized nested payload rejection.
- Still not migrated: actual screen input/video, SAF file operations, notification history, camera, microphone, and PTT. No privileged command is executed from this message layer. Each feature still requires explicit owner-granted capabilities, Android permission/state checks, a target-side dispatcher and per-operation validation before it can be safely exposed over the Internet.
- The room ID is used as a shared session namespace; use a fresh random 256-bit room ID for each session. Noise XX mutual approval and WebRTC DTLS remain the cryptographic/session transport layers. The legacy TCP 18765 path remains plaintext and LAN-only.


## Secure P2P command authorization and video framing · next integration pass

- Added `SecureRemoteCommandRouter`: only an authenticated target-side peer can dispatch a registered command after the target owner explicitly activates a capability set. Every command requires a bounded request ID; duplicate IDs are rejected; the existing `SessionCommandGate` enforces peer identity, active owner-approved session, capability grants and monotonic command sequence.
- Wired the router into `SecureInternetPeer` for inbound target-side command messages. The router is inert until the target app calls `activateOwnerApprovedCommands(...)`; connection establishment and SAS approval alone do not grant remote operations. `revokeOwnerApprovedCommands()` clears grants without requiring the encrypted connection to close.
- The router intentionally does not implement platform side effects. Registered feature handlers must still check current Android permission state, active owner-started foreground service, MediaProjection state, SAF URI grants, and per-command limits. No command handler for live screen, input, file transfer, notification sync, camera, microphone or PTT has been registered yet.
- Added `SecureVideoFrameCodec` for bounded H.264 access-unit fragmentation/reassembly inside versioned secure messages: 32 KiB chunks, max 2 MiB per frame, max 64 chunks, at most two incomplete frames and 5-second assembly expiry. It validates stream IDs, dimensions, metadata consistency, chunk lengths, duplicates and stale frame sequences.
- Added JVM tests for command authorization/revocation/replay and H.264 frame reassembly/invalid chunks.
- **Not yet connected to the live screen encoder or a video decoder UI.** This commit supplies a bounded video frame codec; it does not start screen capture or stream frames by itself. Screen capture remains owner-started locally and is not started remotely.
- GitHub Actions for the newest commit must complete before treating this pass as CI-verified. No physical two-device testing or security audit has been performed.


## Secure live screen streaming integrated · current milestone

- Connected the existing owner-started `ScreenCaptureService` H.264 latest-frame store to a new `SecureScreenStreamSender`. The sender reads only an already-active MediaProjection session; it never starts screen capture.
- Target owner must separately grant `SCREEN_VIEW` in the secure Internet panel for the current session. A controller may then request `screen.stream.start`; the target router checks the authenticated peer, owner grant, request ID and monotonic command sequence.
- H.264 codec configuration and bounded access-unit chunks are sent as versioned RemoteMessage envelopes over the authenticated DTLS DataChannel. The controller reassembles and decodes frames to a SurfaceView using MediaCodec.
- Added target-side immediate revocation of remote command permissions. The sender is closed on explicit revoke, secure session stop, connection failure, panel disposal, and when the owner stops local screen capture. Screen capture still has its persistent local notification and Android's MediaProjection consent flow.
- Added a bounded `command.result` response envelope so the controller receives accepted/rejected command status.
- The sender checks for local capture termination to avoid spinning after ScreenVideoStore is cleared. Codec configuration is validated before decoder setup.
- Automated CI for the earlier UI integration commit `a03d88e` completed unit tests successfully and proceeded to APK assembly. The later lifecycle, command-result and codec-validation commits are still awaiting their own final CI results.
- No two-device Internet/NAT test or physical Samsung test has been performed. Video quality, latency, codec compatibility and behavior under congestion remain unverified on hardware.
- Remote screen input, files, notification sync, camera, microphone and PTT are still not migrated to the secure Internet channel.


## Secure remote screen input · integration pass

- Added incremental capability grants to `SessionCommandGate` and `SecureRemoteCommandRouter`. Granting `SCREEN_INPUT` no longer replaces the existing `SCREEN_VIEW` grant or resets replay sequence state.
- Added target-side `screen.input` command handling for normalized tap/swipe gestures and bounded text insertion into the currently focused editable field, using the existing Android Accessibility bridge.
- The target owner must manually enable RKRemote in Android Accessibility settings and separately grant `SCREEN_INPUT` in the secure Internet panel. The controller can tap or drag directly on the received video surface and submit text to the focused field.
- Accessibility dispatch is rejected when the service is not active, the action type is unknown, coordinates/duration are invalid, or text exceeds the existing 4096-character limit. Android remains responsible for the actual gesture/text action.
- Added a unit test proving incremental capability grants preserve monotonic sequence protection.
- CI for the latest input integration is still running; no physical device validation has been done.


## Secure remote screen input and notification sync

- Remote touch gestures and focused-field text insertion are now registered as target-side commands over the authenticated DTLS DataChannel. The target owner must explicitly enable Android Accessibility and separately grant `SCREEN_INPUT` for the current session.
- The Accessibility bridge does not collect accessibility events or scrape screen text. It only dispatches bounded normalized gestures and sets text in the currently focused editable field.
- Target owners can revoke all remote command capabilities immediately. Session teardown also closes the sender and ends the command gate.
- Added read-only notification sync: it checks Android Notification Access locally, reads the AES-GCM encrypted local event store, returns at most the latest 20 events per request, and truncates fields. The target owner must explicitly grant `NOTIFICATIONS` for the session. Controller UI displays only a short preview of up to five returned events.
- Notification sync uses the versioned message envelope over the secure WebRTC DataChannel; it does not send notification data through the Cloudflare Worker.
- Notification access and remote input are separate Android settings and separate owner grants. Revoking session capabilities stops further requests; Android settings can also revoke either permission.
- CI for earlier screen-input integration commits passed unit tests and APK assembly in the available runs. The latest notification-sync commits are still running through CI; no hardware test has been performed.
- Filesystem operations, camera, microphone and PTT are not yet wired to the secure Internet command channel. SAF access remains limited to owner-selected document trees.


## Secure Internet file browsing and downloads

- The target exposes folder listing, downloads and uploads over the authenticated WebRTC DataChannel only; Cloudflare remains signaling-only.
- Every document ID is checked against the exact owner-selected Storage Access Framework tree. No raw storage paths or broader storage permissions are used.
- Folder listings are capped at 40 entries per response. A single file transfer is limited to 50 MiB and uses ordered 24 KiB chunks with bounded DataChannel buffering.
- The controller writes downloads to a temporary app-private file, checks sequential offsets and the final SHA-256 digest, then publishes the verified result to `Download/RKRemote/Received` on Android 10+ (app-specific Downloads on older Android). Uploads use the Android document picker on the controller and write only inside the selected target SAF tree. Both directions use ordered chunks and SHA-256 validation.
- Existing files are not overwritten. Uploads require a writable SAF grant; incomplete uploads are removed on disconnect or invalid chunks. Remote deletion, rename, move, and arbitrary path access are not implemented.
- The target owner must explicitly grant the FILES capability for each secure session. Only one transfer runs at a time. The source integration has passed the unit-test and debug-APK CI run at commit `242ad46` before the final SAF validation fixes. The newest commits are undergoing CI again. Two-device Android validation, SAF provider behavior and real-world transfer reliability are not yet certified.


## Secure microphone monitoring

- The target-side microphone must be started locally by the owner with Android's RECORD_AUDIO permission and its persistent foreground notification.
- The target owner separately grants the `MICROPHONE` capability for the current secure session. The controller can then request or stop a live mono 16 kHz PCM stream over the authenticated WebRTC DataChannel.
- Audio is kept in a latest-chunk store on the target and a bounded playback queue on the controller; no audio is written to disk. If the network cannot keep up, older queued playback chunks are dropped to bound latency.
- Remote requests never start or stop Android microphone capture. The owner can stop the local service or revoke the session capability at any time.
- This is source-integrated and awaits the current CI run and physical two-device audio/feedback testing. Echo cancellation and noise suppression depend on device support and do not guarantee feedback-free operation.


## Secure PTT over Internet P2P

- The target owner must start the PTT speaker locally; it remains a visible foreground service with a persistent stop action. The target owner separately grants `PUSH_TO_TALK` for the secure session.
- The controller must start its microphone capture locally with Android permission before holding the push-to-talk surface. While held, short mono 16 kHz PCM chunks are sent over the authenticated DTLS DataChannel; release sends the stop command and clears queued target audio.
- The target never starts its speaker or microphone from a remote command. If the target speaker is not locally enabled, PTT requests are rejected. Queue depth remains bounded and old chunks are dropped under congestion.
- This source integration requires CI and physical two-device testing for latency, audio route and feedback. It is half-duplex and not a phone-call/full-duplex path.


## Secure camera snapshots

- The target owner must start the front or rear camera locally with Android CAMERA permission and its persistent foreground notification. The owner separately grants `CAMERA` for the secure session.
- The controller can request a snapshot of the latest in-memory JPEG frame. The target does not remotely activate the camera. The image is transferred in ordered bounded chunks and the controller verifies SHA-256 before decoding it.
- Frames are limited to 2 MiB. The owner can stop an in-progress transfer, revoke all capabilities, or stop the camera locally. This is snapshot transfer, not continuous camera video.
- Source integration awaits CI and physical two-device camera testing. Sensor behavior, image quality and device-specific camera availability are not yet validated.

## Secure P2P file transfer · transfer-ID scoped cancellation (2026-11-08)

- Every upload/download now has a transfer ID tracked independently on controller and target.
- The target accepts `file.transfer.cancel` only when the supplied ID matches its active transfer. Matching uploads are closed and incomplete SAF documents deleted; matching downloads receive a thread-safe cancellation token and stop before the next read/send or completion.
- A shared active-transfer guard covers the race where cancellation arrives while the download worker is queued but before its cancellation token is registered.
- The controller includes the active transfer ID in cancellation requests, stops its upload worker, and aborts any incomplete local download. It also clears the active transfer when the target rejects a request/chunk or reports completion/error.
- Unknown, stale, duplicate, or mismatched IDs cannot cancel another transfer. Rejected file chunks now return a command result carrying the matching transfer ID so the sender can stop instead of blindly continuing.
- Cancellation does not delete completed files. Operations remain limited to the owner-selected SAF tree, overwrite remains disabled, transfers remain capped at 50 MiB, and SHA-256 verification remains required.
- JVM tests cover per-transfer cancellation isolation, duplicate registration, repeated cancellation and cleanup.
- CI verifies unit tests and APK assembly for the latest source revision; see the branch's latest Android build workflow. No two-device, disconnect-during-transfer, or physical Android validation has been performed. Cancellation remains best-effort if the connection has already failed; disconnect cleanup remains in place.


## Current source-level hardening and navigation · 2026-11-08

This section supersedes older milestone notes above where they describe Internet features as not yet integrated. The current branch includes secure Internet signaling, Noise XX identity binding, WebRTC DataChannel transport, owner-approved screen viewing/input, notification sync, SAF file browsing and transfer, camera snapshots, microphone streaming, and push-to-talk source integrations. These are source integrations, not proof of successful two-device operation.

- Main screen now uses a top-left hamburger navigation drawer. Home, secure Internet, LAN control, pairing, Android permissions, notification history, and owner-selected file access are presented as separate pages. Feature panels remain composed while navigating so a page switch alone does not tear down an active connection.
- Noise handshake state violations are now terminal. A duplicate or out-of-order handshake frame received after establishment closes and invalidates the transport instead of only throwing an exception while leaving the session usable. A JVM regression test covers this fail-closed behavior.
- Cloudflare signaling URL validation now rejects embedded URL credentials and non-root custom paths, in addition to requiring WSS, no query/fragment, a valid host, and a 256-bit lowercase-hex room ID. JVM tests cover accepted Worker origins and rejected URL/room configurations.
- CI run for the hamburger navigation commit `95de1b9` completed successfully, including unit tests and debug APK assembly.
- CI run for the Noise fail-closed regression commit `e71a8eb` completed successfully, including unit tests and debug APK assembly.
- The combined Cloudflare URL-validation and current source changes are being checked by GitHub Actions; see the branch's latest Android build workflow before treating this exact revision as build-verified.
- Deployment of the Cloudflare Worker, TURN provisioning, and two-device testing are intentionally deferred. The final validation stage must include installation on both devices, owner-permission review, SAS/peer identity comparison, direct ICE/STUN connection, TURN fallback, network switching/reconnect, and real file/media/notification tests.
- No independent cryptographic/security audit has been performed. The Torann Noise implementation and the full application remain subject to independent review before production use.


## Secure transport hardening follow-up · 2026-11-08

- File-transfer cancellation is now bound to the exact active transfer ID on both controller and target. A thread-safe cancellation registry stops only the matching download; the SAF bridge aborts/deletes only the matching incomplete upload. The panel's active-transfer guard also handles cancellation arriving while a worker is queued before its cancellation token is registered.
- The controller sends the transfer ID in `file.transfer.cancel`, stops its local upload worker or aborts its temporary download, and clears state when the target rejects a request/chunk or completes/reports an error. Rejected chunk commands now receive a scoped `command.result` so the sender can stop.
- JVM tests cover cancellation ID isolation, duplicate registration, repeated cancellation, and cleanup.
- Added strict DTLS SHA-256 fingerprint parsing: exactly 32 hexadecimal octets, all SDP fingerprint attributes valid and consistent, and constant-time comparison with the fingerprint carried in authenticated Noise signaling. Tests cover missing, malformed, inconsistent, and mismatched fingerprints.
- Cloudflare signaling now closes the WSS socket when it receives malformed, oversized, or unknown text control events rather than merely reporting an error and keeping the socket open.
- CI run `42478572064` completed successfully for commit `c100592c`, including transfer-ID cancellation and strict DTLS fingerprint parser tests, debug APK assembly, and artifact upload. The subsequent fail-closed signaling-control change is being checked by run `42478685055`; documentation-only commits followed it. Verify that latest run before treating the current branch tip as build-verified.
- No Cloudflare deployment, TURN test, two-device test, real NAT traversal, or physical Android validation has been performed. These remain part of the final stage.


## Protocol parser hardening · 2026-11-08

- Cloudflare room lifecycle text events are parsed by a dedicated bounded parser: maximum 1 KiB, version 1 only, three known lifecycle event types, and object-only payloads. Malformed JSON and wrong payload types become controlled parse errors; the WSS client closes on invalid text.
- The main RemoteMessage parser now fails closed for malformed JSON, unknown message types, missing/non-string IDs, non-numeric or fractional sequence/timestamp values, and missing or non-object payloads. Payload size remains bounded by the 64 KiB protocol envelope.
- Added JVM tests for malformed JSON, array/missing payloads, string protocol versions, fractional sequences, and strict Cloudflare room-control parsing.
- Removed a duplicate command.result branch in the controller's when dispatch after CI surfaced the Kotlin duplicate-branch warning.
- CI for commit b28aa17e passed unit tests after the Cloudflare parser exception normalization; debug APK assembly was still running at the last check. Strict main-protocol parser and duplicate-branch cleanup are newer commits and must pass the next complete CI run before this branch tip is called build-verified.


## WebRTC transport-loss revocation · 2026-11-08

- WebRTC ICE/PeerConnection disconnection and DataChannel closure are now surfaced to the secure session coordinator.
- In this version, transport loss closes the entire secure Internet session, clears pending signaling, ends the target command-router session, and drops the authenticated peer identity. The target's per-session capability grants therefore do not remain active after a dead DataChannel.
- Automatic ICE restart/reconnect is deliberately not attempted yet. The user must establish a fresh session, compare SAS/peer identity again, and re-grant capabilities locally. Reconnect behavior remains a later hardening task.
- This is source-integrated and awaiting the latest GitHub Actions run; no real network-loss test has been performed on devices.


## Latest transport hardening

At commit `34cc4a6`, WebRTC DataChannel sends serialize concurrent writers and preflight the byte size of every fragment in a logical RKD1 message against the 2 MiB outbound-buffer ceiling. Congested sends are rejected before the first fragment, avoiding partial messages caused by a known-full buffer. An unexpected mid-send channel close/rejection tears down the peer. This is fail-fast backpressure; no background queue or automatic retransmission is claimed. CI verification for this commit is pending.

## Secure Internet session lifecycle hardening · 2026-11-08

- The Cloudflare signaling client now has a terminal, idempotent close state. Closing cancels the WebSocket, cancels pending OkHttp calls, shuts down its dedicated dispatcher executor, and evicts its connection pool. Reusing the same client after close is rejected; a fresh secure session creates a fresh client.
- WebRTC teardown now releases the DataChannel and PeerConnection independently with best-effort cleanup, clears pending ICE/framing state, and removes the transient-disconnect timer. One native disposal failure no longer prevents attempting to release the other resource.
- SecureInternetPeer teardown now revokes the command router, clears authenticated peer identity, and closes the Noise/signaling session even if WebRTC disposal throws.
- Noise/signaling teardown uses nested `finally` cleanup so the WebSocket is closed even if handshake cleanup fails. Room lifecycle callbacks are serialized with close; late WebRTC frames, candidates, and SDP failures are ignored after teardown.
- The Secure Internet UI uses an atomic active-peer reference to reject queued callbacks from superseded sessions. Disconnect/failure cleanup is performed on the UI thread only if that peer still owns the active session, preventing an old callback from aborting transfers or closing resources belonging to a new session.
- A failed partial connection startup closes the partially created secure session. Panel disposal revokes grants and closes active session resources.
- Added a JVM regression test proving Cloudflare client close is idempotent, terminal, and rejects later connect/send attempts.
- GitHub Actions has completed earlier CI runs successfully through commit `d5fc249`; the lifecycle hardening commits after that point are undergoing fresh unit-test and debug-APK CI. Do not treat the latest branch tip as build-verified until the run for the exact tip completes.
- No physical two-device, process-death, network-switch, ICE-loss, or TURN test has been performed. The Internet session remains UI-owned rather than a persistent background service; closing the panel or process ends the session. The legacy LAN TCP path remains plaintext and LAN-only.

## Network handoff recovery · 2026-11-08

- Secure WebRTC peers now observe Android's default network using `ConnectivityManager.registerDefaultNetworkCallback`. A lost active default network starts the existing bounded disconnect grace period; a handoff to a different default network requests an ICE restart.
- The controller is the only side that creates ICE-restart offers. A target-side network change sends a small `ice-restart-request` over the already mutually approved Noise channel. This avoids simultaneous-offer glare and never sends SDP/ICE in plaintext.
- ICE restart offers use WebRTC's `IceRestart=true` constraint and reuse the authenticated Noise signaling path, DTLS fingerprint checks, and the existing DataChannel. Restart attempts are serialized, limited by a 10-second cooldown, and cleared after ICE/PeerConnection reconnects.
- Added an owner-visible **Repair connection** action for a manual recovery request. It does not create a new identity, bypass SAS approval, or restore revoked capability grants.
- The Android network callback is unregistered during WebRTC teardown. A persistent loss beyond the existing 12-second grace period still closes the secure session; a fresh Noise/SAS-approved session is required after terminal failure.
- Current CI is checking the ICE-restart and UI integration commits. Do not call this feature build-verified until CI for the exact branch tip passes.
- Network handoff recovery is source-integrated only. Real Wi-Fi-to-cellular transitions, captive portals, VPN changes, ICE restart timing, NAT combinations, and TURN fallback still require two-device testing. This does not guarantee recovery from every carrier NAT or network outage.

## Cloudflare room lease and deployment guide · 2026-11-08

- The Cloudflare Durable Object room lease is now a 10-minute **inactivity timeout**, not a fixed ten-minute session lifetime. It is refreshed when the second peer joins and on every valid binary Noise frame. An active signaling session is therefore not terminated solely because ten minutes have elapsed; a completely idle room is still reclaimed.
- Added `cloudflare/worker/README.md` with deployment commands, endpoint format, room limits, privacy metadata, and TURN provisioning guidance. Cloudflare deployment remains an owner-operated step; no Worker URL or TURN service has been provisioned by this repository change.
- The Android unit-test and debug-APK CI run for commit `e4eefe1` completed successfully and uploaded artifact `RKRemote-debug-apk`. The subsequent Worker lease and documentation commits do not change Android source, but CI for the current branch tip must still complete before claiming the whole branch is green.
- At the time of this note, secure Internet sessions were still Compose-panel-owned; see the newer foreground-service integration section below for the current behavior.


## Secure Internet foreground-service ownership · 2026-11-08

- The explicit Connect action now starts `SessionForegroundService` as a visible `connectedDevice` foreground service before the Noise/WebRTC session proceeds. The service takes ownership of the active `SecureInternetPeer` in-process, so Android gives the active connection foreground-service process priority while the app UI is backgrounded.
- The persistent notification has a local **Stop session** action. It closes the Internet peer, revokes target-side remote command grants, and also stops the LAN agent if the user chooses the global notification Stop action. The in-app Internet Stop action stops only the Internet peer and leaves an independent LAN agent running.
- Transport disconnect/failure callbacks stop the Internet foreground service and clear the active UI state. Leaving/destroying the panel also stops the service and closes the peer; the current activity keeps feature panels composed when navigating between pages, so opening another page does not itself tear down the session.
- The service deliberately does **not** attempt to restore a WebRTC/Noise connection after process death. Live sockets, ephemeral handshake state, ICE credentials, and DataChannel state cannot safely be reconstructed from the old Intent. The Internet service uses `START_NOT_STICKY` unless an independent LAN agent is also active; in that case only the LAN agent's existing encrypted credential may be restored. No peer identity, SAS approval, capability grants, camera, microphone, or screen capture is silently restored.
- If Android rejects foreground-service startup (including background-start or platform restrictions), the peer is closed and the user sees the startup error. This is not a guarantee against OEM battery management or force-stop.
- CI for the exact current branch tip is required before this change is considered build-verified. No physical backgrounding, process-kill, device reboot, two-phone, or Android 14/15 policy test has been performed yet.
