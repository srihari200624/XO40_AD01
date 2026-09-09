# Security and privacy boundary

## Current guarantees

- Camera analysis is on-device using the model bundled in the APK.
- The manifest has no network or storage permission.
- Frames, crops, landmarks, and face images are never written to disk.
- The overlay is opaque, non-focusable, and non-touchable; it cannot imitate or interact
  with the obscured application.
- The service is not exported and its command actions cannot be invoked by other apps.
- Android backup is disabled.

## Non-guarantees

- `TYPE_APPLICATION_OVERLAY` cannot cover apps that suppress overlays, every system surface,
  or all keyguard surfaces.
- Head orientation is not eye gaze and cannot prove that a person read the screen.
- The current primary-face heuristic is not owner recognition.
- Face detection can miss faces or produce false positives due to lighting, motion, distance,
  occlusion, camera hardware, and demographic or environmental variation.
- This milestone has no presentation-attack defense.

## Rules for future work

Do not add network upload, frame persistence, AccessibilityService, MediaProjection, or
storage access as a shortcut. If owner recognition is added, pin one reviewed embedding
model artifact and record its source, license, checksum, preprocessing, dimensions, and
delegate support. Store only encrypted normalized embeddings with an Android Keystore-backed
key and provide explicit deletion and re-enrollment controls.

Report security issues privately to the project owner rather than attaching real face images
or device recordings to a public issue.
