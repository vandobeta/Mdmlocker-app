/**
 * DBS FamilyGuard - Enterprise Administration Console JavaScript Engine
 * Connects directly to Firebase Realtime Database to monitor, command, and enforce MDM rules.
 */

// --- Firebase Configuration & Initialization ---
const DEFAULT_CONFIG = {
<<<<<<< ours
    databaseURL: "https://dbs-familyguard-default-rtdb.firebaseio.com",
    apiKey: "",
    authDomain: "",
    projectId: "dbs-familyguard",
    storageBucket: "",
    messagingSenderId: "",
    appId: ""
=======
    databaseURL: "https://dbfamilyguard.europe-west1.firebasedatabase.app",
    apiKey: "AIzaSyDPRkFj1trkSOHLClmCuhmrcj1Yoyvch4Q",
    authDomain: "dbfamilyguard.firebaseapp.com",
    projectId: "dbfamilyguard",
    storageBucket: "dbfamilyguard.firebasestorage.app",
    messagingSenderId: "833618312649",
    appId: "1:833618312649:web:24f6a58cda43f3680f3c18",
    measurementId: "G-9E1FV24V20"
>>>>>>> theirs
};

let currentConfig = { ...DEFAULT_CONFIG };
let selectedDeviceId = null;
let database = null;

// Load stored custom configuration if present
const storedConfig = localStorage.getItem("dbs_fg_fb_config");
if (storedConfig) {
    try {
        currentConfig = JSON.parse(storedConfig);
    } catch (e) {
        console.error("Failed to parse cached Firebase config:", e);
    }
}

function initializeFirebaseApp() {
    // If an app is already initialized, delete it first to support re-configuration on-the-fly
    if (firebase.apps.length > 0) {
        firebase.app().delete();
    }
    
    // Initialize
    firebase.initializeApp(currentConfig);
    database = firebase.database();
    
    updateConnectionStatus(true, `Connected to DB: ${currentConfig.databaseURL}`);
    scanForRegisteredDevices();
}

function updateConnectionStatus(isOk, text) {
    const dot = document.getElementById("db-status-dot");
    const label = document.getElementById("db-status-text");
    if (isOk) {
        dot.className = "w-2.5 h-2.5 rounded-full bg-green-500 animate-pulse";
        label.innerText = text;
    } else {
        dot.className = "w-2.5 h-2.5 rounded-full bg-red-500";
        label.innerText = text;
    }
}

// --- DOM Event Listeners & Tab Controls ---
document.addEventListener("DOMContentLoaded", () => {
    initializeFirebaseApp();
    setupTabControls();
    setupConfigModal();
    setupPolicyModal();
    setupScheduleModal();
    setupDeviceControlActions();
    setupBulkActions();
    initQuickMacros();
    
    // Periodic status polling simulator for offline state
    setInterval(updateSimulatedScreenFrames, 1500);
});

function setupTabControls() {
    const tabButtons = document.querySelectorAll(".tab-btn");
    const tabContents = document.querySelectorAll(".tab-content");
    
    tabButtons.forEach(btn => {
        btn.addEventListener("click", () => {
            const targetTab = btn.getAttribute("data-tab");
            
            tabButtons.forEach(b => b.classList.remove("active"));
            tabContents.forEach(c => {
                c.classList.add("hidden");
                c.classList.remove("block");
            });
            
            btn.classList.add("active");
            document.getElementById(`tab-${targetTab}`).classList.remove("hidden");
            document.getElementById(`tab-${targetTab}`).classList.add("block");
        });
    });
}

// --- Scan Registered Devices ---
function scanForRegisteredDevices() {
    const listContainer = document.getElementById("devices-list");
    listContainer.innerHTML = `
        <div class="p-4 text-center text-gray-500 text-xs">
            <div class="animate-spin inline-block w-4 h-4 border-2 border-red-500 border-t-transparent rounded-full mb-1"></div>
            <p>Scanning Firebase path: "/devices"...</p>
        </div>
    `;
    
    const devicesRef = database.ref("devices");
    devicesRef.off(); // Remove previous listeners
    
    devicesRef.on("value", (snapshot) => {
        const devicesData = snapshot.val();
        listContainer.innerHTML = "";
        
        if (!devicesData || Object.keys(devicesData).length === 0) {
            // Seed a simulated device if database is empty so dashboard works immediately!
            seedSimulatedDevice();
            return;
        }
        
        Object.keys(devicesData).forEach(deviceId => {
            const device = devicesData[deviceId];
            const lastUpdated = device.lastUpdated || Date.now();
            const timeAgo = formatTimeAgo(lastUpdated);
            const isOnline = (Date.now() - lastUpdated) < 120000; // Active within 2 minutes
            
            const card = document.createElement("div");
            card.className = `p-4 bg-[#232731] border border-gray-800 rounded-xl cursor-pointer hover:border-gray-600 transition-all ${selectedDeviceId === deviceId ? 'active-device-card' : ''}`;
            card.innerHTML = `
                <div class="flex justify-between items-center mb-1">
                    <span class="font-bold text-white tracking-wide text-xs truncate max-w-[130px]">${deviceId}</span>
                    <span class="px-2 py-0.5 rounded text-[9px] font-bold ${isOnline ? 'bg-green-950/50 text-green-400 border border-green-900' : 'bg-gray-800 text-gray-400 border border-gray-700'}">
                        ${isOnline ? '● ONLINE' : '○ SYNCED'}
                    </span>
                </div>
                <div class="text-[10px] text-gray-400 flex justify-between">
                    <span>Schedules: ${device.schedules ? Object.keys(device.schedules).length : 0}</span>
                    <span>${timeAgo}</span>
                </div>
            `;
            
            card.addEventListener("click", () => {
                selectDevice(deviceId, device);
            });
            
            listContainer.appendChild(card);
        });
        
        // Re-select if currently selected device was updated
        if (selectedDeviceId && devicesData[selectedDeviceId]) {
            updateActiveDevicePanels(selectedDeviceId, devicesData[selectedDeviceId]);
        } else if (!selectedDeviceId && Object.keys(devicesData).length > 0) {
            // Auto-select first device
            const firstId = Object.keys(devicesData)[0];
            selectDevice(firstId, devicesData[firstId]);
        }
    }, (error) => {
        console.error("Database read error:", error);
        updateConnectionStatus(false, "Authentication or Permission Error");
        listContainer.innerHTML = `
            <div class="p-4 bg-red-950/20 border border-red-900/50 rounded-xl text-center text-red-400 text-xs">
                <span class="material-symbols-outlined text-lg mb-1">error</span>
                <p class="font-bold">Database Error</p>
                <p class="text-[10px] mt-1">Verify database rules or configuration.</p>
            </div>
        `;
    });
}

function selectDevice(deviceId, deviceData) {
    selectedDeviceId = deviceId;
    
    // Highlight selected card
    const cards = document.querySelectorAll("#devices-list > div");
    cards.forEach(card => {
        const idSpan = card.querySelector("span");
        if (idSpan && idSpan.innerText === deviceId) {
            card.className = "p-4 bg-[#232731] border border-[#F44336] rounded-xl cursor-pointer shadow-md active-device-card";
        } else {
            card.classList.remove("active-device-card");
            card.style.borderColor = "";
        }
    });
    
    // Render side summary
    const summary = document.getElementById("active-device-summary");
    const lastUpdated = deviceData.lastUpdated || Date.now();
    const isOnline = (Date.now() - lastUpdated) < 120000;
    
    const settings = deviceData.systemSettings || {};
    const model = settings["device_model"] || "Generic Android";
    const brand = settings["device_brand"] || "Google";
    const sdk = settings["device_sdk"] || "N/A";
    
    const isDeviceAdmin = settings["is_device_admin"] === "true";
    const isDeviceOwner = settings["is_device_owner"] === "true";
    const isAccessibilityActive = settings["accessibility_active"] === "true";
    const isOverlayAllowed = settings["overlay_allowed"] === "true";
    const hasCamera = settings["camera_permission"] === "true";
    const hasLocation = settings["location_permission"] === "true";

    summary.innerHTML = `
        <p class="font-bold text-[#F44336] uppercase mb-2">Device Attributes</p>
        <div class="space-y-1.5 font-mono text-[10px] text-gray-400 mb-4 border-b border-gray-800 pb-3">
            <p><span class="text-gray-500">MODEL:</span> <span class="text-white">${brand} ${model}</span></p>
            <p><span class="text-gray-500">SDK VER:</span> <span class="text-white">Android SDK ${sdk}</span></p>
            <p><span class="text-gray-500">ID:</span> <span class="text-white">${deviceId}</span></p>
            <p><span class="text-gray-500">CONN:</span> <span class="${isOnline ? 'text-green-400 font-bold' : 'text-gray-400'}">${isOnline ? 'Active Session' : 'Offline Mirror'}</span></p>
            <p><span class="text-gray-500">LAST:</span> <span class="text-white">${new Date(lastUpdated).toLocaleTimeString()}</span></p>
            <p><span class="text-gray-500">POLICIES:</span> <span class="text-white">${deviceData.policies ? Object.keys(deviceData.policies).length : 0} active</span></p>
        </div>

        <p class="font-bold text-gray-400 uppercase mb-2 tracking-wider text-[10px]">MDM Privilege Status</p>
        <div class="space-y-1.5 font-mono text-[10px] mb-4 border-b border-gray-800 pb-3">
            <div class="flex justify-between items-center">
                <span class="text-gray-500">DEVICE OWNER:</span>
                <span class="${isDeviceOwner ? 'text-green-400 font-bold' : 'text-red-400'}">
                    ${isDeviceOwner ? '★ OWNER' : '✕ INACTIVE'}
                </span>
            </div>
            <div class="flex justify-between items-center">
                <span class="text-gray-500">DEVICE ADMIN:</span>
                <span class="${isDeviceAdmin ? 'text-green-400 font-bold' : 'text-red-400'}">
                    ${isDeviceAdmin ? '🛡️ ADMIN' : '✕ INACTIVE'}
                </span>
            </div>
            <div class="flex justify-between items-center">
                <span class="text-gray-500">ACCESSIBILITY:</span>
                <span class="${isAccessibilityActive ? 'text-green-400 font-bold' : 'text-red-400'}">
                    ${isAccessibilityActive ? '✓ RUNNING' : '✕ INACTIVE'}
                </span>
            </div>
            <div class="flex justify-between items-center">
                <span class="text-gray-500">OVERLAY (SYSTEM DRAW):</span>
                <span class="${isOverlayAllowed ? 'text-green-400 font-bold' : 'text-red-400'}">
                    ${isOverlayAllowed ? '✓ ALLOWED' : '✕ DENIED'}
                </span>
            </div>
        </div>

        <p class="font-bold text-gray-400 uppercase mb-2 tracking-wider text-[10px]">App Permissions</p>
        <div class="space-y-1.5 font-mono text-[10px]">
            <div class="flex justify-between items-center">
                <span class="text-gray-500">CAMERA PERMISSION:</span>
                <span class="${hasCamera ? 'text-green-400' : 'text-red-400'}">
                    ${hasCamera ? '✓ GRANTED' : '✕ DENIED'}
                </span>
            </div>
            <div class="flex justify-between items-center">
                <span class="text-gray-500">LOCATION PERMISSION:</span>
                <span class="${hasLocation ? 'text-green-400' : 'text-red-400'}">
                    ${hasLocation ? '✓ GRANTED' : '✕ DENIED'}
                </span>
            </div>
        </div>
    `;
    
    updateActiveDevicePanels(deviceId, deviceData);
}

// --- Render active device panels ---
function updateActiveDevicePanels(deviceId, deviceData) {
    // 1. Render Hardware Toggles
    const settings = deviceData.systemSettings || {};
    const policies = deviceData.policies || {};
    
    // Get state values
    const isWifiOn = settings["global_wifi_on"] === "1";
    document.getElementById("wifi-switch").checked = isWifiOn;
    document.getElementById("wifi-val").innerText = isWifiOn ? "ACTIVE" : "DISABLED";
    document.getElementById("wifi-val").className = `text-xs font-mono ${isWifiOn ? 'text-green-500' : 'text-gray-500'}`;
    
    const isBluetoothOn = policies["disallowBluetooth"] ? policies["disallowBluetooth"].value !== "true" : true;
    document.getElementById("bluetooth-switch").checked = isBluetoothOn;
    document.getElementById("bluetooth-val").innerText = isBluetoothOn ? "ACTIVE" : "MUTED";
    document.getElementById("bluetooth-val").className = `text-xs font-mono ${isBluetoothOn ? 'text-blue-400' : 'text-gray-500'}`;

    // Flashlight local dashboard state holds simulated values unless client reports back
    const isTorchOn = policies["flashlight_active_state"] ? policies["flashlight_active_state"].value === "true" : false;
    document.getElementById("torch-switch").checked = isTorchOn;
    document.getElementById("torch-val").innerText = isTorchOn ? "ON" : "OFF";
    document.getElementById("torch-val").className = `text-xs font-mono ${isTorchOn ? 'text-yellow-500' : 'text-gray-500'}`;

    const isBlinking = policies["flashlight_blinking_state"] ? policies["flashlight_blinking_state"].value === "true" : false;
    document.getElementById("blink-switch").checked = isBlinking;
    document.getElementById("blink-val").innerText = isBlinking ? "BLINKING" : "OFF";
    document.getElementById("blink-val").className = `text-xs font-mono ${isBlinking ? 'text-amber-500' : 'text-gray-500'}`;

    // 2. Render Screen Streaming Simulation Overlay Frame
    const isScreenShareRequest = policies["remoteScreenShareRequest"] ? policies["remoteScreenShareRequest"].value === "true" : false;
    const isScreenControlRequest = policies["remoteScreenControlRequest"] ? policies["remoteScreenControlRequest"].value === "true" : false;
    
    const inactiveOverlay = document.getElementById("viewport-inactive");
    const shareBtn = document.getElementById("btn-toggle-share");
    const controlBtn = document.getElementById("btn-toggle-control");
    
    // Configure buttons state colors
    if (isScreenShareRequest) {
        shareBtn.className = "flex-grow bg-red-600 hover:bg-red-700 text-white py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 transition-all";
        shareBtn.innerHTML = `<span class="material-symbols-outlined text-sm">cancel_presentation</span> Stop Screen Share`;
    } else {
        shareBtn.className = "flex-grow bg-[#2d313f] hover:bg-[#3d4255] text-white py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 transition-all";
        shareBtn.innerHTML = `<span class="material-symbols-outlined text-sm">screen_share</span> Request Screen Share`;
    }
    
    if (isScreenControlRequest) {
        controlBtn.className = "flex-grow bg-red-600 hover:bg-red-700 text-white py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 transition-all";
        controlBtn.innerHTML = `<span class="material-symbols-outlined text-sm">cancel_presentation</span> Stop Remote Control`;
    } else {
        controlBtn.className = "flex-grow bg-[#2d313f] hover:bg-[#3d4255] text-white py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 transition-all";
        controlBtn.innerHTML = `<span class="material-symbols-outlined text-sm">settings_remote</span> Remote Touch Control`;
    }

    if (isScreenShareRequest || isScreenControlRequest) {
        inactiveOverlay.classList.add("opacity-0", "pointer-events-none");
        document.getElementById("simulator-state-text").innerText = isScreenControlRequest ? "🔴 ACTIVE REMOTE SYSTEM CONTROL" : "🔴 REMOTE SCREEN STREAM ACTIVE";
    } else {
        inactiveOverlay.classList.remove("opacity-0", "pointer-events-none");
    }

    // 2.5 Render Live Device Location Card
    const locationContent = document.getElementById("location-card-content");
    if (locationContent) {
        const lat = settings["latitude"] || "Unknown";
        const lon = settings["longitude"] || "Unknown";
        const locationPermission = settings["location_permission"] === "true";

        if (!locationPermission) {
            locationContent.innerHTML = `
                <div class="p-3 bg-[#232731] border border-red-950/30 rounded-xl text-center space-y-1.5">
                    <span class="material-symbols-outlined text-red-500 text-lg">gpp_bad</span>
                    <p class="text-red-400 font-bold">Permission Denied</p>
                    <p class="text-gray-500 text-[9px] leading-relaxed">The target device has rejected location services or permissions are inactive.</p>
                </div>
            `;
        } else if (lat === "Unknown" || lon === "Unknown" || lat === "" || lon === "") {
            locationContent.innerHTML = `
                <div class="p-3 bg-[#232731] border border-gray-800 rounded-xl text-center space-y-1">
                    <span class="material-symbols-outlined text-amber-500 text-lg">location_searching</span>
                    <p class="text-amber-400 font-bold">Acquiring Fix...</p>
                    <p class="text-gray-500 text-[9px] leading-relaxed">Location permission is granted, but GPS hardware has not yet locked onto satellites.</p>
                </div>
            `;
        } else {
            locationContent.innerHTML = `
                <div class="space-y-2">
                    <div class="p-2.5 bg-[#232731] border border-gray-800 rounded-xl space-y-1.5">
                        <div class="flex justify-between text-[9px] text-gray-500 border-b border-gray-800 pb-1">
                            <span>LATITUDE:</span>
                            <span class="text-white font-bold">${lat}</span>
                        </div>
                        <div class="flex justify-between text-[9px] text-gray-500">
                            <span>LONGITUDE:</span>
                            <span class="text-white font-bold">${lon}</span>
                        </div>
                    </div>

                    <a href="https://www.google.com/maps/search/?api=1&query=${lat},${lon}" target="_blank" class="block border border-red-500/20 bg-gradient-to-br from-red-500/5 to-amber-500/5 hover:from-red-500/10 hover:to-amber-500/10 rounded-xl p-2.5 text-center transition-all group">
                        <div class="flex items-center justify-center gap-1 text-red-400 group-hover:text-red-300 font-bold uppercase tracking-wider text-[9px] mb-1">
                            <span class="material-symbols-outlined text-xs">map</span> View on Google Maps
                        </div>
                        <p class="text-[8px] text-gray-500 group-hover:text-gray-400 leading-relaxed">Click to open coordinates in browser satellite viewport</p>
                    </a>
                </div>
            `;
        }
    }

    // 3. Render System Policies Table
    const policiesTableBody = document.getElementById("policies-table-body");
    policiesTableBody.innerHTML = "";
    
    if (!deviceData.policies || Object.keys(deviceData.policies).length === 0) {
        policiesTableBody.innerHTML = `
            <tr>
                <td colspan="4" class="py-6 text-center text-gray-500">No policies currently active on this device.</td>
            </tr>
        `;
    } else {
        Object.keys(deviceData.policies).forEach(key => {
            const policy = deviceData.policies[key];
            const isGreyedOut = policy.permissionFlag === "GREY_OUT";
            const isBool = policy.valueType === "boolean";
            const checked = policy.value === "true";
            
            const tr = document.createElement("tr");
            tr.className = "border-b border-gray-800/40 hover:bg-gray-800/20";
            
            tr.innerHTML = `
                <td class="py-3.5 font-semibold text-white">
                    <div class="flex items-center gap-1.5">
                        <span class="material-symbols-outlined text-[15px] ${isGreyedOut ? 'text-amber-500' : 'text-gray-500'}">
                            ${isGreyedOut ? 'lock' : 'lock_open'}
                        </span>
                        <span>${key}</span>
                    </div>
                </td>
                <td class="py-3.5 font-mono">
                    ${isBool ? `
                        <label class="switch">
                            <input type="checkbox" class="policy-toggle-switch" data-key="${key}" ${checked ? 'checked' : ''}>
                            <span class="slider"></span>
                        </label>
                    ` : `
                        <input type="text" class="policy-text-input bg-[#232731] border border-gray-700 rounded px-2 py-1 text-white w-28 focus:outline-none focus:border-[#F44336]" data-key="${key}" value="${policy.value}">
                    `}
                </td>
                <td class="py-3.5">
                    <span class="px-2 py-0.5 rounded text-[10px] font-bold ${isGreyedOut ? 'bg-amber-950/40 text-amber-400 border border-amber-900' : 'bg-green-950/40 text-green-400 border border-green-900'}">
                        ${isGreyedOut ? '🔒 LOCKED ON DEVICE' : '🔓 ALLOW LOCAL EDITS'}
                    </span>
                </td>
                <td class="py-3.5 text-right">
                    <button class="text-gray-500 hover:text-red-500 p-1 delete-policy-btn transition-colors" data-key="${key}">
                        <span class="material-symbols-outlined text-sm">delete</span>
                    </button>
                </td>
            `;
            
            // Toggle Switch Event Listener
            if (isBool) {
                tr.querySelector(".policy-toggle-switch").addEventListener("change", (e) => {
                    updatePolicyValue(key, e.target.checked ? "true" : "false");
                });
            } else {
                // Blur/Enter change listener
                const input = tr.querySelector(".policy-text-input");
                input.addEventListener("keydown", (e) => {
                    if (e.key === "Enter") {
                        updatePolicyValue(key, input.value);
                        input.blur();
                    }
                });
                input.addEventListener("blur", () => {
                    updatePolicyValue(key, input.value);
                });
            }
            
            tr.querySelector(".delete-policy-btn").addEventListener("click", () => {
                deletePolicy(key);
            });
            
            policiesTableBody.appendChild(tr);
        });
    }

    // 3.5 Render Global / Secure / System Settings Tables
    renderSettingsTable("global-settings-list", deviceData.globalSettings || {}, "global");
    renderSettingsTable("secure-settings-list", deviceData.secureSettings || {}, "secure");
    renderSettingsTable("system-settings-list", deviceData.systemTable || {}, "system");

    // 4. Render Screen Time Schedules List
    const schedulesContainer = document.getElementById("schedules-list-container");
    schedulesContainer.innerHTML = "";
    
    if (!deviceData.schedules || Object.keys(deviceData.schedules).length === 0) {
        schedulesContainer.innerHTML = `
            <div class="col-span-2 text-center py-8 text-gray-500 text-xs">
                <span class="material-symbols-outlined text-4xl mb-2 text-gray-700">timer_off</span>
                <p>No application suspensions or active schedules are enforced on this device.</p>
            </div>
        `;
    } else {
        Object.keys(deviceData.schedules).forEach(key => {
            const schedule = deviceData.schedules[key];
            const activeDays = schedule.daysOfWeek || "Daily";
            const targetType = schedule.isCategory ? "Category Restriction" : "Specific Package Rule";
            
            const card = document.createElement("div");
            card.className = "bg-[#232731] border border-gray-800 rounded-xl p-4 flex flex-col justify-between gap-3 shadow-md";
            
            const formatTime = (h, m) => `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}`;
            
            card.innerHTML = `
                <div class="flex justify-between items-start">
                    <div>
                        <span class="text-[9px] font-bold tracking-wider text-gray-400 uppercase bg-[#1f212a] border border-gray-800 px-2 py-0.5 rounded">${targetType}</span>
                        <h4 class="text-sm font-black text-white mt-1.5 flex items-center gap-1.5">
                            <span class="w-2 h-2 rounded-full bg-red-500"></span> ${schedule.targetName}
                        </h4>
                    </div>
                    <button class="text-gray-500 hover:text-red-500 p-1 delete-schedule-btn transition-colors" data-id="${key}">
                        <span class="material-symbols-outlined text-sm">delete</span>
                    </button>
                </div>
                
                <div class="space-y-1 font-mono text-[11px] text-gray-400">
                    <p><span class="text-gray-500">PACKAGE:</span> <span class="text-red-400">${schedule.actionTarget}</span></p>
                    <p><span class="text-gray-500">HOURS:</span> <span class="text-white">${formatTime(schedule.startHour, schedule.startMinute)} - ${formatTime(schedule.endHour, schedule.endMinute)}</span></p>
                    <p><span class="text-gray-500">DAYS:</span> <span class="text-white">${activeDays}</span></p>
                    <p><span class="text-gray-500">ACTION:</span> <span class="text-[#F44336] font-bold">${schedule.actionType}</span></p>
                </div>

                <div class="flex justify-between items-center border-t border-gray-800 pt-2 text-[10px]">
                    <span class="text-gray-400">Status Rule:</span>
                    <div class="flex items-center gap-1.5">
                        <span class="font-bold text-xs ${schedule.isEnabled ? 'text-green-500' : 'text-gray-500'}">
                            ${schedule.isEnabled ? 'Enforced' : 'Muted'}
                        </span>
                        <label class="switch">
                            <input type="checkbox" class="schedule-toggle-switch" data-id="${key}" ${schedule.isEnabled ? 'checked' : ''}>
                            <span class="slider"></span>
                        </label>
                    </div>
                </div>
            `;
            
            card.querySelector(".schedule-toggle-switch").addEventListener("change", (e) => {
                toggleScheduleEnabled(key, e.target.checked);
            });
            
            card.querySelector(".delete-schedule-btn").addEventListener("click", () => {
                deleteSchedule(key);
            });
            
            schedulesContainer.appendChild(card);
        });
    }

    // 5. Render Audit Logs monospaced terminal console
    const terminalScreen = document.getElementById("terminal-screen");
    terminalScreen.innerHTML = "";
    
    if (!deviceData.auditLogs || Object.keys(deviceData.auditLogs).length === 0) {
        terminalScreen.innerHTML = `<div class="text-gray-500">// Device audit log buffer empty. Waiting for sync...</div>`;
    } else {
        // Sort logs descending by timestamp
        const sortedLogs = Object.keys(deviceData.auditLogs)
            .map(id => deviceData.auditLogs[id])
            .sort((a, b) => b.timestamp - a.timestamp);
            
        const filterVal = document.getElementById("log-filter").value.toUpperCase();
        
        sortedLogs.forEach(log => {
            if (filterVal && !log.eventType.toUpperCase().includes(filterVal) && !log.message.toUpperCase().includes(filterVal)) {
                return; // Filter out
            }
            
            const logLine = document.createElement("div");
            logLine.className = "flex gap-2 hover:bg-gray-900 py-0.5 px-1 rounded transition-colors";
            
            const timeStr = new Date(log.timestamp).toISOString();
            let colorClass = "text-gray-400";
            if (log.eventType === "CRITICAL_EXCEPTION") colorClass = "text-red-500 font-bold";
            if (log.eventType === "POLICY_ENFORCEMENT") colorClass = "text-green-400";
            if (log.eventType === "KID_MODIFICATION") colorClass = "text-yellow-400 font-bold";
            if (log.eventType === "DEVICE_CONTROL") colorClass = "text-blue-400";
            
            logLine.innerHTML = `
                <span class="text-gray-600 select-none">${timeStr}</span>
                <span class="${colorClass} min-w-[120px] shrink-0 font-bold">[${log.eventType}]</span>
                <span class="text-gray-300">${log.message}</span>
            `;
            
            terminalScreen.appendChild(logLine);
        });
        
        if (terminalScreen.children.length === 0) {
            terminalScreen.innerHTML = `<div class="text-gray-500">// No logs matched query: "${filterVal}"</div>`;
        }
    }
}

// --- Live Viewport Interaction Click simulation ---
const screenViewport = document.getElementById("screen-viewport");
const gestureTracker = document.getElementById("gesture-tracker");

screenViewport.addEventListener("click", (e) => {
    if (!selectedDeviceId) return;
    
    // Check if remote control is active
    database.ref(`devices/${selectedDeviceId}/policies/remoteScreenControlRequest/value`).once("value", (snap) => {
        if (snap.val() !== "true") return; // control inactive
        
        // Compute click coordinates percentage inside frame
        const rect = screenViewport.getBoundingClientRect();
        const x = Math.round(((e.clientX - rect.left) / rect.width) * 1080);
        const y = Math.round(((e.clientY - rect.top) / rect.height) * 2400);
        
        gestureTracker.innerText = `Last Gesture: Dispatch Tap (${x}, ${y})`;
        gestureTracker.className = "inline-block px-2 py-0.5 bg-gray-900 border border-green-800 rounded text-green-400 text-[9px] animate-bounce";
        
        // Post remote click gesture log command to terminal
        const cmdId = "cmd-" + Math.random().toString(36).substring(2, 10);
        database.ref(`devices/${selectedDeviceId}/commands/${cmdId}`).set({
            id: cmdId,
            commandType: "click_gesture",
            target: `${x},${y}`,
            value: "TAP",
            timestamp: Date.now(),
            executed: false
        });
        
        // Push local logs mirror simulation
        pushLocalSimulatedAuditLog("COMMAND_EXECUTION", `Dashboard dispatched remote touchscreen gesture tap event at: (${x}px, ${y}px)`);
        
        setTimeout(() => {
            gestureTracker.classList.remove("animate-bounce");
        }, 800);
    });
});

let frameTicks = 0;
function updateSimulatedScreenFrames() {
    if (!selectedDeviceId) return;
    
    // Check share requests state
    database.ref(`devices/${selectedDeviceId}/policies`).once("value", (snap) => {
        const policies = snap.val() || {};
        const isSharing = policies["remoteScreenShareRequest"] && policies["remoteScreenShareRequest"].value === "true";
        const isControlling = policies["remoteScreenControlRequest"] && policies["remoteScreenControlRequest"].value === "true";
        
        if (isSharing || isControlling) {
            frameTicks++;
            const screens = [
                "MIRRORING SYSTEM DRAWER (DRAWER_EXPANDED)...",
                "RENDER FRAME: SystemSettings -> Network Preferences",
                "RENDER FRAME: Chrome Sandbox Session active",
                "RENDER FRAME: Launcher Home Workspace panel index 0",
                "RENDER FRAME: MDM Core enforcing local restrictions"
            ];
            
            document.getElementById("simulator-state-text").innerText = screens[frameTicks % screens.length];
            document.getElementById("feed-timestamp").innerText = `SYNC: OK - TS: ${new Date().toLocaleTimeString()}`;
        }
    });
}

function renderSettingsTable(containerId, settingsMap, tableName) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.innerHTML = "";

    if (!settingsMap || Object.keys(settingsMap).length === 0) {
        container.innerHTML = `<div class="text-gray-500 italic">No ${tableName} settings uploaded yet.</div>`;
        return;
    }

    Object.keys(settingsMap).forEach(key => {
        const value = settingsMap[key] ?? "";
        const row = document.createElement("div");
        row.className = "flex flex-col gap-1 bg-[#232731] border border-gray-800 rounded-lg p-2";
        row.innerHTML = `
            <div class="flex items-center justify-between">
                <span class="text-gray-300 font-mono text-[10px] break-all">${key}</span>
                <label class="flex items-center gap-1 text-[9px] text-gray-400 cursor-pointer">
                    <input type="checkbox" class="settings-locked-checkbox" data-table="${tableName}" data-key="${key}" checked>
                    <span>Enforced</span>
                </label>
            </div>
            <input type="text" class="settings-value-input bg-[#13141b] border border-gray-800 rounded px-2 py-1 text-white text-[11px] w-full focus:outline-none focus:border-[#F44336]"
                   data-table="${tableName}" data-key="${key}" value="${value}">
        `;
        container.appendChild(row);
    });
}

function saveSettingsTables() {
    if (!selectedDeviceId) {
        alert("Select a device first.");
        return;
    }
    const tables = { global: {}, secure: {}, system: {} };
    document.querySelectorAll(".settings-value-input").forEach(input => {
        const table = input.getAttribute("data-table");
        const key = input.getAttribute("data-key");
        if (table && key && tables[table] !== undefined) {
            tables[table][key] = input.value;
        }
    });

    const updates = {};
    Object.keys(tables.global).forEach(k => { updates[`globalSettings/${k}`] = tables.global[k]; });
    Object.keys(tables.secure).forEach(k => { updates[`secureSettings/${k}`] = tables.secure[k]; });
    Object.keys(tables.system).forEach(k => { updates[`systemTable/${k}`] = tables.system[k]; });

    updates["lastUpdated"] = Date.now();
    database.ref(`devices/${selectedDeviceId}`).update(updates);
    pushLocalSimulatedAuditLog("SETTINGS_APPLY", "Dashboard pushed updated Global/Secure/System settings tables to device.");
    alert("Settings tables pushed. The client will apply them on its next sync cycle.");
}

document.getElementById("apply-settings-btn").addEventListener("click", saveSettingsTables);

// --- Action Publisher API helper functions ---
function updatePolicyValue(key, value) {
    if (!selectedDeviceId) return;
    database.ref(`devices/${selectedDeviceId}/policies/${key}/value`).set(value);
    pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Console updated policy key '${key}' to state: '${value}'`);
}

function deletePolicy(key) {
    if (!selectedDeviceId) return;
    if (confirm(`Remove EMM policy restriction '${key}' from database?`)) {
        database.ref(`devices/${selectedDeviceId}/policies/${key}`).remove();
        pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Console purged policy flag '${key}' from EMM schema.`);
    }
}

function toggleScheduleEnabled(key, enabled) {
    if (!selectedDeviceId) return;
    database.ref(`devices/${selectedDeviceId}/schedules/${key}/isEnabled`).set(enabled);
    pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Console modified schedule '${key}' active state: ${enabled}`);
}

function deleteSchedule(key) {
    if (!selectedDeviceId) return;
    if (confirm("Delete this screen suspension lock schedule permanently?")) {
        database.ref(`devices/${selectedDeviceId}/schedules/${key}`).remove();
        pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Console purged schedule config '${key}' from active storage.`);
    }
}

function dispatchRemoteCommand(commandType, target = null, value = null) {
    if (!selectedDeviceId) {
        alert("Select a connected device to command first.");
        return;
    }
    const cmdId = "cmd-" + Math.random().toString(36).substring(2, 10);
    database.ref(`devices/${selectedDeviceId}/commands/${cmdId}`).set({
        id: cmdId,
        commandType: commandType,
        target: target,
        value: value,
        timestamp: Date.now(),
        executed: false
    });
    
    pushLocalSimulatedAuditLog("COMMAND_EXECUTION", `Console dispatched execution payload: [${commandType.toUpperCase()}]`);
}

function pushLocalSimulatedAuditLog(eventType, message) {
    if (!selectedDeviceId) return;
    const logId = Date.now();
    database.ref(`devices/${selectedDeviceId}/auditLogs/${logId}`).set({
        id: logId,
        timestamp: logId,
        eventType: eventType,
        message: message
    });
}

// --- Setup hardware control inputs actions ---
function setupDeviceControlActions() {
    // Torch Toggle
    document.getElementById("torch-switch").addEventListener("change", (e) => {
        dispatchRemoteCommand(e.target.checked ? "torch_on" : "torch_off");
        updatePolicyValue("flashlight_active_state", e.target.checked ? "true" : "false");
    });
    
    // Torch SOS Blink
    document.getElementById("blink-switch").addEventListener("change", (e) => {
        dispatchRemoteCommand(e.target.checked ? "torch_blink_on" : "torch_blink_off");
        updatePolicyValue("flashlight_blinking_state", e.target.checked ? "true" : "false");
    });

    // WiFi
    document.getElementById("wifi-switch").addEventListener("change", (e) => {
        dispatchRemoteCommand(e.target.checked ? "wifi_on" : "wifi_off");
    });

    // Bluetooth
    document.getElementById("bluetooth-switch").addEventListener("change", (e) => {
        dispatchRemoteCommand(e.target.checked ? "bluetooth_on" : "bluetooth_off");
        updatePolicyValue("disallowBluetooth", e.target.checked ? "false" : "true");
    });

    // Go Home / Back
    document.getElementById("btn-go-home").addEventListener("click", () => dispatchRemoteCommand("go_home"));
    document.getElementById("btn-go-back").addEventListener("click", () => dispatchRemoteCommand("go_back"));

    // Screen Share Requests
    document.getElementById("btn-toggle-share").addEventListener("click", () => {
        if (!selectedDeviceId) return;
        database.ref(`devices/${selectedDeviceId}/policies/remoteScreenShareRequest/value`).once("value", (snap) => {
            const isCurrentlySharing = snap.val() === "true";
            updatePolicyValue("remoteScreenShareRequest", isCurrentlySharing ? "false" : "true");
        });
    });

    // Screen Control Requests
    document.getElementById("btn-toggle-control").addEventListener("click", () => {
        if (!selectedDeviceId) return;
        database.ref(`devices/${selectedDeviceId}/policies/remoteScreenControlRequest/value`).once("value", (snap) => {
            const isCurrentlyControlling = snap.val() === "true";
            updatePolicyValue("remoteScreenControlRequest", isCurrentlyControlling ? "false" : "true");
        });
    });
}

// --- Bulk operations actions setup ---
function setupBulkActions() {
    document.querySelectorAll(".bulk-cmd-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            const cmdType = btn.getAttribute("data-cmd");
            
            database.ref("devices").once("value", (snap) => {
                const devices = snap.val() || {};
                const keys = Object.keys(devices);
                if (keys.length === 0) {
                    alert("No registered active target device vectors located in DB.");
                    return;
                }
                
                if (confirm(`Broadcast BULK [${cmdType.toUpperCase()}] command packet to ALL ${keys.length} connected device nodes?`)) {
                    keys.forEach(id => {
                        const cmdId = "cmd-" + Math.random().toString(36).substring(2, 10);
                        database.ref(`devices/${id}/commands/${cmdId}`).set({
                            id: cmdId,
                            commandType: cmdType,
                            target: cmdType === "lock" ? null : "BULK SOS WARNING",
                            value: "Administrative system overrides are taking action.",
                            timestamp: Date.now(),
                            executed: false
                        });
                        
                        // Push log
                        const logId = Date.now();
                        database.ref(`devices/${id}/auditLogs/${logId}`).set({
                            id: logId,
                            timestamp: logId,
                            eventType: "COMMAND_EXECUTION",
                            message: `Broadcasting administrative BULK execution override: [${cmdType.toUpperCase()}]`
                        });
                    });
                    
                    alert("Broadcast batch logs published successfully.");
                }
            });
        });
    });
}

// --- Configure DB Modal ---
function setupConfigModal() {
    const modal = document.getElementById("config-modal");
    const openBtn = document.getElementById("config-btn");
    const closeBtn = document.getElementById("close-config-modal");
    const resetBtn = document.getElementById("config-reset");
    const form = document.getElementById("config-form");
    
    // Fill values on open
    openBtn.addEventListener("click", () => {
        document.getElementById("config-dburl").value = currentConfig.databaseURL;
        document.getElementById("config-apikey").value = currentConfig.apiKey;
        document.getElementById("config-projectid").value = currentConfig.projectId;
        document.getElementById("config-appid").value = currentConfig.appId;
        modal.classList.remove("hidden");
    });
    
    closeBtn.addEventListener("click", () => modal.classList.add("hidden"));
    
    resetBtn.addEventListener("click", () => {
        currentConfig = { ...DEFAULT_CONFIG };
        localStorage.removeItem("dbs_fg_fb_config");
        modal.classList.add("hidden");
        initializeFirebaseApp();
        alert("Reset config to DBS FamilyGuard simulator database.");
    });
    
    form.addEventListener("submit", (e) => {
        e.preventDefault();
        
        currentConfig.databaseURL = document.getElementById("config-dburl").value.trim();
        currentConfig.apiKey = document.getElementById("config-apikey").value.trim();
        currentConfig.projectId = document.getElementById("config-projectid").value.trim();
        currentConfig.appId = document.getElementById("config-appid").value.trim();
        
        localStorage.setItem("dbs_fg_fb_config", JSON.stringify(currentConfig));
        modal.classList.add("hidden");
        initializeFirebaseApp();
        alert("Config saved! Re-binding Firebase database connections.");
    });
}

// --- Add Policy Modal ---
function setupPolicyModal() {
    const modal = document.getElementById("add-policy-modal");
    const openBtn = document.getElementById("add-policy-btn");
    const closeBtn = document.getElementById("close-policy-modal");
    const form = document.getElementById("add-policy-form");
    
    openBtn.addEventListener("click", () => {
        if (!selectedDeviceId) {
            alert("Please select a device node first.");
            return;
        }
        form.reset();
        modal.classList.remove("hidden");
    });
    
    closeBtn.addEventListener("click", () => modal.classList.add("hidden"));
    
    form.addEventListener("submit", (e) => {
        e.preventDefault();
        const key = document.getElementById("policy-key").value.trim();
        const type = document.getElementById("policy-type").value;
        const flag = document.getElementById("policy-flag").value;
        const val = document.getElementById("policy-value").value.trim();
        
        database.ref(`devices/${selectedDeviceId}/policies/${key}`).set({
            key: key,
            value: val,
            valueType: type,
            permissionFlag: flag
        });
        
        modal.classList.add("hidden");
        pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Administrative dashboard injected new Policy flag: ${key} = ${val}`);
    });
}

// --- Add Schedule Modal ---
function setupScheduleModal() {
    const modal = document.getElementById("add-schedule-modal");
    const openBtn = document.getElementById("add-schedule-btn");
    const closeBtn = document.getElementById("close-schedule-modal");
    const form = document.getElementById("add-schedule-form");
    
    openBtn.addEventListener("click", () => {
        if (!selectedDeviceId) {
            alert("Please select a device node first.");
            return;
        }
        form.reset();
        modal.classList.remove("hidden");
    });
    
    closeBtn.addEventListener("click", () => modal.classList.add("hidden"));
    
    form.addEventListener("submit", (e) => {
        e.preventDefault();
        
        const targetName = document.getElementById("schedule-target").value.trim();
        const isCategory = document.querySelector('input[name="is-category"]:checked').value === "true";
        const startHour = parseInt(document.getElementById("sched-start-hour").value);
        const startMinute = parseInt(document.getElementById("sched-start-min").value);
        const endHour = parseInt(document.getElementById("sched-end-hour").value);
        const endMinute = parseInt(document.getElementById("sched-end-min").value);
        const actionType = document.getElementById("sched-action").value;
        const actionTarget = document.getElementById("sched-action-target").value.trim();
        
        // Combine active days of week
        const days = [];
        document.querySelectorAll("#sched-days-container input:checked").forEach(cb => {
            days.push(cb.value);
        });
        const daysOfWeek = days.length === 7 ? "Daily" : days.join(",");
        
        const schedId = "sched_" + Date.now();
        
        database.ref(`devices/${selectedDeviceId}/schedules/${schedId}`).set({
            id: schedId,
            targetName: targetName,
            isCategory: isCategory,
            startHour: startHour,
            startMinute: startMinute,
            endHour: endHour,
            endMinute: endMinute,
            daysOfWeek: daysOfWeek,
            isEnabled: true,
            actionType: actionType,
            actionTarget: actionTarget
        });
        
        modal.classList.add("hidden");
        pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Administrative dashboard injected new Screen Limit Schedule: ${targetName}`);
    });
}

// --- Live Logs Clear console ---
document.getElementById("clear-logs-btn").addEventListener("click", () => {
    if (!selectedDeviceId) return;
    if (confirm("Purge remote device log history permanently?")) {
        database.ref(`devices/${selectedDeviceId}/auditLogs`).remove();
    }
});

document.getElementById("log-filter").addEventListener("input", () => {
    if (selectedDeviceId) {
        database.ref(`devices/${selectedDeviceId}`).once("value", (snap) => {
            if (snap.exists()) {
                updateActiveDevicePanels(selectedDeviceId, snap.val());
            }
        });
    }
});

// --- Formatting Helpers ---
function formatTimeAgo(timestamp) {
    const diff = Date.now() - timestamp;
    if (diff < 10000) return "Just now";
    const secs = Math.floor(diff / 1000);
    if (secs < 60) return `${secs}s ago`;
    const mins = Math.floor(secs / 60);
    if (mins < 60) return `${mins}m ago`;
    const hours = Math.floor(mins / 60);
    return `${hours}h ago`;
}

// --- Seed Default Simulation Device ---
function seedSimulatedDevice() {
    const simId = "dev-simulated-01";
    const mockDbSeed = {
        deviceId: simId,
        lastUpdated: Date.now(),
        systemSettings: {
            global_wifi_on: "1",
            global_airplane_mode_on: "0",
            global_adb_enabled: "0",
            global_development_settings_enabled: "0",
            system_screen_brightness: "128",
            system_screen_off_timeout: "30000",
            secure_accessibility_enabled: "1",
            is_device_admin: "true",
            is_device_owner: "true",
            accessibility_active: "true",
            overlay_allowed: "true",
            camera_permission: "true",
            location_permission: "true",
            latitude: "51.507400",
            longitude: "-0.127800",
            device_model: "Pixel 8 Pro",
            device_brand: "Google",
            device_sdk: "34",
            write_settings_granted: "false"
        },
        globalSettings: {
            airplane_mode_on: "0",
            wifi_on: "1",
            mobile_data: "1",
            bluetooth_on: "1",
            adb_enabled: "0",
            development_settings_enabled: "0",
            stay_on_while_plugged_in: "0",
            auto_time: "1",
            auto_time_zone: "1",
            data_roaming: "0"
        },
        secureSettings: {
            install_non_market_apps: "0",
            location_mode: "3",
            skip_first_use_hints: "0",
            lock_to_app_enabled: "0"
        },
        systemTable: {
            screen_brightness: "128",
            screen_brightness_mode: "1",
            screen_off_timeout: "30000",
            haptic_feedback_enabled: "1",
            sound_effects_enabled: "1",
            accelerometer_rotation: "1",
            font_scale: "1.0",
            time_12_24: "24"
        },
        policies: {
            disallowScreenCapture: {
                key: "disallowScreenCapture",
                value: "true",
                valueType: "boolean",
                permissionFlag: "GREY_OUT"
            },
            disallowUsbFileTransfer: {
                key: "disallowUsbFileTransfer",
                value: "true",
                valueType: "boolean",
                permissionFlag: "GREY_OUT"
            },
            disallowSafeBoot: {
                key: "disallowSafeBoot",
                value: "true",
                valueType: "boolean",
                permissionFlag: "GREY_OUT"
            },
            allowSettingsModification: {
                key: "allowSettingsModification",
                value: "false",
                valueType: "boolean",
                permissionFlag: "GREY_OUT"
            },
            disallowBluetooth: {
                key: "disallowBluetooth",
                value: "false",
                valueType: "boolean",
                permissionFlag: "PERMIT_KID"
            },
            remoteScreenShareRequest: {
                key: "remoteScreenShareRequest",
                value: "false",
                valueType: "boolean",
                permissionFlag: "GREY_OUT"
            },
            remoteScreenControlRequest: {
                key: "remoteScreenControlRequest",
                value: "false",
                valueType: "boolean",
                permissionFlag: "GREY_OUT"
            }
        },
        schedules: {
            sched_default_gaming: {
                id: "sched_default_gaming",
                targetName: "Gaming Block",
                isCategory: true,
                startHour: 20,
                startMinute: 0,
                endHour: 7,
                endMinute: 0,
                daysOfWeek: "Daily",
                isEnabled: true,
                actionType: "SUSPEND",
                actionTarget: "Game"
            }
        },
        auditLogs: {
            [Date.now() - 50000]: {
                id: Date.now() - 50000,
                timestamp: Date.now() - 50000,
                eventType: "POLICY_ENFORCEMENT",
                message: "EMM core validated system integrity: Enforced locked state on 'disallowScreenCapture'"
            },
            [Date.now() - 40000]: {
                id: Date.now() - 40000,
                timestamp: Date.now() - 40000,
                eventType: "DEVICE_CONTROL",
                message: "Client successfully synced dynamic settings tables with Firebase database."
            }
        }
    };
    
    database.ref(`devices/${simId}`).set(mockDbSeed);
}

// --- Quick Macro Engine ---
let quickMacros = [];

function initQuickMacros() {
    const saved = localStorage.getItem("familyguard_quick_macros");
    if (saved) {
        try {
            quickMacros = JSON.parse(saved);
        } catch (e) {
            console.error("Failed to parse saved macros", e);
        }
    }
    
    if (!quickMacros || quickMacros.length === 0) {
        quickMacros = [
            {
                id: "macro-default-lockdown",
                name: "Lock Screen & Cut Wi-Fi",
                steps: ["lock", "wifi_off"]
            },
            {
                id: "macro-default-restore",
                name: "Unlock Screen & Wi-Fi On",
                steps: ["unlock", "wifi_on"]
            }
        ];
        saveMacrosToLocalStorage();
    }
    
    renderQuickMacros();
    setupMacroFormListeners();
}

function saveMacrosToLocalStorage() {
    localStorage.setItem("familyguard_quick_macros", JSON.stringify(quickMacros));
}

function renderQuickMacros() {
    const listContainer = document.getElementById("macros-list");
    if (!listContainer) return;
    
    listContainer.innerHTML = "";
    
    quickMacros.forEach(macro => {
        const item = document.createElement("div");
        item.className = "bg-[#232731] border border-gray-800 rounded-xl p-3 flex flex-col sm:flex-row sm:items-center justify-between gap-3";
        
        const stepsLabels = macro.steps.map(step => {
            let colorClass = "bg-gray-800 text-gray-400";
            let label = step;
            if (step === "lock") { colorClass = "bg-red-500/10 text-red-400 border border-red-500/10"; label = "LOCK"; }
            if (step === "unlock") { colorClass = "bg-green-500/10 text-green-400 border border-green-500/10"; label = "UNLOCK"; }
            if (step === "wifi_off") { colorClass = "bg-orange-500/10 text-orange-400 border border-orange-500/10"; label = "WIFI OFF"; }
            if (step === "wifi_on") { colorClass = "bg-teal-500/10 text-teal-400 border border-teal-500/10"; label = "WIFI ON"; }
            if (step === "torch_on") { colorClass = "bg-yellow-500/10 text-yellow-400 border border-yellow-500/10"; label = "TORCH ON"; }
            if (step === "torch_off") { colorClass = "bg-blue-500/10 text-blue-400 border border-blue-500/10"; label = "TORCH OFF"; }
            return `<span class="text-[8px] font-bold px-1.5 py-0.5 rounded ${colorClass}">${label}</span>`;
        }).join(" <span class='text-gray-600 text-[9px]'>→</span> ");
        
        item.innerHTML = `
            <div class="space-y-1">
                <p class="text-xs font-bold text-white">${macro.name}</p>
                <div class="flex flex-wrap items-center gap-1">
                    ${stepsLabels}
                </div>
            </div>
            <div class="flex items-center gap-2 self-end sm:self-auto">
                <button class="run-macro-btn bg-red-500 hover:bg-red-600 text-white font-bold text-[10px] px-3 py-1.5 rounded-lg flex items-center gap-1 transition-all" data-id="${macro.id}">
                    <span class="material-symbols-outlined text-xs">play_arrow</span> Run
                </button>
                <button class="delete-macro-btn text-gray-500 hover:text-red-400 p-1.5 rounded-lg transition-all" data-id="${macro.id}" title="Delete Macro">
                    <span class="material-symbols-outlined text-xs">delete</span>
                </button>
            </div>
        `;
        listContainer.appendChild(item);
    });
    
    document.querySelectorAll(".run-macro-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            const macroId = btn.getAttribute("data-id");
            runQuickMacro(macroId);
        });
    });
    
    document.querySelectorAll(".delete-macro-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            const macroId = btn.getAttribute("data-id");
            deleteQuickMacro(macroId);
        });
    });
}

function runQuickMacro(macroId) {
    if (!selectedDeviceId) {
        alert("Select a connected device to execute the macro sequence.");
        return;
    }
    
    const macro = quickMacros.find(m => m.id === macroId);
    if (!macro) return;
    
    pushLocalSimulatedAuditLog("COMMAND_EXECUTION", `Console executing sequence macro: [${macro.name.toUpperCase()}]`);
    
    macro.steps.forEach((step, index) => {
        setTimeout(() => {
            executeMacroStep(step);
        }, index * 400);
    });
}

function executeMacroStep(step) {
    switch (step) {
        case "lock":
            updatePolicyValue("parentLockActive", "true");
            dispatchRemoteCommand("lock");
            break;
        case "unlock":
            updatePolicyValue("parentLockActive", "false");
            break;
        case "wifi_off":
            dispatchRemoteCommand("wifi_off");
            break;
        case "wifi_on":
            dispatchRemoteCommand("wifi_on");
            break;
        case "torch_on":
            dispatchRemoteCommand("torch_on");
            updatePolicyValue("flashlight_active_state", "true");
            break;
        case "torch_off":
            dispatchRemoteCommand("torch_off");
            updatePolicyValue("flashlight_active_state", "false");
            break;
    }
}

function deleteQuickMacro(macroId) {
    if (confirm("Delete this macro sequence?")) {
        quickMacros = quickMacros.filter(m => m.id !== macroId);
        saveMacrosToLocalStorage();
        renderQuickMacros();
    }
}

function setupMacroFormListeners() {
    const toggleBtn = document.getElementById("btn-toggle-macro-form");
    const macroForm = document.getElementById("macro-form");
    const cancelBtn = document.getElementById("btn-cancel-macro");
    const saveBtn = document.getElementById("btn-save-macro");
    
    if (!toggleBtn || !macroForm) return;
    
    toggleBtn.addEventListener("click", () => {
        macroForm.classList.toggle("hidden");
    });
    
    cancelBtn.addEventListener("click", () => {
        macroForm.classList.add("hidden");
        clearMacroFormInputs();
    });
    
    saveBtn.addEventListener("click", () => {
        const nameInput = document.getElementById("macro-name");
        const name = nameInput.value.trim();
        if (!name) {
            alert("Please enter a name for the macro.");
            return;
        }
        
        const checkedSteps = [];
        document.querySelectorAll(".macro-step-check:checked").forEach(checkbox => {
            checkedSteps.push(checkbox.value);
        });
        
        if (checkedSteps.length === 0) {
            alert("Select at least one step command to build the macro sequence.");
            return;
        }
        
        const newMacro = {
            id: "macro-" + Math.random().toString(36).substring(2, 10),
            name: name,
            steps: checkedSteps
        };
        
        quickMacros.push(newMacro);
        saveMacrosToLocalStorage();
        renderQuickMacros();
        
        macroForm.classList.add("hidden");
        clearMacroFormInputs();
    });
}

function clearMacroFormInputs() {
    document.getElementById("macro-name").value = "";
    document.querySelectorAll(".macro-step-check").forEach(cb => {
        cb.checked = false;
    });
}
