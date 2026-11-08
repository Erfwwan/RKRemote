# Section 5 · Live walkie-talkie push-to-talk

## Intended operation

Section 5 provides a simple half-duplex, walkie-talkie-style voice path between two paired phones on the same trusted LAN. It is not a phone call and does not transmit both directions at once:

- **Controller → target:** the controller user presses and holds **HOLD TO TALK**. The controller captures mono 16 kHz, signed 16-bit PCM and posts short chunks to the target. Releasing the control cancels capture and stops sending.
- **Target → controller:** the target owner starts the microphone capture locally (Section 4). The separate Section 4 microphone-monitoring feature is not part of this push-to-talk path. Do not use it at the same time as PTT; the intended interaction is one speaking direction at a time.
- **Target speaker authorization:** the target owner must locally enable **PTT speaker** first. The authenticated network endpoint accepts audio only while this local playback service is active. Remote requests cannot start the speaker or microphone.

## Audio format and buffering

- Both directions use mono, 16,000 Hz, signed 16-bit PCM.
- Controller-to-target packets are approximately 100 ms (3,200 bytes) and are sent as authenticated HTTP POST requests.
- Incoming packets must contain complete 16-bit samples (even byte count) and be no larger than 4,096 bytes.
- The target playback queue holds at most 8 packets, approximately 0.8 seconds at the normal packet size. When full, the oldest packet is discarded to prefer fresh speech over delayed speech.
- Audio is transient and held in memory only. It is not recorded to a file or retained as conversation history.
- The target microphone-to-controller path remains a separate polling/chunk path from Section 4; listening and speaking can be enabled independently.

## Owner controls and privacy

1. On the target, the owner grants microphone permission and starts the microphone service locally if they want the return-audio path.
2. On the target, the owner enables the PTT speaker locally. A persistent foreground notification indicates that playback is enabled and offers a local stop action.
3. On the controller, the user grants RECORD_AUDIO permission, then presses and holds **HOLD TO TALK**. Releasing the control stops transmission.
4. The target owner can disable the PTT speaker from the app or its persistent notification. Disabling it clears queued audio.
5. The microphone and speaker are not remotely activated by the audio endpoints.

Android runtime permissions, privacy indicators, foreground-service rules, and OEM background restrictions still apply. Permission grants are reused until revoked, but Android does not guarantee uninterrupted background execution.

## Known limitations

- This is a low-complexity LAN prototype, not production VoIP.
- Each outgoing chunk currently uses a separate HTTP request. This adds latency and overhead compared with a persistent audio transport.
- The return microphone path uses polling and can drop chunks. Neither direction has a proper jitter buffer, packet-loss concealment, codec compression, or synchronized full-duplex timing. Android Acoustic Echo Canceler and Noise Suppressor are enabled on recording sessions when available; their effectiveness depends on device support and routing. The app does not switch Android into communication/call mode. Playback uses the normal media route and the target phone main loudspeaker.
- Acoustic feedback is reduced with Android echo cancellation where supported, but cannot be guaranteed absent on every phone. Playback is intentionally through the main loudspeaker. Without headphones, acoustic feedback may still occur if both phones are near each other; keep the controller microphone away from the target speaker and test moderate volume.
- The current LAN bearer-token transport is plaintext. Use only a trusted private Wi-Fi network; do not expose port 18765 to the public internet.
- Physical two-device, long-duration, Bluetooth-route, and network-jitter tests remain outstanding.

## Manual acceptance test

1. Install the same development APK on two Android devices on the same trusted private Wi-Fi.
2. Start the LAN agent on the target and locally enable **PTT speaker**. Confirm its persistent notification appears.
3. On the controller, grant RECORD_AUDIO permission, connect to the target, then press and hold **HOLD TO TALK**. Confirm speech is audible from the target speaker.
4. Release **HOLD TO TALK** and confirm transmission stops.
5. Confirm that PTT is one-way while pressed and that the app does not enter call mode.
6. Disable the target PTT speaker locally and verify subsequent PTT requests are rejected.

