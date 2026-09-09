# Screen Onlooker Monitor — Updated Methodology

> One document, two readers. The **Human Guide** explains the product and engineering
> approach. The **Machine Spec** expresses the same decisions in structured form. Keep both
> sections synchronized when the design changes.

---

## Human Guide

### Product objective

Build a native Kotlin Android application that detects a likely physical onlooker through
the front camera and reacts by obscuring the current display.

The application is intended to work system-wide while explicitly armed by the user. Its
protection is best-effort: Android applications can suppress third-party overlays, and an
application overlay cannot cover every lock-screen or system surface.

### Confirmed scope

- Native Android application written in Kotlin.
- Supports Android 12 and newer (`minSdk 31`).
- Android API 37 is the compile level; Android 16 / API 36 remains the provisional target.
- Distributed by sideloading rather than Google Play.
- All detection and recognition run locally on the device.
- Head orientation is sufficient; eye-gaze estimation is not required.
- Owner embeddings may be stored locally for a later recognition milestone.
- Efficiency and accuracy are high priorities, but numerical acceptance thresholds have
  not yet been selected.

### Out of scope

- Reading or capturing the pixels of other applications.
- Detecting whether another application is being screenshotted or recorded.
- AccessibilityService-based screen inspection.
- MediaProjection-based monitoring.
- Root, ADB, developer-mode, or privileged system-app techniques.
- Cloud processing, remote image upload, or a server backend.

Android's screenshot and screen-recording callbacks only report capture involving the
monitoring application's own visible activities. They do not provide system-wide detection
of capture involving other applications.

### On-device stack

- **Application:** Kotlin Android app with permission, enrollment, arm/stop, and status
  screens. The UI may use Views or Compose; this is not yet selected.
- **Runtime:** a camera-type foreground service, started from a visible activity after the
  user taps **Arm protection**.
- **Camera:** CameraX `ImageAnalysis` on the front camera.
- **Face detection:** on-device ML Kit Face Detection in fast mode.
- **Tracking and orientation:** ML Kit tracking IDs, face bounds, required landmarks, and
  Euler head angles.
- **Owner recognition, later:** an exact, versioned face-embedding model running through
  LiteRT/TFLite. The final model artifact has not yet been selected.
- **Response:** `TYPE_APPLICATION_OVERLAY`, with an opaque shield as the dependable visual
  treatment where overlays are allowed. Cross-window blur is optional.
- **Local security:** Android Keystore-protected encryption for stored owner embeddings.
- **Observability:** local timing, frame-drop, battery, thermal, and failure measurements.

### User and service lifecycle

1. The app explains why it needs camera, notification, and overlay access.
2. The user grants the necessary runtime permissions and special overlay access.
3. The user taps **Arm protection** while the app has a visible activity.
4. The app starts the camera foreground service and immediately posts its persistent
   notification.
5. CameraX binds front-camera analysis to the service lifecycle.
6. Monitoring continues while other apps are foregrounded.
7. The notification exposes monitoring status and a direct Stop action.
8. Stopping monitoring releases the camera, closes inference resources, and removes any
   privacy overlay.

Do not depend on silent background startup or boot-time camera startup. Modern Android
versions restrict starting a camera foreground service while the application is in the
background.

### Monitoring state model

```text
DISARMED
   |
   v
STARTING ---> DEGRADED
   |          ^     |
   v          |     |
ACTIVE -------+-----+
   |
   v
CANDIDATE_DETECTED
   |
   +---- cleared ----> ACTIVE
   |
   v
SHIELD_ACTIVE
   |
   +---- clear debounce ----> ACTIVE
```

- `DISARMED`: no camera access or analysis.
- `STARTING`: foreground service and camera pipeline are initializing.
- `ACTIVE`: camera and analysis pipeline are healthy.
- `CANDIDATE_DETECTED`: an additional screen-oriented face is being evaluated across time.
- `SHIELD_ACTIVE`: the overlay is visible and notification/haptic response has fired.
- `DEGRADED`: protection is unavailable because of permission, camera, overlay, or runtime
  failure. The user must be told which capability is unavailable.

### Camera and inference pipeline

1. Use the front CameraX selector and `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`.
2. Keep frames as YUV `ImageProxy` input for face detection; do not create a full-frame
   bitmap for every analysis.
3. Run all image analysis away from the main thread.
4. Always close `ImageProxy`, including error and cancellation paths.
5. Detect faces and retain only transient bounds, angles, and tracking state.
6. Apply a minimum usable face-size rule so distant, low-information detections do not
   enter later recognition stages.
7. Sample the luma plane of the same frame into a coarse grid before inference, and use it
   for frame lighting, per-detection brightness and contrast, and a frame-to-frame motion
   score. Do not build a bitmap for this.
8. Use elapsed time rather than frame count for debounce behavior, and additionally require a
   minimum number of analyzed frames so one frame can never satisfy the window.
9. Expire lost tracks and require renewed temporal evidence when a face reappears.
10. Meter auto-exposure on the primary face region when the frame is strongly backlit, at a
    throttled rate so exposure has time to converge.

Use adaptive analysis rather than one fixed frame rate:

- Low-duty analysis while the scene contains one stable face.
- A temporary higher-rate burst when another face appears or tracking becomes unstable.
- Return to low duty after the scene clears or stabilizes.

Exact rates, image resolution, and burst duration must be selected from measurements on the
supported devices.

### Head-orientation gate

Head orientation is determined from valid ML Kit Euler angles, not eye gaze. A candidate
should be considered screen-oriented only when all configured pose bounds persist over the
debounce window.

ML Kit does not expose a general face-detection confidence score. Reliability must therefore
come from temporal consistency, usable face size, stable tracking, valid pose values, and
measured behavior—not from a nonexistent detection-confidence threshold.

The exact pitch/yaw/roll ranges and debounce interval remain calibration parameters. They
must not be copied from a paper or selected without evaluation data.

### Trigger policy

For the first milestone, the provisional technical trigger is:

```text
more than one usable face is tracked
AND an additional face is screen-oriented
AND that candidate persists for the debounce interval AND a minimum frame count
AND triggering is not currently suppressed
     (scene still settling, camera or scene moving, or frame too dark to judge)
```

A usable face must additionally clear a luma and local-contrast floor measured under its own
bounding box, with a larger size floor when lighting is poor. ML Kit reports no confidence
score, and in dim light it reports sensor noise as faces; brightness and structure under the
detection are what stand in for the missing score.

The suppression windows exist because the least trustworthy detections cluster exactly where
the scene is changing: the moment a face appears from nothing, and while the phone is being
moved. Suppression restarts candidate evidence rather than discarding the candidate, so a
real onlooker begins accumulating again as soon as conditions settle.

This milestone trigger is for validating camera, tracking, orientation, response latency,
and false-positive behavior. It is not the final owner-versus-stranger rule.

The final response to uncertain owner recognition, the behavior when the owner's face is
missing, and the presentation-attack requirement are deliberately deferred decisions.

### Owner recognition milestone

After the deferred trigger decisions are resolved:

1. Select one exact embedding model artifact and record its source, license, checksum,
   input size, output dimension, normalization, alignment, and delegate compatibility.
2. Enroll multiple owner samples under guided pose and lighting variation.
3. Align and crop only the relevant face before embedding.
4. Cache embeddings only for stable active tracks, then expire them with those tracks.
5. Compare normalized embeddings with cosine similarity.
6. Determine the similarity threshold from device-collected validation data.
7. Encrypt stored owner templates with a key protected by Android Keystore.
8. Provide explicit template deletion and re-enrollment controls.

No enrollment photograph, analyzed frame, or face crop should be persisted.

### Privacy response

- Show a full-display opaque application overlay when the trigger fires.
- Use blur only when cross-window blur is supported and currently enabled.
- Do not rely on transparency alone; the opaque treatment is the privacy fallback.
- Do not collect touches or imitate the obscured application's interface.
- Accompany the overlay with a notification and haptic signal.
- Remove the overlay only after a separate clear debounce period.
- If the target app blocks overlays, report degraded protection through the available
  notification/haptic path.

### Camera contention and failure handling

Another application may obtain higher-priority camera access. Treat this as an expected
runtime condition rather than a crash:

- Move to `DEGRADED` when the camera disconnects or cannot open.
- Notify the user that visual monitoring is unavailable.
- Observe camera availability and attempt recovery while the already-started service remains
  valid.
- Never claim protection is active while frames are unavailable.

### Permissions and declarations

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.VIBRATE" />
```

The monitoring service must declare `android:foregroundServiceType="camera"` and should not
be exported. Overlay access is granted through Android's special-access settings rather than
an ordinary runtime-permission dialog.

### Performance and evaluation

Do not assume GPU is always faster or more efficient. Benchmark CPU, GPU, NNAPI, and any
supported vendor delegate with the selected model and device set. Start with FP16 only after
confirming model support; test INT8 only if measurements justify it.

Collect these measurements locally from the first prototype:

- Face-detection and embedding time per invocation.
- End-to-end candidate-to-shield latency, including percentiles.
- Frames analyzed, skipped, and dropped.
- False shields and missed second faces in labeled test sessions.
- Battery consumption per hour while armed.
- Thermal status and throttling behavior.
- Camera disconnections and recovery time.
- Overlay success, rejection, and removal time.

Evaluate across lighting, face distance, head pose, skin tone, glasses, masks, device
orientation, motion, multiple faces, and front-camera quality. Numerical pass/fail thresholds
remain open until the team defines them.

### Build order

1. Create the Kotlin project and permission/status interface.
2. Prove foreground-camera lifecycle behavior on Android 12 through 16.
3. Prove overlay behavior, opaque fallback, and degraded-state reporting.
4. Add multi-face detection and stable tracking.
5. Add head-orientation gating and separate trigger/clear debounce windows.
6. Add adaptive analysis and performance instrumentation.
7. Measure accuracy, latency, battery, thermals, and interruption recovery.
8. Resolve the deferred trigger and presentation-attack decisions.
9. Add exact-model owner enrollment and recognition.
10. Tune only from measured results on the supported device set.

### First milestone definition

The first milestone is complete when a user can arm monitoring from a visible activity,
leave the app, receive a best-effort privacy shield after a stable additional
screen-oriented face is detected, see an accurate degraded-state warning when monitoring is
unavailable, and stop monitoring from the persistent notification. No images are persisted.

### Deferred decisions

- Final behavior when owner recognition is uncertain.
- Whether a non-owner can trigger when the owner's face is not currently detected.
- Required protection against photographs or replayed images of the owner.
- Exact supported device/OEM list.
- Numerical accuracy, latency, battery, and thermal acceptance thresholds.
- Final face-embedding model artifact and license.
- Views versus Compose for the application UI.

### Authoritative platform references

- [Foreground-service launch restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Foreground-service declarations](https://developer.android.com/develop/background-work/services/fgs/declare)
- [Application overlay behavior](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
- [Cross-window blur availability](https://developer.android.com/reference/android/view/WindowManager)
- [ML Kit face detection](https://developers.google.com/ml-kit/vision/face-detection/android)
- [ML Kit Face result API](https://developers.google.com/android/reference/com/google/mlkit/vision/face/Face)

---

## Machine Spec

```yaml
project: screen_onlooker_monitor
platform: android_native
language: kotlin
distribution: sideloaded

sdk:
  min: 31
  compile: 37
  target: 36
  target_status: provisional

goal:
  primary: detect_likely_physical_onlooker_via_front_camera
  response: best_effort_system_wide_privacy_shield

priorities:
  - accuracy
  - efficiency
  - low_latency
  - on_device_privacy

constraints:
  root: false
  adb_dependency: false
  developer_mode_dependency: false
  cloud_backend: false
  persist_camera_frames: false
  system_wide_overlay_guaranteed: false

out_of_scope:
  - capture_other_app_pixels
  - detect_capture_of_other_apps
  - accessibility_service
  - media_projection_monitoring
  - eye_gaze_estimation

application:
  ui_toolkit: deferred
  required_screens:
    - permission_rationale
    - enrollment
    - arm_stop
    - monitoring_status

service:
  type: foreground_service
  foreground_service_type: camera
  start_requirement: user_action_while_activity_visible
  persistent_notification: true
  background_or_boot_start_dependency: false

states:
  - disarmed
  - starting
  - active
  - candidate_detected
  - shield_active
  - degraded

camera_pipeline:
  api: camerax_image_analysis
  lens: front
  backpressure: STRATEGY_KEEP_ONLY_LATEST
  input: yuv_imageproxy
  executor: dedicated_off_main
  imageproxy_close_required: all_paths
  cadence: adaptive
  cadence_values: calibrate_on_supported_devices

face_detection:
  library: mlkit_face_detection
  mode: fast
  cadence: each_selected_analysis_frame
  outputs:
    - face_count
    - bounding_box
    - tracking_id
    - head_euler_angles
    - alignment_landmarks_if_available
  general_detection_confidence_available: false
  reliability_inputs:
    - temporal_consistency
    - usable_face_size
    - stable_tracking
    - valid_pose_values
    - frame_and_patch_luma_contrast

frame_quality:
  source: camerax_luma_plane
  sampling: strided_coarse_grid_in_rotated_space
  derived:
    - mean_luma
    - contrast
    - dark_fraction
    - clipped_fraction
    - frame_to_frame_motion_score
    - per_detection_patch_luma_and_contrast
  conditions: [good, low_light, too_dark, backlit]
  effects:
    low_light: [longer_trigger_debounce, larger_min_face_size]
    backlit: [longer_trigger_debounce, face_region_ae_metering]
    too_dark: [suppress_trigger, report_reduced_confidence]
  persist_frames: false

orientation_gate:
  type: head_orientation
  eye_gaze: false
  pose_bounds: calibrate
  debounce_basis: elapsed_time
  debounce_duration: calibrate
  clear_debounce_separate: true

milestone_one_trigger:
  all_of:
    - usable_face_count_greater_than_one
    - additional_face_screen_oriented
    - condition_persists_for_debounce
    - condition_persists_for_min_candidate_frames
    - trigger_not_suppressed
  suppression_windows:
    - usable_track_set_changed_recently
    - camera_or_scene_motion_detected
    - frame_too_dark
  suppression_effect: restart_candidate_evidence_cap_state_at_candidate_detected
  status: validation_rule_not_final_identity_rule

owner_recognition:
  milestone: later
  runtime: litert_tflite
  model_artifact: deferred
  task: one_to_one_owner_verification
  enrollment: multiple_guided_samples
  preprocessing:
    - align_face
    - crop_relevant_face
    - model_specific_normalization
  comparison: cosine_similarity
  threshold: derive_from_validation_data
  track_embedding_cache: expire_with_track
  uncertain_match_response: deferred
  owner_missing_behavior: deferred
  presentation_attack_requirement: deferred

storage:
  persist:
    - encrypted_owner_embeddings
  never_persist:
    - camera_frames
    - analyzed_face_crops
    - enrollment_photos
  encryption_key: android_keystore_protected
  deletion_and_reenrollment_controls: required

response:
  overlay_type: TYPE_APPLICATION_OVERLAY
  primary_visual: opaque_privacy_shield
  blur: optional_when_supported_and_enabled
  guaranteed_over_all_apps: false
  cannot_cover:
    - apps_that_suppress_overlays
    - critical_system_surfaces
    - some_keyguard_surfaces
  fallback:
    - notification
    - haptic
  collect_touches: false

camera_contention:
  expected: true
  on_unavailable:
    - enter_degraded_state
    - notify_user
    - observe_availability
    - attempt_valid_recovery

permissions:
  - CAMERA
  - FOREGROUND_SERVICE
  - FOREGROUND_SERVICE_CAMERA
  - SYSTEM_ALERT_WINDOW
  - POST_NOTIFICATIONS
  - VIBRATE

performance:
  fixed_delegate_default: none
  benchmark_candidates:
    - cpu
    - gpu
    - nnapi
    - supported_vendor_delegate
  quantization:
    initial_candidate: fp16
    int8: test_only_if_measurements_justify
  acceptance_thresholds: deferred

measurements:
  - stage_latency
  - end_to_end_alert_latency_percentiles
  - analyzed_skipped_dropped_frames
  - false_shields
  - missed_second_faces
  - battery_consumption_per_hour
  - thermal_status_and_throttling
  - camera_disconnect_and_recovery
  - overlay_success_and_failure

test_platforms:
  android_versions: [12, 13, 14, 15, 16, 17]
  device_oem_matrix: deferred
  scenarios:
    - permission_denial
    - notification_denial
    - overlay_denial_or_blocking
    - camera_contention
    - rotation
    - lock_unlock
    - low_light
    - motion
    - varied_face_distance_and_pose
    - glasses_and_masks
    - multiple_faces

build_order:
  - project_permissions_status_ui
  - foreground_camera_lifecycle_spike
  - overlay_and_degraded_state_spike
  - multi_face_detection_and_tracking
  - head_orientation_and_debounce
  - adaptive_analysis_and_measurement
  - device_evaluation
  - resolve_deferred_trigger_security_decisions
  - exact_model_owner_recognition
  - measured_tuning
```
