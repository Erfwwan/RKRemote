# Section 2 — continuous interactive screen video

## Agreed user requirements

- Replace the screen JPEG snapshot endpoint and periodic polling UI with a continuous video stream. The controller must receive and decode video, not a sequence of still-image requests.
- Capture only after the target owner approves Android MediaProjection and while the visible foreground-service notification and local stop action remain active.
- Encode with Android MediaCodec (H.264/AVC hardware encoder when available) and decode on the controller with MediaCodec directly to a Surface.
- Cap the encoded video dimensions so the longest side is at most 480 pixels, preserving the target display aspect ratio. Do not generate larger screen frames for this feature.
- Target at least 5 frames per second where the device and network permit; do not promise a fixed frame rate or latency on all devices.
- Use a persistent, framed streaming connection rather than repeated HTTP GET /screen.jpg requests. Keep only a bounded/latest-frame queue and discard stale frames under backpressure to avoid growing latency.
- Prioritize remote input commands over video delivery. The controller should support direct interaction with the displayed stream; touch coordinates must account for aspect-fit letterboxing.
- Support ordinary touch, long press, drag and scroll gestures, plus the already implemented Accessibility-mediated focused-field text insertion. Android secure surfaces, permission dialogs, protected DRM content, and OS-level restrictions remain outside the guarantee.
- Screen rotation control is not required; the stream should follow the current display dimensions without adding a remote rotation command.

## Transport and security prerequisites

The current executable LAN path is raw HTTP with a temporary bearer token and no TLS. The screen is sensitive data. Do not ship or claim this stream is safe over that plaintext path or expose it to untrusted networks. Integrate and validate the authenticated encrypted transport required by Section 1 before sending live screen video or persistent pairing/session credentials. The standalone WSS primitive already in the repository is not yet wired into the executable LAN path.

## Implementation sequence

1. Replace ImageReader-to-JPEG conversion with a MediaProjection VirtualDisplay targeting a MediaCodec AVC encoder input Surface.
2. Publish codec format/configuration data and length-framed H.264 access units to a bounded single-viewer stream channel. Drop stale queued frames rather than allowing latency to accumulate.
3. Add an authenticated long-lived video-stream endpoint on the encrypted transport; cleanly stop a viewer on socket close, owner stop, projection revocation, or transport failure.
4. Add a controller MediaCodec decoder rendering to SurfaceView/SurfaceTexture. Parse stream dimensions/configuration, handle codec recreation, and close the stream on lifecycle cancellation.
5. Replace the JPEG preview loop and one-shot snapshot button in the UI. Make the rendered video surface the touch surface; support tap, long press, drag and scroll with normalized coordinates and aspect-fit offset correction.
6. Keep command and video paths independently bounded so a slow video consumer cannot block touch commands.
7. Add unit tests for frame framing/size limits, stale-frame replacement, malformed stream metadata, and coordinate mapping. Run Android CI and then validate on the actual two phones, including network loss and projection stop.

## Acceptance criteria

- No screen JPEG polling or one-shot screen snapshot remains in the controller UI or active screen endpoint.
- A controller receives an actual H.264 video bitstream over one persistent connection and decodes it to a display surface.
- Maximum encoded width/height obeys the 480-pixel longest-side cap.
- Frame queues are bounded; reconnect starts with a current keyframe/configuration rather than replaying stale frames.
- Touch input remains responsive during video delivery and cannot be replayed automatically after disconnect.
- Owner consent, persistent foreground notification, local stop, and Android permission boundaries remain intact.
- CI passes; physical two-device validation is recorded separately and is not inferred from a successful build.

## Current implementation status

This file records the agreed Section 2 specification only. The current executable implementation still captures JPEG snapshots up to 1600 pixels and the controller polls them approximately once per second. No continuous H.264 stream or 480-pixel cap is implemented yet. Do not describe the requested live-video feature as implemented until the sequence and acceptance criteria above have been completed and tested.
