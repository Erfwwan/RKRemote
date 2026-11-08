# Section 4 · Owner-controlled camera and microphone

## Scope

This section provides independent camera viewing and microphone listening from the target phone to the controller. It is separate from Section 5's push-to-talk speaker path.

## Target-side controls

- **Start camera** requests Android's CAMERA runtime permission if needed, then starts `CameraCaptureService` from a visible user action.
- **Stop camera** stops only camera capture.
- **Start microphone** requests Android's RECORD_AUDIO runtime permission if needed, then starts `MicrophoneCaptureService` from a visible user action.
- **Stop microphone** stops only microphone capture.
- Camera and microphone use separate foreground services and persistent notifications with explicit stop actions.
- The Android system camera/microphone privacy indicators remain in effect. The services do not restart automatically after process death or reboot.

## Controller-side controls

- **View camera** polls the authenticated `GET /camera.jpg` endpoint while the preview is active. The camera service selects a JPEG output no larger than 1280×720 where the device exposes a suitable size and limits publication to about two frames per second.
- **Listen to microphone** polls authenticated `GET /audio/chunk` and plays mono PCM through `AudioTrack`.
- Audio format is 16,000 samples/second, mono, signed 16-bit PCM. Chunks are kept in memory only. The target microphone service holds only the latest chunk, so old audio may be dropped when the network/controller is slower; this is a bounded-latency prototype, not lossless streaming.
- Stopping the controller preview/listener stops only the controller's playback/request loop. The target owner remains in control of the sensor service and can stop it independently.

## Authorization and privacy boundaries

- Network endpoints only read frames/chunks that an already-running target-side foreground service has published. They do not start either sensor.
- The target owner must physically start each sensor locally and accept the corresponding Android runtime permission.
- Camera frames and microphone chunks are memory-only and cleared when their service stops.
- The current bearer-token LAN transport is plaintext. Use trusted private Wi-Fi only; do not expose TCP port 18765 to the public internet or untrusted networks until encrypted transport is integrated.

## Manual acceptance test

1. Install the same debug APK on two Android devices on the same trusted private Wi-Fi.
2. On the target, start the LAN agent. Start camera locally and accept CAMERA permission.
3. Confirm the persistent camera notification and Android camera privacy indicator appear.
4. On the controller, enter the target address and session token, tap **View camera**, and confirm frames arrive.
5. Stop camera from the target notification; verify the controller no longer receives frames. Stop the controller preview.
6. On the target, start microphone locally and accept RECORD_AUDIO permission.
7. Confirm the persistent microphone notification and Android microphone privacy indicator appear.
8. On the controller, tap **Listen to microphone**, speak near the target, and confirm audio is heard. Use headphones to reduce feedback.
9. Stop microphone from the target notification; verify audio stops. Stop controller playback.
10. Repeat with both sensors independently: stopping one must not stop the other.
11. Deny each Android permission in turn and verify the corresponding sensor does not start.
12. Verify no camera image or audio data is written to disk by these services.

## Validation status

The service, endpoint and UI implementation is present in the branch. GitHub Actions has completed a successful Android unit-test and debug-APK build on the preceding branch revision; the latest Section 4 follow-up commit triggers a new workflow. Physical two-device acceptance testing has not been performed and must not be described as passed.
