# Section 3 — File browsing and transfer

## Implemented behavior

- The target owner selects a storage tree using Android's Storage Access Framework (SAF); the app persists the read/write URI grant.
- The controller can browse nested folders and list every child document returned by the selected document provider. The previous 200-item listing cap is removed.
- The controller can download files from the target and upload files into the currently browsed target folder.
- Both directions use 64 KiB streaming buffers. File contents are not accumulated in a single in-memory byte array, and the former 8 MiB application limit is removed.
- Downloaded files are stored automatically in the controller's public Downloads/RKRemote/Received folder on Android 10+. Existing names are preserved by generating a numbered alternative rather than overwriting. On Android 8/9, the fallback is the app-specific Downloads/RKRemote/Received directory.
- Uploads refuse to overwrite an existing file in the selected target folder.
- The controller UI supports opening nested folders and navigating back. File operations are limited to viewing/listing, upload, and download; there is no remote delete, rename, move, or copy operation.

## Android permission boundary

This is not unrestricted access to every byte on the target phone. The owner must explicitly select a folder through the Android system picker. RKRemote can then browse and transfer documents exposed below that selected tree while the persisted SAF grant remains valid. Android and individual document providers may prevent selecting or accessing protected locations such as parts of Android/data, Android/obb, system/private app storage, or certain provider-managed locations. The app does not bypass these restrictions.

## Large-file behavior

The app no longer imposes a fixed per-file size limit. Transfers are streamed with bounded memory use. Actual maximum size and completion time depend on free storage, the selected document provider, filesystem/provider behavior, network stability, and Android process lifetime. Transfers are not resumable yet: if a connection or process is interrupted, the incomplete destination is deleted where the provider allows it, and the user must start the transfer again. The UI reports completion or failure; progress percentage and pause/resume are not yet implemented.

## Security and validation

The current LAN bearer-token HTTP transport is still plaintext. File contents and credentials must only be transferred over a trusted private Wi-Fi network until the Section 1 authenticated encrypted transport is integrated. Do not expose TCP port 18765 to the public internet.

The code changes have been committed to the development branch. GitHub Actions build/test and physical two-device tests must pass before this section can be called device-validated.