# Onlooker Monitor

Native Kotlin Android prototype for detecting a likely physical onlooker with the front
camera and showing a best-effort opaque privacy shield. It follows
[`SCREEN_ONLOOKER_MONITOR_METHODOLOGY.md`](SCREEN_ONLOOKER_MONITOR_METHODOLOGY.md).

The current milestone detects this provisional condition:

```text
more than one usable face
AND an additional face has a screen-oriented head pose
AND the additional face is present in the current analyzed frame
```

It does not yet identify the owner or estimate eye gaze. Detection is not a security
guarantee: Android apps can suppress overlays, and an application overlay cannot cover every
system or lock-screen surface.

## What is implemented

- On-device front-camera analysis with CameraX and the bundled ML Kit face detector.
- Stable multi-face tracking with tracking-ID and bounding-box overlap association.
- Evidence-based onlooker filtering: an additional screen-oriented face must persist for the
  trigger debounce across several analyzed frames before the shield fires.
- A frame-quality gate derived from the camera luma plane: near-black or flat detections are
  rejected as sensor noise, poor light lengthens the debounce and raises the size floor, and
  near-darkness suppresses triggering outright.
- Trigger suppression while the scene is churning or the phone is moving, so motion blur and
  a face appearing from nothing cannot shield on their own.
- Auto-exposure metering on the primary face when the scene is strongly backlit.
- A text-free, opaque black privacy shield that extends into the cutout and system-bar layout
  areas Android exposes to application overlays.
- A foreground service with persistent status, high-priority alerts, vibration, and a Stop
  notification action.
- Camera-contention recovery, runtime permission/overlay health checks, degraded-state
  reporting, and automatic recovery after valid analysis resumes.
- A status screen showing the state, visible-face count, inference time, and frame counters.
- No internet or storage permission; frames and face crops are not stored.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for module boundaries and Kotlin APIs.

## Build and install on your phone

The easiest path is Android Studio:

1. Install the latest stable Android Studio with JDK 17 and Android SDK Platform 37.
2. Open this directory as a project and let Gradle sync.
3. Connect an Android 12+ phone with USB debugging enabled.
4. Select the `app` run configuration and press **Run**.

Or from a configured terminal:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone:

1. Grant camera and notification access.
2. Open **Display over other apps** and allow Onlooker Monitor.
3. Return to the app and tap **Arm protection** while the activity is visible.
4. Keep yourself visible as the primary face, then have a second person enter the front
   camera view and look generally toward the screen.
5. Stop from the persistent notification or reopen the app and tap **Stop protection**.

The shield itself is intentionally solid black and contains no visible controls or text.
Double-tap anywhere on it to dismiss it immediately; detection remains armed but cannot show
the shield again for one second. Use the persistent notification's **Stop** action to stop
monitoring completely. Android may still draw protected system UI or lock-screen surfaces
above an application overlay.

For the first test, use even lighting. A second screen-oriented face must persist for about
0.7 s (longer in poor light) before the shield appears, so expect roughly one second between
an onlooker arriving and the screen blanking. The status line reports reduced confidence in
low light, strong backlight, and near-darkness rather than shielding on untrustworthy frames.
Camera capture, face inference, main-thread scheduling, and display composition mean
the end-to-end response cannot be guaranteed below 5 ms. The UI reports visible-face count,
last inference time, analyzed frames, and skipped frames. Values in `MonitorConfig` are
false-positive-conscious provisional settings, not validated security thresholds; it is the
single surface to retune from measured device sessions.

## Release preparation

Before treating a build as production:

- Replace `applicationId` and configure an externally protected release signing key.
- Run device tests across Android 12–16 and selected OEMs.
- Collect labeled false-shield/missed-face sessions without storing camera frames in the app.
- Tune thresholds only from that data and document the resulting device matrix.
- Test camera contention, permission revocation, overlay blocking, rotation, lock/unlock,
  low light, masks/glasses, thermal throttling, and long-duration battery behavior.
- Complete an accessibility review and translations.
- Decide the deferred owner-recognition and presentation-attack policies before adding face
  embeddings.

Do not add `INTERNET`, storage, AccessibilityService, or MediaProjection permissions to this
milestone. Review [`SECURITY.md`](SECURITY.md) before changing the privacy boundary.
