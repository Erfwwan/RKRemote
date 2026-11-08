# Section 2 · Independent still screenshots

## Behavior

- Still capture is a separate foreground service and a separate `MediaProjection` session from the H.264 live-video service.
- The target owner starts **Enable screenshot standby** and accepts Android's system screen-capture consent.
- While that foreground session remains alive, the controller can request `GET /screenshot.jpg` with the normal bearer token. No additional target-side prompt is required for each request.
- The image is captured at the selected display/content resolution and encoded as JPEG quality 95. It is not taken from the 480 px live-video stream.
- The controller displays the returned image and saves it under `Download/RKRemote/Screenshots/` on Android 10+.
- The target can stop screenshot standby from the app or its persistent notification.

## Android platform constraint

Android supports only one active MediaProjection capture session at a time for an app. Starting live video ends an existing screenshot standby session, and starting screenshot standby while live video is active is blocked by the UI. Stop live video before enabling screenshot standby. Android may also end the session when the screen locks, the user stops capture in system UI, another projection starts, or the process is killed. In those cases the owner must enable standby again and grant consent again.

This is a foreground, visible, owner-approved capture service; it is not a permanent permission. The service does not start automatically after reboot.

## Manual verification

1. On the target, start the LAN agent and enable screenshot standby; accept Android's screen-capture dialog.
2. Turn live video off.
3. On the controller, enter the target IP and session token; tap **Capture target screenshot**.
4. Verify the returned image is shown and a JPEG appears in `Download/RKRemote/Screenshots/`.
5. Repeat a capture without approving another system dialog.
6. Stop screenshot standby from the target notification and verify subsequent requests return HTTP 503.
7. Start live video and verify screenshot standby is ended by Android; re-enable standby only after live video stops.

## Security note

The current LAN transport is bearer-token authenticated but plaintext. Use only a trusted private network until the separate encrypted transport work is complete.
