# RKRemote release and acceptance checklist

This checklist prepares a candidate build. It does not certify the app as production-ready and does not replace owner-operated two-device testing.

## Build matrix

- [ ] JDK 17 and Gradle 8.11.1 are available.
- [ ] Run `gradle --no-daemon :app:testDebugUnitTest`.
- [ ] Run `gradle --no-daemon :app:assembleDebug`.
- [ ] Run `gradle --no-daemon :app:assembleRelease`.
- [ ] Confirm the workflow run belongs to the exact branch commit being evaluated.
- [ ] Confirm both APK artifacts were uploaded successfully.
- [ ] Treat the CI release APK as unsigned unless a signing configuration was intentionally supplied.

## Signing and versioning

- [ ] Create the release keystore under the operator's control and back it up offline.
- [ ] Keep keystore files and passwords outside Git; never send private signing material in chat or commit it to the repository.
- [ ] Supply all four `RKREMOTE_RELEASE_*` environment variables together for a signed release.
- [ ] Verify the signed APK with `apksigner verify --verbose --print-certs`.
- [ ] Record the signing certificate fingerprint in the operator's release records.
- [ ] Increase `versionCode` before publishing an update; preserve the same signing key for in-place upgrades.
- [ ] Do not distribute a debug APK as a production release.

## Installation and permission review

- [ ] Install the candidate APK on the target Android device.
- [ ] Confirm the app launches and the navigation panel works.
- [ ] Grant only the permissions needed for the selected tests.
- [ ] Confirm Camera and Microphone require explicit local permission and owner-started capture.
- [ ] Confirm Notification Access and Accessibility are enabled manually in Android Settings only when needed.
- [ ] Confirm the folder picker grants access only to the selected SAF tree.
- [ ] Confirm active sessions show a persistent notification with a working local Stop action.
- [ ] Confirm stopping capture clears the corresponding published in-memory media.
- [ ] Confirm there is no request for contacts, call log, location, or broad shared-media/storage permissions.

## Two-device acceptance tests — final stage

Run these only when both devices are physically available. Use a trusted test network first; do not expose the plaintext legacy LAN listener to the public Internet.

- [ ] Pair the intended target and controller; compare the short authentication string on both screens before approving.
- [ ] Verify the target owner can reject pairing and revoke an active session.
- [ ] Verify capability grants are scoped to the requested operations.
- [ ] Verify screen streaming, tap, drag, long press, and stop/reconnect behavior.
- [ ] Verify file listing, upload, download, cancellation, checksum verification, and reconnect behavior with test files.
- [ ] Verify notification history only after manually enabling Notification Access and granting the session capability.
- [ ] Verify rear/front camera only after the target owner starts the relevant capture and sees its persistent notification.
- [ ] Verify microphone monitoring and half-duplex PTT; check feedback and stop controls.
- [ ] Verify app backgrounding, screen lock, network switching, process reclaim, and explicit Stop behavior.
- [ ] Verify direct ICE on two different networks; test TURN relay separately if a TURN server is provisioned.
- [ ] Verify Cloudflare Worker room join, peer disconnect, reconnect, and room expiry using the operator's deployment.
- [ ] Record Android version, device model, network type, test result, and any logs for every failed case.

## Known release blockers / operator dependencies

- A release signing key must be created and protected by the operator.
- The Cloudflare Worker must be deployed to the operator's account.
- TURN is not bundled or provisioned; some carrier or restrictive networks will require a separate TURN service and credentials.
- Physical two-device, Samsung-specific, long-duration, battery, NAT/TURN, and independent security-audit validation have not been completed by CI.
- The legacy LAN TCP transport is plaintext and is restricted to trusted private Wi-Fi only.
- Secure Internet sessions are not restored after process death; the visible app session must remain alive.
- Upload resume after a terminal session/process end is not implemented.

A green CI result confirms only the automated tests and build tasks for that exact commit. It is not a security audit, runtime guarantee, or production-readiness certification.
