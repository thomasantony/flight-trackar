# Google Play Store Release & Listing Guide — FlightTrackAR

This document contains everything needed to configure, build, verify, and publish **FlightTrackAR** (`com.thomasantony.flighttrackar`) on the Google Play Console.

---

## 1. Store Listing Copy

- **App Name (max 30 chars):** `FlightTrackAR`
- **Short Description (max 80 chars):**
  `Point your camera at the sky to identify live flights with AR & motion tracking.` *(80 chars)*
- **Category:** Travel & Local (or Maps & Navigation)
- **Tags:** Aviation, Flight Tracker, Augmented Reality, ADS-B, Plane Spotting

### Full Description (max 4000 chars)

> Point your phone at the sky and instantly see which flights are overhead.
>
> **FlightTrackAR** combines live, openly available ADS-B flight feeds with a 3D motion-prediction filter and real-time computer vision to identify airplanes right on your camera screen—day or night.
>
> **LIVE AUGMENTED REALITY SKY OVERLAY**
> - Real-time callsign, aircraft type, altitude, ground speed, climb/descent arrow, and slant distance overlaid directly on your camera view.
> - Tap any flight reticle or locked bounding box to view full telemetry: registration, ICAO24, barometric & geometric altitude, true track, turn rate, vertical rate, bearing, elevation, and squawk.
> - Pinch-to-zoom and quick lens presets (including 2x/5x telephoto on supported devices) with automatic dynamic field-of-view recalibration.
>
> **3D KALMAN FILTER MOTION PREDICTION**
> - Public ADS-B feeds can be delayed by several seconds. FlightTrackAR runs a 7-state 3D Constant Turn Rate & Velocity (CTRV) Extended Kalman Filter to smoothly predict each aircraft's true live position using ground speed, heading, vertical rate, and reported (or inferred) turn rate.
> - Toggle the Debug / Tracker HUD to inspect raw ADS-B fixes vs. EKF-compensated positions, curved trajectory arcs, and an interactive future lead-time slider.
>
> **DAY & NIGHT CAMERA AIRCRAFT DETECTION**
> - On-device computer vision analyzes the sky around predicted aircraft positions to draw a live bounding box on the visible airplane.
> - **Day Mode:** Detects dark silhouettes and sunlit fuselage glints against bright skies.
> - **Night Mode:** Tracks bright navigation/landing lights and detects blinking anti-collision strobes while rejecting stationary stars.
> - **Auto Mode:** Automatically switches between Day, Night, and Obstructed (indoor/cluttered) states.
>
> **COMPASS CALIBRATION & LOCK-ON SYNC**
> - Phone magnetometers can drift by several degrees. Use fine azimuth/pitch nudge controls or tap **Lock-On Sync** to snap compass alignment directly to a visually detected airplane.
>
> **OPEN ADS-B DATA & PRIVACY FIRST**
> - Uses open, community-driven ADS-B networks (`adsb.fi`, `adsb.lol`, and `OpenSky Network`) with automatic fallback—no API key or account required.
> - Includes an offline Demo Simulation mode for testing indoors.
> - No ads, no tracking, and no analytics. Camera frames are processed 100% on-device and are never recorded or uploaded.

---

## 2. Graphic Assets Ready for Upload

Located in `store/assets/`:

| Asset | File | Dimensions | Play Console Requirement |
| --- | --- | --- | --- |
| App Icon | `store/assets/ic_launcher_512.png` | `512 × 512 px` PNG | 512×512 PNG, max 1024 KB |
| Feature Graphic | `store/assets/feature_graphic_1024x500.png` | `1024 × 500 px` PNG | 1024×500 PNG/JPG, max 15 MB |

**Still to capture on your Pixel 9 Pro:**
- **Phone Screenshots (2–8 required, min 2):** Capture live sky AR shots (Day lock-on, Flight Details bottom sheet, Debug HUD with EKF vectors, Calibration bar) directly from your Pixel 9 Pro (`16:9` or `9:16`, min 320 px, max 3840 px).

---

## 3. Privacy Policy URL Setup

Google Play requires a publicly accessible HTTPS Privacy Policy URL for apps requesting `CAMERA` and `LOCATION`.

1. Make sure `docs/privacy-policy.html` is hosted via **GitHub Pages** (either make a small public docs repo or enable GitHub Pages on `/docs` if the repo is public), OR host `docs/privacy-policy.html` on `thomasantony.com`.
2. Verify the URL in [SettingsSheet.kt](../app/src/main/java/com/flighttrackar/ui/SettingsSheet.kt) (`https://thomasantony.github.io/flight-trackar/privacy-policy.html`) matches your live hosted page before uploading to Play Console!

---

## 4. Play Console Data Safety Questionnaire Answers

- **Does your app collect or share any of the required user data types?**
  - **Yes** — *Location (Approximate or Precise location)* is transmitted off-device solely in ephemeral HTTPS queries to third-party public ADS-B APIs (`adsb.fi`, `adsb.lol`, `opensky-network.org`) to look up nearby flights.
- **Location Data Declaration:**
  - **Collected:** Yes *(note: Play Console defines sending location off-device to any external endpoint as "collected" unless processed strictly on-device; mark **"Processed ephemerally"** = Yes!)*
  - **Shared:** No (not shared with third parties for advertising/analytics/brokers; used solely for core app functionality)
  - **Purpose:** App functionality
  - **User choice:** Required for live ADS-B mode (optional if user uses Demo Simulation)
- **Photos and Videos / Camera:**
  - **Collected:** No (camera frames are processed strictly in RAM on-device and never stored or transmitted)
- **Is all of the user data collected by your app encrypted in transit?**
  - **Yes** (HTTPS enforced via `network_security_config.xml` and `usesCleartextTraffic="false"`).
- **Do you provide a way for users to request that their data is deleted?**
  - Not applicable (no accounts or stored personal data; data is processed ephemerally).

---

## 5. App Content & Permissions Declarations

- **Target Audience:** Everyone / General Audience (not specifically targeted at children under 13).
- **Ads:** No, my app does not contain ads.
- **Content Rating (IARC):** Likely **Everyone (E)** / **PEGI 3**.
- **Background Location:** **No** (`ACCESS_BACKGROUND_LOCATION` is NOT declared; location is only accessed while the activity is in the foreground).
- **Foreground Permissions Justification (if prompted):**
  - `CAMERA`: Displays the live sky AR camera view and runs on-device visual aircraft bounding-box detection.
  - `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`: Computes observer-to-aircraft 3D azimuth, elevation, slant range, local magnetic declination, and nearby ADS-B flight search queries.

---

## 6. Release Signing & Upload Checklist

- [ ] **1. Generate Upload Keystore** (keep a secure offline backup of `.jks` and passwords):
  ```bash
  keytool -genkey -v -keystore release-upload-key.jks \
    -keyalg RSA -keysize 2048 -validity 10000 -alias upload
  ```
- [ ] **2. Configure `keystore.properties`** (copy from `keystore.properties.example`, already gitignored):
  ```properties
  storeFile=release-upload-key.jks
  storePassword=<your-store-password>
  keyAlias=upload
  keyPassword=<your-key-password>
  ```
- [ ] **3. Build & Verify Signed Release Bundle (`.aab`):**
  ```bash
  ./gradlew testDebugUnitTest lintRelease bundleRelease
  ```
  Verify output exists at:
  - `app/build/outputs/bundle/release/app-release.aab`
  - `app/build/outputs/mapping/release/mapping.txt` *(automatically bundled into the AAB for Play Console crash deobfuscation)*
- [ ] **4. Test the Minified Release Build on Your Pixel 9 Pro** (verify R8 didn't strip JSON parsing, CameraX, or Location!):
  ```bash
  ./gradlew assembleRelease
  adb install -r app/build/outputs/apk/release/app-release.apk
  ```
- [ ] **5. Play Console Setup:**
  1. Create new app in Google Play Console → enable **Play App Signing** (recommended).
  2. Upload `app-release.aab` to **Internal Testing** first, verify on your Pixel 9 Pro via Play Store test link.
  3. Complete **Closed Testing** (note: personal Play developer accounts created after Nov 2023 require 12 testers opted in for 14 continuous days before Production access).
  4. Upload `store/assets/ic_launcher_512.png`, `store/assets/feature_graphic_1024x500.png`, and at least 2 screenshots from your Pixel 9 Pro.
  5. Publish live Privacy Policy URL and submit for review!
