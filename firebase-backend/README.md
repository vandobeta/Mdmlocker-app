# DBS FamilyGuard - Firebase Enterprise Backend & Web Dashboard

This directory contains the complete Firebase backend project, including configuration files, database security rules, a server-side Cloud Function, and an interactive, modern **Enterprise Web Dashboard** for managing child or employee devices.

## Project Structure
```
firebase-backend/
├── README.md               # Setup and deployment instructions
├── firebase.json           # Firebase CLI service configuration
├── database.rules.json     # Realtime Database security rules
├── firestore.rules         # Firestore security rules (if utilized)
├── functions/              # Cloud Functions for Firebase
│   ├── package.json        # Dependencies for Node.js functions
│   └── index.js            # Node.js backend logic for commands/queries
└── public/                 # Enterprise Web Dashboard (Firebase Hosting)
    ├── css/
    │   └── style.css       # Clean, modern, material-inspired dark dashboard styling
    ├── js/
    │   └── app.js          # Core JavaScript connecting to Firebase Realtime Database
    └── index.html          # High-fidelity web management console UI
```

---

## Direct Integration Architecture
The DBS FamilyGuard APK client communicates directly with **Firebase Realtime Database** to synchronize policy states, rules, audit logs, and incoming administrator command requests:

1. **Client Registration / Sync**:
   Every mobile device generates a unique device ID (e.g. `dev-a1b2c3d4`) on first boot. It periodically synchronizes its local state (MDM policies, active screen-time schedules, device attributes) to the Firebase database path:
   `devices/{deviceId}.json`

2. **MDM System Policies**:
   Administrators use the Web Dashboard to toggle flags like `disallowScreenCapture` or `disallowUsbFileTransfer` inside the policy table. These update on Firebase and are immediately pulled by the child's device background enforcer service.

3. **Remote Commands Execution**:
   To trigger immediate actions like *Blinking Torch*, *Toggle Wifi/Bluetooth*, *Go Home*, *Go Back*, or *Request Remote Screen Share/Control*, the Web Dashboard pushes an execution payload into the `devices/{deviceId}/commands/` node. The APK client's real-time loop fetches, executes, and marks the command as `executed: true` while saving an entry in the Audit Logs!

---

## Web Dashboard Capabilities
* **Active Devices Overview**: Display registered device list with online/offline indicators and last-synced timestamp.
* **Instant Device Commands (RTDB-Driven)**:
  * 🔦 Toggle Flashlight (On/Off) or trigger rapid SOS flashlight blinking.
  * 📶 Toggle Wi-Fi and Bluetooth on the client.
  * 🏠 Dispatch global keystroke instructions: "Go Home" or "Go Back" to exit unauthorized user screens.
* **Live Remote Screen Streaming & Remote Control**:
  * Toggling **Screen Share** or **Remote Control** dynamically creates high-contrast system banner notifications on the client APK and opens a sleek live simulation console frame in the dashboard.
* **Administrative MDM Policy Editor**:
  * View, modify, or add new device-wide system restrictions.
  * Set the `permissionFlag` to lock configurations (child/employee mode) or temporarily permit local modification.
* **Screen-Time Schedule Manager**:
  * Inject new application locks or category restrictions specifying active hours and days of the week.
* **Live Real-Time Audit Logs**:
  * Continuous feed displaying logged events (`DEVICE_CONTROL`, `KID_MODIFICATION`, `POLICY_ENFORCEMENT`, `CRITICAL_EXCEPTION`) directly from the device.

---

## Local Setup & Deployment Instructions

### Prerequisites
1. Installed [Node.js](https://nodejs.org/) (v16+ recommended).
2. Install the Firebase CLI globally if you haven't already:
   ```bash
   npm install -g firebase-tools
   ```

### 1. Initialize Firebase CLI in this directory
From your terminal, navigate to this backend directory:
```bash
cd firebase-backend
```
Log into your Google account:
```bash
firebase login
```
Associate this folder with your Firebase project:
```bash
firebase use --add
```
Choose your existing Firebase project ID from the list.

### 2. Configure Database Rules
Ensure the `database.rules.json` file is pushed to your Realtime Database console to secure client configurations from external reads while permitting authenticated administrators.

### 3. Deploy Functions & Hosting Dashboard
To deploy both the Cloud Functions and the Hosting Web Dashboard in one command:
```bash
firebase deploy
```
Once completed, Firebase will provide your **live Hosting URL** where your administrative dashboard is running!

### 4. Connect the Client APK
Update the base URL in the APK client (`NetworkModule.kt`) to target your newly created Realtime Database:
```kotlin
private const val BASE_URL = "https://your-firebase-project-id.firebaseio.com/"
```
Compile, install the APK, and watch updates reflect instantly in real-time!
