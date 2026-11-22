# Privacy Policy for FlightTrackAR

**Effective Date:** November 22, 2026  
**Developer:** Thomas Antony  
**Application ID:** `com.thomasantony.flighttrackar`  
**Contact:** mail@thomasantony.com

---

## 1. Overview

FlightTrackAR is an augmented-reality flight-tracking application that overlays nearby aircraft telemetry onto your device's live camera preview. Your privacy is a core design priority: FlightTrackAR contains **no advertisements, no analytics SDKs, no user accounts, and no developer-operated tracking servers**.

## 2. Information Processed by the App

### Camera Feed (`CAMERA`)
- **Purpose:** Used strictly to display the live sky camera preview and run real-time, on-device computer vision to draw bounding boxes around likely visible aircraft.
- **Storage & Sharing:** Camera frames are processed transiently in device memory only. FlightTrackAR **never records, saves, or transmits** photos, videos, or camera frames off your device.

### Device Location (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`)
- **Purpose:** Used to calculate the 3D bearing, elevation, and slant distance of overhead aircraft relative to you, compute local magnetic declination, and query nearby flights within your chosen search radius.
- **Transmission to Public ADS-B Feeds:** When live flight tracking is active (i.e., not in Demo Simulation mode), your device sends HTTPS requests directly to your selected open ADS-B data provider containing your rounded latitude/longitude (4 decimal places, ~11 m precision) and search radius (or geographic bounding box for OpenSky Network):
  - `api.airplanes.live` (airplanes.live)
  - `api.adsb.lol` (adsb.lol)
  - `opensky-network.org` (OpenSky Network)
- **No Persistent Storage by Developer:** The developer does not collect, receive, log, or store your location history. Please refer to the respective open ADS-B network privacy policies regarding standard web server access logs.

### Motion & Orientation Sensors
- **Purpose:** Accelerometer, gyroscope, and magnetometer data (`TYPE_ROTATION_VECTOR`) are read strictly on-device to align aircraft overlays with where your camera is pointing. Sensor data never leaves your device.

## 3. Data Sharing & Third Parties

FlightTrackAR does not sell, rent, or share personal data with third parties for advertising, profiling, or marketing. The only external network requests made by the app are direct HTTPS API queries to the open ADS-B flight data provider selected in Settings.

## 4. Data Retention & Deletion

FlightTrackAR does not maintain user accounts or cloud databases. App settings (such as unit preferences and calibration offsets) remain locally on your device and are deleted automatically if you uninstall the app or clear app storage in Android Settings.

## 5. Children's Privacy

FlightTrackAR does not knowingly collect any personally identifiable information from anyone, including children under 13.

## 6. Changes to This Policy

We may update this Privacy Policy as features evolve. Any updates will be published at this page with an updated effective date.

## 7. Contact

If you have questions about this Privacy Policy, please contact:  
**Thomas Antony** — mail@thomasantony.com
