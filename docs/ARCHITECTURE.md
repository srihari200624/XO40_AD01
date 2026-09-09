# Architecture

## Data flow

```text
CameraX YUV ImageProxy
        │
        ▼
FaceFrameAnalyzer ── bundled ML Kit detector
        │             bounds + tracking ID + Euler angles only
        │          ── LumaGrid sampled from the Y plane
        │             frame lighting, per-detection contrast, motion score
        ▼
MonitoringEngine
   ├── FaceTracker (ML ID, IoU fallback, expiry)
   ├── usable-size, luma/contrast, and head-pose gates
   ├── stable primary-face selection
   ├── scene-settle and motion trigger suppression
   └── OnlookerPolicy (per-candidate evidence, trigger/clear debounce)
        │
        ▼
OnlookerMonitorService
   ├── MonitorStatusStore → activity status UI
   ├── PrivacyShieldController → opaque application overlay
   └── MonitorNotifications → foreground status, alert, Stop action
```

`ImageProxy` is passed directly to ML Kit through `InputImage.fromMediaImage`; no full-frame
bitmap is created. The luma plane is read directly with a strided sample -- a few thousand
bytes per analyzed frame -- into a coarse `LumaGrid` already expressed in ML Kit rotated
coordinates, so face-region lookups are a direct index. Every accepted, skipped, invalid,
failed, and completed analysis path closes the proxy. Only normalized face bounds, pose
values, IDs, counters, and aggregate luma statistics survive an analysis call.

## Boundaries intended for replacement

- `FaceFrameAnalyzer` is the ML Kit/CameraX adapter. A different detector should still emit
  `FaceObservation` values.
- `MonitoringEngine` is Android-free logic. It accepts observations and elapsed-realtime
  milliseconds, making it deterministic in unit tests.
- `PrivacyShieldController` contains all overlay-specific code.
- `MonitorNotifications` owns notification channels and pending intents.
- `MonitorStatusStore` is the in-process presentation boundary. Detection does not depend on
  the activity being alive.

## Identity semantics

This milestone has no enrollment and does not know who owns the phone. The first stable,
usable face is selected as the provisional primary track. It stays primary until lost; the
engine does not silently change the primary while that track remains visible. This is only
appropriate for validating multi-face tracking and response behavior.

ML Kit tracking IDs are preferred when present. IoU association supplies a local monotonic
track ID when ML Kit does not provide one or when a new face has not acquired an ID. Expired
tracks cannot inherit debounce evidence.

## Timing and failure behavior

- All policy times use `SystemClock.elapsedRealtime`, never wall-clock time or frame count.
- A long frame gap resets tracks and candidate evidence.
- Candidate evidence is per track and needs both elapsed time and a minimum frame count. It
  survives a short dropped-detection gap (`candidateGraceMillis`) so one missed frame does
  not erase a real onlooker, and restarts whenever triggering is suppressed.
- Triggering is suppressed while the usable-face set is still changing, while the luma grid
  reports camera or scene movement, and while the frame is too dark to judge. Suppression
  never shortens an already-active shield; that keeps its own clear debounce.
- A stable one-face scene uses low-duty analysis; multiple faces and non-active policy states
  use burst cadence.
- CameraX is allowed to recover its documented recoverable camera errors while the service
  reports degraded protection. Bind failures use capped exponential retry.
- Three consecutive ML inference failures move the visible state to degraded. A later valid
  result recovers it.
- Permission and overlay access are rechecked during monitoring. The app does not claim
  active protection when a required response channel is unavailable.

## Android lifecycle

The user must tap **Arm protection** from the visible `MainActivity`. The activity starts the
camera foreground service; the service promotes itself immediately, then binds CameraX to
its `LifecycleService`. It returns `START_NOT_STICKY`: Android must not silently recreate a
camera monitor without a fresh user-visible start. A Stop action unbinds CameraX, closes ML
Kit, removes the overlay and alerts, shuts down the analysis executor, and stops the service.

