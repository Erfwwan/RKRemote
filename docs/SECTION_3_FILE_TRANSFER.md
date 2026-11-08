# Section 3 — File-transfer management and network errors

## Implemented

- File browsing remains restricted to the folder explicitly selected by the target-device owner through Android's Storage Access Framework (SAF). Nested folders, direct-child listing, and upload destination selection are supported.
- Upload and download stream in bounded chunks; the secure DataChannel send queue applies backpressure rather than allowing unbounded memory growth.
- Uploads enforce the configured per-file limit, reject unknown sizes, refuse overwrites, validate ordered chunk offsets, and verify the final SHA-256 before reporting success.
- Downloads validate transfer IDs, file size, ordered offsets, and final SHA-256. Completed files are published in the controller's Downloads/RKRemote/Received location with collision-safe names.
- The controller reports upload and download percentage progress.
- A download interrupted by a recoverable DataChannel send failure retains its verified partial file and can resume from the saved byte offset after a new secure connection. A non-retryable source/provider/integrity error aborts and removes the partial file.
- Per-transfer cancellation is supported. Closing/revoking the target session cancels active target-side uploads and downloads; incomplete target-side upload destinations are removed where the provider allows it.
- Retry windows are bounded; a transfer does not retry indefinitely or silently claim completion.

## Remaining limitation

- Upload source progress is reported, and chunks are retried while the current secure session remains available. A partially uploaded file is **not yet durably resumable after the secure session closes or the app process is terminated**; the user must start that upload again.
- Provider-specific SAF behavior, free storage, process lifetime, and network conditions still affect completion.
- Real two-device and cross-network validation remains reserved for the final project stage.

## Security boundary

The target owner must explicitly select and retain access to the SAF folder. RKRemote does not bypass Android storage protections or access unrelated folders. Remote file operations are available only through the authenticated, owner-approved secure session; no remote delete, rename, move, or copy operation is exposed.
