# Onlooker Monitor 🛡️

**Onlooker Monitor** is a native Kotlin Android application designed to protect user privacy from physical onlookers ("shoulder surfers"). Utilizing the device's front camera via CameraX and ML Kit Face Detection, the app continuously monitors for unauthorized additional faces looking toward the screen while you use sensitive applications.

---

## 🌟 Key Features

- 👁️ **Real-Time Onlooker Detection**: On-device front-camera analysis detecting when an additional screen-oriented face is present.
- 🎯 **Per-App Sensitivity Gating**: Monitors only when sensitive applications (UPI, Banking, DigiLocker, Groww, or user-selected apps) are in the foreground. Automatically turns off the camera when in unprotected apps or on the home screen.
- 🖼️ **Cropped Onlooker Photo Popup**: Option to display an on-screen overlay popup featuring an in-memory cropped image of the detected onlooker.
- 🛡️ **Dual Privacy Response Modes**:
  - **Full Black Screen**: Immediately masks the entire screen.
  - **Privacy Warning Popup**: Displays a non-intrusive warning card with an optional cropped image of the onlooker.
- 🔒 **100% On-Device & Private**: All image processing and inference run locally in memory. Zero network access (`INTERNET` permission excluded) and zero disk storage required.
- ⚡ **Adaptive Frame Pipeline**:
  - Motion blur and scene-settle suppression to avoid false triggers during phone movement.
  - Frame quality and luma sampling to ignore sensor noise in near-dark environments.
  - Debounced trigger windows with liveness verification.

---

## 🏗️ Architecture & Data Flow

```text
CameraX Front Camera (640x480)
        │
        ▼
FaceFrameAnalyzer ──► Bundled ML Kit Face Detector
        │             (Normalized bounds, head pose yaw/pitch/roll)
        │          ──► LumaGrid Y-plane sampling
        │             (Mean luma, contrast spread, motion score)
        ▼
MonitoringEngine
   ├── FaceTracker (ML ID association & IoU overlap tracking)
   ├── Usable-size, luma/contrast, & head-pose orientation gates
   ├── Primary face vs. candidate onlooker identification
   └── OnlookerPolicy (Per-candidate evidence, trigger/clear debounce)
        │
        ▼
OnlookerMonitorService (Foreground Service)
   ├── ForegroundAppWatcher (UsageStatsManager polling every 500ms)
   ├── PrivacyShieldController (TYPE_APPLICATION_OVERLAY)
   ├── MonitorStatusStore (StateFlow status stream)
   └── MonitorNotifications (Foreground status & alert notifications)
```

---

## ⚙️ User Configuration & Settings

### 1. Response Style
- **Full Black Screen**: Displays an opaque black overlay covering the entire screen. Double-tap anywhere to temporarily dismiss.
- **Privacy Warning Popup**: Displays a top overlay card warning you of the onlooker.

### 2. Show Onlooker Photo Popup
- When enabled, the app captures an in-memory frame, crops directly to the candidate onlooker's face region with padding, and renders the cropped photo directly inside the warning popup card.

### 3. Protected Apps
- Select which apps trigger active monitoring.
- **Default set**: UPI apps (Google Pay, PhonePe, Paytm, BHIM), Banking apps (HDFC, SBI, ICICI, Axis, Kotak), and biometric-protected apps.
- **Per-App Gating**: When you switch to an unselected app (or return to the home screen), the camera automatically unbinds within 1 second and transitions to `STANDBY` mode ("Camera off; this app isn't protected").

---

## 🚀 Building and Running

### Prerequisites
- **Android Studio**: Ladybug / Jellyfish or newer (JDK 17)
- **Target SDK**: 36 (Compile SDK 37, Min SDK 31)
- **Device**: Android 12+ (API 31+) device with USB debugging enabled

### Quick Start via Terminal
```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Deploy to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 On-Device Permissions Required

1. **Front Camera**: Required for on-device face analysis.
2. **Display over other apps**: Required for rendering the privacy shield overlay above other apps.
3. **Usage Access**: Recommended for per-app gating (enables automatic camera shutdown when outside protected apps).
4. **Notifications**: Required for running the foreground monitoring service and alerting.

---

## 🧪 Testing Strategy & Verification

The repository includes unit tests covering core tracking, policy debouncing, frame quality gating, and memory management:

```bash
./gradlew testDebugUnitTest
```

---

## 🔒 Security & Privacy Model

- **No Internet Access**: The app manifest omits `android.permission.INTERNET`.
- **No Disk Storage**: Frames and face crops are held in memory only for immediate UI rendering and are never written to disk or media store.
- **In-Memory Operations**: Image proxies are closed immediately after inference.

---

## 📄 License

Distributed under the Apache 2.0 License. See `LICENSE` for details.
