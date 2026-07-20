/**
 * DBS FamilyGuard - Enterprise Administration Console JavaScript Engine
 * Connects directly to Firebase Realtime Database to monitor, command, and enforce MDM rules.
 */

// --- Firebase Configuration & Initialization ---
const DEFAULT_CONFIG = {
  apiKey: "AIzaSyDPRkFj1trkSOHLClmCuhmrcj1Yoyvch4Q",
  authDomain: "dbfamilyguard.firebaseapp.com",
  projectId: "dbfamilyguard",
  storageBucket: "dbfamilyguard.firebasestorage.app",
  messagingSenderId: "833618312649",
  appId: "1:833618312649:web:24f6a58cda43f3680f3c18",
  databaseURL: "https://dbfamilyguard-default-rtdb.firebaseio.com"
};

let currentConfig = { ...DEFAULT_CONFIG };
let selectedDeviceId = null;
let database = null;
let firestore = null;
let currentSearchQuery = "";
let currentFilterState = "all"; // "all", "online", "offline"
let cachedDevicesData = {};
let currentSettingsSubTab = "global"; // "global", "secure", "system"
let currentAppFilter = "all"; // "all", "user", "system"

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
    firestore = firebase.firestore();
    
    firebase.auth().onAuthStateChanged((user) => {
        const authContainer = document.getElementById("auth-container");
        const dashboardContainer = document.getElementById("dashboard-container");
        const btnSignOut = document.getElementById("btn-sign-out");

        if (user) {
            console.log("Logged in as:", user.email);
            if (authContainer) authContainer.classList.add("hidden");
            if (dashboardContainer) {
                dashboardContainer.classList.remove("hidden");
                dashboardContainer.classList.add("flex");
                dashboardContainer.style.display = "flex";
            }
            if (btnSignOut) btnSignOut.classList.remove("hidden");
            
            scanForRegisteredDevices();
        } else {
            console.log("Not logged in");
            if (authContainer) authContainer.classList.remove("hidden");
            if (dashboardContainer) {
                dashboardContainer.classList.add("hidden");
                dashboardContainer.classList.remove("flex");
                dashboardContainer.style.display = "none";
            }
            if (btnSignOut) btnSignOut.classList.add("hidden");
        }
    });
}

// --- DOM Event Listeners & Tab Controls ---
document.addEventListener("DOMContentLoaded", () => {
    initializeFirebaseApp();
    setupTabControls();
    setupDeviceListFilters();
    setupConfigModal();
    setupPolicyModal();
    setupScheduleModal();
    setupDeviceControlActions();
    setupBulkActions();
    initQuickMacros();
    setupAppsTabFilters();
    setupSettingsSubTabs();
    setupLocationActions();
    setupTroubleshootingActions();
    setupAntiTamperActions();
    setupAuth();
    setupEnrollmentActions();
    
    // Periodic status polling simulator
    setInterval(updateSimulatedScreenFrames, 1500);
});

function setupDeviceListFilters() {
    const searchInput = document.getElementById("device-search");
    
    if (searchInput) {
        searchInput.addEventListener("input", (e) => {
            currentSearchQuery = e.target.value.toLowerCase().trim();
            renderDevicesList();
        });
    }
}

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
            const targetEl = document.getElementById(`tab-${targetTab}`);
            if (targetEl) {
                targetEl.classList.remove("hidden");
                targetEl.classList.add("block", "fade-in");
                setTimeout(() => targetEl.classList.remove("fade-in"), 300);
            }
        });
    });
}

// --- Scan Registered Devices ---
function scanForRegisteredDevices() {
    const listContainer = document.getElementById("devices-list");
    listContainer.innerHTML = `
        <div class="p-4 text-center text-gray-500 text-xs">
            <div class="animate-spin inline-block w-4 h-4 border-2 border-[#F44336] border-t-transparent rounded-full mb-1"></div>
            <p>Scanning Realtime DB: "/devices"...</p>
        </div>
    `;
    
    const devicesRef = database.ref("devices");
    devicesRef.off(); // Remove previous listeners
    
    devicesRef.on("value", (snapshot) => {
        const devicesData = snapshot.val();
        cachedDevicesData = devicesData || {};
        
        if (!devicesData || Object.keys(devicesData).length === 0) {
            // Seed a simulated device if database is empty so dashboard works immediately!
            seedSimulatedDevice();
            return;
        }
        
        renderDevicesList();
        
    }, (error) => {
        console.error("Database read error:", error);
        listContainer.innerHTML = `
            <div class="p-4 bg-red-950/20 border border-red-900/50 rounded-xl text-center text-[#F44336] text-xs">
                <span class="material-symbols-outlined text-lg mb-1">error</span>
                <p class="font-bold">Database Error</p>
                <p class="text-[10px] mt-1">Verify database rules or API configuration.</p>
            </div>
        `;
    });
}

function renderDevicesList() {
    const listContainer = document.getElementById("devices-list");
    if (!listContainer) return;
    
    listContainer.innerHTML = "";
    
    const deviceIds = Object.keys(cachedDevicesData);
    if (deviceIds.length === 0) {
        listContainer.innerHTML = `
            <div class="p-4 text-center text-gray-500 text-xs italic">
                No registered nodes found.
            </div>
        `;
        return;
    }
    
    let renderedCount = 0;
    
    deviceIds.forEach(deviceId => {
        const device = cachedDevicesData[deviceId];
        const lastUpdated = device.lastUpdated || Date.now();
        const timeAgo = formatTimeAgo(lastUpdated);
        const isOnline = (Date.now() - lastUpdated) < 120000; // Active within 2 minutes
        
        // Apply search query filter
        const settings = device.systemSettings || {};
        const deviceModel = settings["device_model"] || "";
        const deviceBrand = settings["device_brand"] || "";
        const matchesSearch = deviceId.toLowerCase().includes(currentSearchQuery) || 
                              deviceModel.toLowerCase().includes(currentSearchQuery) ||
                              deviceBrand.toLowerCase().includes(currentSearchQuery);
        
        if (!matchesSearch) {
            return;
        }
        
        renderedCount++;
        
        const card = document.createElement("div");
        const isActive = selectedDeviceId === deviceId;
        card.className = `p-4 bg-[#1a1c24] border rounded-xl cursor-pointer hover:border-gray-600 transition-all ${isActive ? 'border-[#F44336] shadow-lg' : 'border-gray-800'}`;
        card.innerHTML = `
            <div class="flex justify-between items-center mb-1">
                <span class="font-bold text-white tracking-wide text-xs truncate max-w-[130px]" title="${deviceId}">${deviceId}</span>
                <span class="px-2 py-0.5 rounded text-[9px] font-bold ${isOnline ? 'bg-green-950/40 text-green-400 border border-green-900/30' : 'bg-gray-800 text-gray-400 border border-gray-700'}">
                    ${isOnline ? '● ONLINE' : '○ SYNCED'}
                </span>
            </div>
            <div class="text-[10px] text-gray-400 flex justify-between">
                <span>${deviceBrand} ${deviceModel || 'Unknown Device'}</span>
                <span>${timeAgo}</span>
            </div>
        `;
        
        card.addEventListener("click", () => {
            selectDevice(deviceId, device);
        });
        
        listContainer.appendChild(card);
    });
    
    if (renderedCount === 0) {
        listContainer.innerHTML = `
            <div class="p-4 text-center text-gray-500 text-xs italic">
                No matching devices found.
            </div>
        `;
        return;
    }
    
    // Auto-select the first device if none selected
    if (!selectedDeviceId && deviceIds.length > 0) {
        selectDevice(deviceIds[0], cachedDevicesData[deviceIds[0]]);
    } else if (selectedDeviceId && cachedDevicesData[selectedDeviceId]) {
        updateActiveDevicePanels(selectedDeviceId, cachedDevicesData[selectedDeviceId]);
    }
}

function selectDevice(deviceId, deviceData) {
    selectedDeviceId = deviceId;
    
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
        <p class="font-bold text-[#F44336] uppercase mb-2 text-[10px] tracking-wider">Device Attributes</p>
        <div class="space-y-1.5 font-mono text-[10px] text-gray-400 mb-4 border-b border-gray-800 pb-3">
            <p><span class="text-gray-500">MODEL:</span> <span class="text-white">${brand} ${model}</span></p>
            <p><span class="text-gray-500">SDK VER:</span> <span class="text-white">Android SDK ${sdk}</span></p>
            <p><span class="text-gray-500">ID:</span> <span class="text-white text-[8px]">${deviceId}</span></p>
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
                <span class="text-gray-500">OVERLAY:</span>
                <span class="${isOverlayAllowed ? 'text-green-400 font-bold' : 'text-red-400'}">
                    ${isOverlayAllowed ? '✓ ALLOWED' : '✕ DENIED'}
                </span>
            </div>
        </div>

        <p class="font-bold text-gray-400 uppercase mb-2 tracking-wider text-[10px]">App Permissions</p>
        <div class="space-y-1.5 font-mono text-[10px]">
            <div class="flex justify-between items-center">
                <span class="text-gray-500">CAMERA PERM:</span>
                <span class="${hasCamera ? 'text-green-400 font-bold' : 'text-red-400'}">
                    ${hasCamera ? '✓ GRANTED' : '✕ DENIED'}
                </span>
            </div>
            <div class="flex justify-between items-center">
                <span class="text-gray-500">LOCATION PERM:</span>
                <span class="${hasLocation ? 'text-green-400 font-bold' : 'text-red-400'}">
                    ${hasLocation ? '✓ GRANTED' : '✕ DENIED'}
                </span>
            </div>
        </div>
    `;
    
    // Highlight selected card and trigger updates
    const cards = document.querySelectorAll("#devices-list > div");
    cards.forEach(card => {
        const idSpan = card.querySelector("span");
        if (idSpan && idSpan.innerText === deviceId) {
            card.className = "p-4 bg-[#1a1c24] border border-[#F44336] rounded-xl cursor-pointer shadow-lg";
        } else {
            card.className = "p-4 bg-[#1a1c24] border border-gray-800 rounded-xl cursor-pointer hover:border-gray-600 transition-all";
        }
    });

    updateActiveDevicePanels(deviceId, deviceData);
}

// --- Render Active Device Panels ---
function updateActiveDevicePanels(deviceId, deviceData) {
    const settings = deviceData.systemSettings || {};
    const policies = deviceData.policies || {};
    
    // --- 1. WiFi & Basic states updates ---
    const isWifiOn = settings["global_wifi_on"] === "1";
    document.getElementById("wifi-switch").checked = isWifiOn;
    document.getElementById("wifi-val").innerText = isWifiOn ? "ACTIVE" : "DISABLED";
    document.getElementById("wifi-val").className = `text-xs font-mono ${isWifiOn ? 'text-green-500' : 'text-gray-500'}`;
    
    // --- 2. Master Flashlight state updates ---
    const isTorchOn = policies["flashlight_active_state"] ? policies["flashlight_active_state"].value === "true" : false;
    document.getElementById("torch-switch").checked = isTorchOn;
    document.getElementById("torch-val").innerText = isTorchOn ? "ON" : "OFF";
    document.getElementById("torch-val").className = `text-xs font-mono ${isTorchOn ? 'text-yellow-500' : 'text-gray-500'}`;

    const intensityLevel = policies["flashlight_intensity_level"] ? parseInt(policies["flashlight_intensity_level"].value) : 5;
    document.getElementById("torch-intensity-slider").value = intensityLevel;
    document.getElementById("torch-intensity-val").innerText = `Level ${intensityLevel} (${intensityLevel <= 3 ? 'Low' : intensityLevel <= 7 ? 'Medium' : 'High'})`;

    const flashlightMode = policies["flashlight_mode"] ? policies["flashlight_mode"].value : "solid";
    document.querySelectorAll(".torch-mode-btn").forEach(btn => {
        const mode = btn.getAttribute("data-mode");
        if (mode === flashlightMode) {
            btn.classList.add("active");
        } else {
            btn.classList.remove("active");
        }
    });

    // --- 3. Remote Screen Stream Simulation overlay updates ---
    const isScreenShareRequest = policies["remoteScreenShareRequest"] ? policies["remoteScreenShareRequest"].value === "true" : false;
    const isScreenControlRequest = policies["remoteScreenControlRequest"] ? policies["remoteScreenControlRequest"].value === "true" : false;
    
    const inactiveOverlay = document.getElementById("viewport-inactive");
    const shareBtn = document.getElementById("btn-toggle-share");
    const controlBtn = document.getElementById("btn-toggle-control");
    
    if (isScreenShareRequest) {
        shareBtn.className = "flex-grow bg-[#F44336] hover:bg-red-700 text-white py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 transition-all";
        shareBtn.innerHTML = `<span class="material-symbols-outlined text-sm">cancel_presentation</span> Stop Stream`;
    } else {
        shareBtn.className = "flex-grow bg-[#2d313f] hover:bg-[#3d4255] text-white py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 transition-all";
        shareBtn.innerHTML = `<span class="material-symbols-outlined text-sm">screen_share</span> Screen Share`;
    }
    
    if (isScreenControlRequest) {
        controlBtn.className = "flex-grow bg-[#F44336] hover:bg-red-700 text-white py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 transition-all";
        controlBtn.innerHTML = `<span class="material-symbols-outlined text-sm">cancel_presentation</span> Stop Remote`;
    } else {
        controlBtn.className = "flex-grow bg-[#2d313f] hover:bg-[#3d4255] text-white py-2.5 rounded-xl text-xs font-semibold flex items-center justify-center gap-1.5 transition-all";
        controlBtn.innerHTML = `<span class="material-symbols-outlined text-sm">settings_remote</span> Remote Control`;
    }

    if (isScreenShareRequest || isScreenControlRequest) {
        inactiveOverlay.classList.add("hidden");
    } else {
        inactiveOverlay.classList.remove("hidden");
    }

    // --- 4. Policies Table rendering ---
    renderPoliciesTable(deviceData);

    // --- 5. Screen Time rules render ---
    renderSchedulesList(deviceData);

    // --- 6. Installed Applications List render & Visualizer Mockup ---
    renderInstalledAppsList(deviceData);

    // --- 7. Settings Tables render ---
    renderSettingsTable(deviceData);

    // --- 8. Location coordinates sync and map update ---
    renderLocationTelemetry(deviceData);

    // --- 9. Audit Logs render ---
    renderAuditLogs(deviceData);
}

// --- render helper functions ---
function renderPoliciesTable(deviceData) {
    const tableBody = document.getElementById("policies-table-body");
    if (!tableBody) return;
    tableBody.innerHTML = "";
    
    const policies = deviceData.policies || {};
    if (Object.keys(policies).length === 0) {
        tableBody.innerHTML = `
            <tr>
                <td colspan="4" class="py-6 text-center text-gray-500 font-sans">No policies currently active on this device.</td>
            </tr>
        `;
        return;
    }

    Object.keys(policies).forEach(key => {
        const policy = policies[key];
        const isGreyedOut = policy.permissionFlag === "GREY_OUT";
        const isBool = policy.valueType === "boolean";
        const checked = policy.value === "true";
        
        // Skip flashlights / remote flags from settings policies table to keep view tidy
        if (["flashlight_active_state", "flashlight_intensity_level", "flashlight_mode", "flashlight_blinking_state", "remoteScreenShareRequest", "remoteScreenControlRequest", "customVpnPackage", "customVpnConfig"].includes(key)) {
            return;
        }

        const tr = document.createElement("tr");
        tr.className = "border-b border-gray-800/40 hover:bg-gray-800/20";
        tr.innerHTML = `
            <td class="py-3 font-semibold text-white">
                <div class="flex items-center gap-2">
                    <span class="material-symbols-outlined text-[15px] ${isGreyedOut ? 'text-amber-500 animate-pulse' : 'text-gray-500'}">
                        ${isGreyedOut ? 'lock' : 'lock_open'}
                    </span>
                    <span>${key}</span>
                </div>
            </td>
            <td class="py-3 font-mono text-xs">
                ${isBool ? `
                    <label class="switch">
                        <input type="checkbox" class="policy-toggle-switch" data-key="${key}" ${checked ? 'checked' : ''}>
                        <span class="slider"></span>
                    </label>
                ` : `
                    <input type="text" class="policy-text-input bg-[#13141b] border border-gray-800 rounded px-2.5 py-1 text-white w-32 focus:outline-none focus:border-[#F44336]" data-key="${key}" value="${policy.value}">
                `}
            </td>
            <td class="py-3">
                <span class="px-2 py-0.5 rounded text-[10px] font-bold ${isGreyedOut ? 'bg-amber-950/40 text-amber-400 border border-amber-900/30' : 'bg-green-950/40 text-green-400 border border-green-900/30'}">
                    ${isGreyedOut ? '🔒 LOCKED POLICY' : '🔓 LOCAL PERMIT'}
                </span>
            </td>
            <td class="py-3 text-right">
                <button class="text-gray-500 hover:text-red-500 p-1.5 delete-policy-btn transition-colors" data-key="${key}">
                    <span class="material-symbols-outlined text-sm">delete</span>
                </button>
            </td>
        `;

        if (isBool) {
            tr.querySelector(".policy-toggle-switch").addEventListener("change", (e) => {
                updatePolicyValue(key, e.target.checked ? "true" : "false");
            });
        } else {
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

        tableBody.appendChild(tr);
    });
}

function renderSchedulesList(deviceData) {
    const wrapper = document.getElementById("schedules-list-container");
    if (!wrapper) return;
    wrapper.innerHTML = "";
    
    const schedules = deviceData.schedules || {};
    if (Object.keys(schedules).length === 0) {
        wrapper.innerHTML = `
            <div class="col-span-2 text-center py-6 text-gray-500 text-xs italic">
                No active screen limitation rules configured.
            </div>
        `;
        return;
    }

    Object.keys(schedules).forEach(key => {
        const sched = schedules[key];
        const isCat = sched.isCategory;
        const formattedStart = `${sched.startHour.toString().padStart(2, '0')}:${sched.startMinute.toString().padStart(2, '0')}`;
        const formattedEnd = `${sched.endHour.toString().padStart(2, '0')}:${sched.endMinute.toString().padStart(2, '0')}`;

        const card = document.createElement("div");
        card.className = "bg-[#232731] border border-gray-800 rounded-xl p-4 flex flex-col justify-between gap-3 shadow";
        card.innerHTML = `
            <div class="flex justify-between items-start">
                <div>
                    <span class="text-[8px] font-bold tracking-wider text-red-400 bg-red-950/40 border border-red-900/30 px-1.5 py-0.5 rounded uppercase">
                        ${isCat ? 'Category Restrict' : 'App Package Restrict'}
                    </span>
                    <h4 class="text-xs font-black text-white mt-1 flex items-center gap-1.5">${sched.targetName}</h4>
                </div>
                <button class="text-gray-500 hover:text-red-500 delete-schedule-btn transition-colors" data-id="${key}">
                    <span class="material-symbols-outlined text-sm">delete</span>
                </button>
            </div>
            <div class="space-y-1 font-mono text-[10px] text-gray-400">
                <p><span class="text-gray-500">TARGET:</span> <span class="text-white">${sched.actionTarget}</span></p>
                <p><span class="text-gray-500">LIMITS:</span> <span class="text-white">${formattedStart} - ${formattedEnd}</span></p>
                <p><span class="text-gray-500">DAYS:</span> <span class="text-white">${sched.daysOfWeek}</span></p>
                <p><span class="text-gray-500">ACTION:</span> <span class="text-amber-500 font-bold">${sched.actionType}</span></p>
            </div>
            <div class="flex justify-between items-center border-t border-gray-800/60 pt-2 text-[10px]">
                <span class="text-gray-500">Active Rule Enforced:</span>
                <label class="switch">
                    <input type="checkbox" class="schedule-toggle-switch" data-id="${key}" ${sched.isEnabled ? 'checked' : ''}>
                    <span class="slider"></span>
                </label>
            </div>
        `;

        card.querySelector(".schedule-toggle-switch").addEventListener("change", (e) => {
            toggleScheduleEnabled(key, e.target.checked);
        });

        card.querySelector(".delete-schedule-btn").addEventListener("click", () => {
            deleteSchedule(key);
        });

        wrapper.appendChild(card);
    });
}

function renderInstalledAppsList(deviceData) {
    const listContainer = document.getElementById("apps-list-container");
    if (!listContainer) return;
    listContainer.innerHTML = "";

    const apps = deviceData.installedApps || {};
    const appKeys = Object.keys(apps);
    
    // Check if phone lock is globally active
    const policies = deviceData.policies || {};
    const isLocked = (policies["parentLockActive"] && policies["parentLockActive"].value === "true") ||
                     (policies["lostModeActive"] && policies["lostModeActive"].value === "true");

    const lockOverlay = document.getElementById("mock-lockscreen-overlay");
    const homescreen = document.getElementById("mock-homescreen-container");
    const mockModelLabel = document.getElementById("mock-phone-model-label");
    const mockBatteryIcon = document.getElementById("mock-device-battery-icon");
    const mockBatteryHealth = document.getElementById("visualizer-battery-health");

    // Update Battery visualizer status
    const batteryPercent = parseInt(deviceData.systemSettings ? (deviceData.systemSettings["battery_percent"] || "95") : "95");
    if (mockBatteryIcon) {
        if (batteryPercent > 80) mockBatteryIcon.innerText = "battery_full";
        else if (batteryPercent > 30) mockBatteryIcon.innerText = "battery_4_bar";
        else mockBatteryIcon.innerText = "battery_alert";
    }
    if (mockBatteryHealth) {
        mockBatteryHealth.innerText = `${batteryPercent}% / Good`;
    }

    if (isLocked) {
        if (lockOverlay) lockOverlay.classList.remove("hidden");
        if (homescreen) homescreen.classList.add("opacity-10", "pointer-events-none");
        
        const isLostMode = policies["lostModeActive"] && policies["lostModeActive"].value === "true";
        document.getElementById("mock-lock-status-title").innerText = isLostMode ? "LOST MODE TRIGGERED" : "EMM COMPLIANCE LOCK";
        document.getElementById("mock-lock-status-desc").innerText = isLostMode ? 
            (policies["lostModeMessage"] ? policies["lostModeMessage"].value : "Please return this corporate device immediately.") : 
            "Administrative remote enforcer has locked this Android terminal workspace.";
    } else {
        if (lockOverlay) lockOverlay.classList.add("hidden");
        if (homescreen) homescreen.classList.remove("opacity-10", "pointer-events-none");
    }

    if (mockModelLabel && deviceData.systemSettings) {
        mockModelLabel.innerText = `${deviceData.systemSettings.device_brand || 'Google'} ${deviceData.systemSettings.device_model || 'Pixel 8'}`;
    }

    if (appKeys.length === 0) {
        listContainer.innerHTML = `
            <div class="p-6 text-center text-gray-500 text-xs">
                Select an active client terminal containing installed apps catalog listings.
            </div>
        `;
        return;
    }

    // Sort apps by descending usage minutes (as requested: ordered by usage footprint)
    const sortedAppKeys = appKeys.sort((a, b) => (apps[b].timeUsedMinutes || 0) - (apps[a].timeUsedMinutes || 0));

    let renderedCount = 0;
    sortedAppKeys.forEach(key => {
        const app = apps[key];
        
        // Filters
        if (currentAppFilter === "user" && app.isSystemApp) return;
        if (currentAppFilter === "system" && !app.isSystemApp) return;

        renderedCount++;
        const item = document.createElement("div");
        item.className = "bg-[#232731] border border-gray-800 rounded-xl p-3.5 flex items-center justify-between gap-4 hover:border-indigo-500/40 transition-all cursor-pointer";
        
        const formatMinutes = (m) => {
            if (!m || m === 0) return "Not used today";
            if (m < 60) return `${m}m used`;
            const h = Math.floor(m / 60);
            const rm = m % 60;
            return rm > 0 ? `${h}h ${rm}m used` : `${h}h used`;
        };

        const statusColors = {
            "ALLOWED": "bg-green-950/40 text-green-400 border border-green-900/30",
            "DISABLED": "bg-red-950/40 text-red-400 border border-red-900/30",
            "SUSPENDED": "bg-orange-950/40 text-orange-400 border border-orange-900/30",
            "BLACKLISTED": "bg-purple-950/40 text-purple-400 border border-purple-900/30"
        };
        const statusLabel = app.status || "ALLOWED";

        item.innerHTML = `
            <div class="flex items-center gap-3 min-w-0">
                <div class="w-9 h-9 rounded-xl bg-gray-800 flex items-center justify-center text-md select-none shrink-0">
                    ${app.isSystemApp ? '⚙️' : '📦'}
                </div>
                <div class="min-w-0">
                    <div class="flex items-center gap-2">
                        <h4 class="text-xs font-black text-white truncate max-w-[150px]">${app.appName}</h4>
                        <span class="px-1.5 py-0.5 rounded text-[8px] font-mono tracking-wider font-extrabold ${statusColors[statusLabel]}">${statusLabel}</span>
                    </div>
                    <p class="text-[9px] text-gray-400 truncate max-w-[200px] font-mono">${app.packageName}</p>
                    <p class="text-[9px] text-[#F44336] mt-0.5 font-bold flex items-center gap-1">
                        <span class="material-symbols-outlined text-[10px]">schedule</span>
                        ${formatMinutes(app.timeUsedMinutes)}
                    </p>
                </div>
            </div>
            <div class="text-right shrink-0">
                <span class="material-symbols-outlined text-gray-500 hover:text-white transition-colors text-lg">tune</span>
            </div>
        `;

        item.addEventListener("click", () => {
            openAppControlModal(key, app);
        });

        listContainer.appendChild(item);
    });

    if (renderedCount === 0) {
        listContainer.innerHTML = `
            <div class="p-6 text-center text-gray-500 text-xs italic">
                No apps matched selected package scope filters.
            </div>
        `;
    }
}

function renderSettingsTable(deviceData) {
    const tableBody = document.getElementById("settings-table-body");
    if (!tableBody) return;
    tableBody.innerHTML = "";

    const systemSettings = deviceData.systemSettings || {};
    const settingsKeys = Object.keys(systemSettings);

    if (settingsKeys.length === 0) {
        tableBody.innerHTML = `
            <tr>
                <td colspan="4" class="py-6 text-center text-gray-500 font-sans">Select an active device directory node first.</td>
            </tr>
        `;
        return;
    }

    let renderedCount = 0;
    settingsKeys.forEach(key => {
        // filter settings table categories
        const isGlobal = key.startsWith("global_");
        const isSecure = key.startsWith("secure_");
        const isSystem = key.startsWith("system_");

        if (currentSettingsSubTab === "global" && !isGlobal) return;
        if (currentSettingsSubTab === "secure" && !isSecure) return;
        if (currentSettingsSubTab === "system" && !isSystem) return;

        renderedCount++;
        const val = systemSettings[key];
        
        // Check if this setting's local edit permission is locked/greyed out
        const policies = deviceData.policies || {};
        const isLockedKey = `lock_setting_${key}`;
        const isEnforcedLocked = policies[isLockedKey] ? policies[isLockedKey].value === "true" : true; // Default locked for enterprise safety!

        const tr = document.createElement("tr");
        tr.className = "border-b border-gray-800/40 hover:bg-gray-800/20";
        tr.innerHTML = `
            <td class="py-3 font-semibold text-white tracking-wide text-xs">
                ${key.replace(/^(global_|secure_|system_)/, '')}
            </td>
            <td class="py-3 text-xs">
                <input type="text" class="settings-value-input bg-[#13141b] border border-gray-800 rounded px-2 py-1 text-white w-28 focus:outline-none focus:border-amber-500 text-xs font-mono font-bold" data-key="${key}" value="${val}">
            </td>
            <td class="py-3 font-sans">
                <span class="px-2 py-0.5 rounded text-[10px] font-bold ${isEnforcedLocked ? 'bg-amber-950/40 text-amber-400 border border-amber-900/30' : 'bg-green-950/40 text-green-400 border border-green-900/30'}">
                    ${isEnforcedLocked ? '🔒 Locked (Grey Out)' : '🔓 Allowed (Permit Edit)'}
                </span>
            </td>
            <td class="py-3 text-right font-sans">
                <button class="bg-[#232731] hover:bg-gray-700 text-gray-300 px-2 py-1 rounded text-[10px] font-bold transition-all toggle-setting-lock-btn" data-key="${key}">
                    ${isEnforcedLocked ? 'Unlock Flag' : 'Lock Flag'}
                </button>
            </td>
        `;

        // Handle value writes
        const input = tr.querySelector(".settings-value-input");
        input.addEventListener("keydown", (e) => {
            if (e.key === "Enter") {
                writeSettingsValue(key, input.value);
                input.blur();
            }
        });
        input.addEventListener("blur", () => {
            writeSettingsValue(key, input.value);
        });

        // Handle policy lock flag toggle
        tr.querySelector(".toggle-setting-lock-btn").addEventListener("click", () => {
            toggleSettingPolicyLock(key, !isEnforcedLocked);
        });

        tableBody.appendChild(tr);
    });

    if (renderedCount === 0) {
        tableBody.innerHTML = `
            <tr>
                <td colspan="4" class="py-6 text-center text-gray-500 font-sans italic">No keys matching the selected settings table segment are loaded.</td>
            </tr>
        `;
    }
}

function renderLocationTelemetry(deviceData) {
    const latSpan = document.getElementById("location-tab-lat");
    const lonSpan = document.getElementById("location-tab-lon");
    if (!latSpan || !lonSpan) return;

    const settings = deviceData.systemSettings || {};
    const lat = settings["latitude"] || "51.507400";
    const lon = settings["longitude"] || "-0.127800";

    latSpan.innerText = lat;
    lonSpan.innerText = lon;

    const gmapsLink = document.getElementById("location-tab-gmaps");
    if (gmapsLink) {
        gmapsLink.href = `https://www.google.com/maps/search/?api=1&query=${lat},${lon}`;
    }
}

function renderAuditLogs(deviceData) {
    const terminalScreen = document.getElementById("terminal-screen");
    if (!terminalScreen) return;
    terminalScreen.innerHTML = "";
    
    const logs = deviceData.auditLogs || {};
    if (Object.keys(logs).length === 0) {
        terminalScreen.innerHTML = `<div class="text-gray-500">// Device audit log buffer empty. Waiting for sync...</div>`;
        return;
    }

    // Sort descending
    const sorted = Object.keys(logs)
        .map(id => logs[id])
        .sort((a, b) => b.timestamp - a.timestamp);

    const query = document.getElementById("log-filter").value.toUpperCase();

    sorted.forEach(log => {
        if (query && !log.eventType.toUpperCase().includes(query) && !log.message.toUpperCase().includes(query)) {
            return;
        }

        const row = document.createElement("div");
        row.className = "flex gap-2 hover:bg-gray-900 py-0.5 px-1 rounded transition-colors";
        
        let colorClass = "text-gray-400";
        if (log.eventType === "CRITICAL_EXCEPTION") colorClass = "text-red-500 font-bold";
        if (log.eventType === "POLICY_ENFORCEMENT") colorClass = "text-green-400";
        if (log.eventType === "KID_MODIFICATION") colorClass = "text-yellow-400 font-bold animate-pulse";
        if (log.eventType === "DEVICE_CONTROL") colorClass = "text-blue-400";

        row.innerHTML = `
            <span class="text-gray-600 select-none">${new Date(log.timestamp).toISOString().split('T')[1].slice(0,-1)}</span>
            <span class="${colorClass} min-w-[120px] shrink-0 font-bold">[${log.eventType}]</span>
            <span class="text-gray-300">${log.message}</span>
        `;
        terminalScreen.appendChild(row);
    });

    if (terminalScreen.children.length === 0) {
        terminalScreen.innerHTML = `<div class="text-gray-500">// No audit messages match search filter "${query}"</div>`;
    }
}

// --- Dynamic modal handlers ---
let currentSelectedAppKey = null;
function openAppControlModal(key, app) {
    currentSelectedAppKey = key;
    document.getElementById("app-modal-name").innerText = app.appName;
    document.getElementById("app-modal-package").innerText = app.packageName;
    document.getElementById("app-control-modal").classList.remove("hidden");
}

document.getElementById("close-app-modal").addEventListener("click", () => {
    document.getElementById("app-control-modal").classList.add("hidden");
});

// App modification commands dispatchers
document.getElementById("btn-app-suspend").addEventListener("click", () => {
    if (!selectedDeviceId || !currentSelectedAppKey) return;
    updateAppStatusOnFirebase(currentSelectedAppKey, "SUSPENDED");
    dispatchRemoteCommand("suspend_app", currentSelectedAppKey, "SUSPENDED");
    document.getElementById("app-control-modal").classList.add("hidden");
});

document.getElementById("btn-app-disable").addEventListener("click", () => {
    if (!selectedDeviceId || !currentSelectedAppKey) return;
    updateAppStatusOnFirebase(currentSelectedAppKey, "DISABLED");
    dispatchRemoteCommand("disable_app", currentSelectedAppKey, "DISABLED");
    document.getElementById("app-control-modal").classList.add("hidden");
});

document.getElementById("btn-app-blacklist").addEventListener("click", () => {
    if (!selectedDeviceId || !currentSelectedAppKey) return;
    updateAppStatusOnFirebase(currentSelectedAppKey, "BLACKLISTED");
    dispatchRemoteCommand("blacklist_app", currentSelectedAppKey, "BLACKLISTED");
    document.getElementById("app-control-modal").classList.add("hidden");
});

document.getElementById("btn-app-uninstall").addEventListener("click", () => {
    if (!selectedDeviceId || !currentSelectedAppKey) return;
    // For uninstall, dispatch command, wait for client confirmation. App stays in database till uninstalled
    dispatchRemoteCommand("uninstall_app", currentSelectedAppKey, "UNINSTALL");
    alert("Dispatched package silent uninstall request to local Android MDM.");
    document.getElementById("app-control-modal").classList.add("hidden");
});

function updateAppStatusOnFirebase(appKey, status) {
    if (!selectedDeviceId) return;
    database.ref(`devices/${selectedDeviceId}/installedApps/${appKey}/status`).set(status);
    pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Console updated application package '${appKey}' status to ${status}`);
}

function setupAppsTabFilters() {
    const filterAll = document.getElementById("app-filter-all");
    const filterUser = document.getElementById("app-filter-user");
    const filterSystem = document.getElementById("app-filter-system");

    const updateBtns = (active) => {
        [filterAll, filterUser, filterSystem].forEach(b => {
            b.className = "bg-[#232731] hover:bg-[#2d313f] text-gray-400 text-xs px-3 py-1.5 rounded-lg font-semibold transition-all";
        });
        active.className = "bg-indigo-600 text-white text-xs px-3 py-1.5 rounded-lg font-semibold transition-all";
    };

    filterAll.addEventListener("click", () => {
        currentAppFilter = "all";
        updateBtns(filterAll);
        if (selectedDeviceId && cachedDevicesData[selectedDeviceId]) renderInstalledAppsList(cachedDevicesData[selectedDeviceId]);
    });
    filterUser.addEventListener("click", () => {
        currentAppFilter = "user";
        updateBtns(filterUser);
        if (selectedDeviceId && cachedDevicesData[selectedDeviceId]) renderInstalledAppsList(cachedDevicesData[selectedDeviceId]);
    });
    filterSystem.addEventListener("click", () => {
        currentAppFilter = "system";
        updateBtns(filterSystem);
        if (selectedDeviceId && cachedDevicesData[selectedDeviceId]) renderInstalledAppsList(cachedDevicesData[selectedDeviceId]);
    });
}

function setupSettingsSubTabs() {
    const tabGlobal = document.getElementById("settings-tab-global");
    const tabSecure = document.getElementById("settings-tab-secure");
    const tabSystem = document.getElementById("settings-tab-system");

    const updateBtns = (active) => {
        [tabGlobal, tabSecure, tabSystem].forEach(b => {
            b.className = "text-xs font-bold px-3 py-1.5 rounded-lg bg-[#232731] text-gray-400 hover:text-white transition-all";
        });
        active.className = "text-xs font-bold px-3 py-1.5 rounded-lg bg-amber-500 text-black transition-all";
    };

    tabGlobal.addEventListener("click", () => {
        currentSettingsSubTab = "global";
        updateBtns(tabGlobal);
        if (selectedDeviceId && cachedDevicesData[selectedDeviceId]) renderSettingsTable(cachedDevicesData[selectedDeviceId]);
    });
    tabSecure.addEventListener("click", () => {
        currentSettingsSubTab = "secure";
        updateBtns(tabSecure);
        if (selectedDeviceId && cachedDevicesData[selectedDeviceId]) renderSettingsTable(cachedDevicesData[selectedDeviceId]);
    });
    tabSystem.addEventListener("click", () => {
        currentSettingsSubTab = "system";
        updateBtns(tabSystem);
        if (selectedDeviceId && cachedDevicesData[selectedDeviceId]) renderSettingsTable(cachedDevicesData[selectedDeviceId]);
    });
}

function writeSettingsValue(key, val) {
    if (!selectedDeviceId) return;
    database.ref(`devices/${selectedDeviceId}/systemSettings/${key}`).set(val);
    pushLocalSimulatedAuditLog("DEVICE_CONTROL", `Console rewrote setting property '${key}' to value: '${val}'`);
}

function toggleSettingPolicyLock(key, isLocked) {
    if (!selectedDeviceId) return;
    const policyKey = `lock_setting_${key}`;
    database.ref(`devices/${selectedDeviceId}/policies/${policyKey}`).set({
        key: policyKey,
        value: isLocked ? "true" : "false",
        valueType: "boolean",
        permissionFlag: isLocked ? "GREY_OUT" : "PERMIT_KID"
    });
    pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Console toggled local policy permission locker on setting '${key}': ${isLocked ? 'LOCKED (GREY_OUT)' : 'USER CHANGEABLE'}`);
}

function setupLocationActions() {
    const spoofModal = document.getElementById("spoof-location-modal");
    const closeBtn = document.getElementById("close-spoof-modal");
    const spoofForm = document.getElementById("spoof-location-form");

    document.getElementById("btn-spoof-location").addEventListener("click", () => {
        if (!selectedDeviceId) {
            alert("Please select a device node first.");
            return;
        }
        spoofForm.reset();
        spoofModal.classList.remove("hidden");
    });

    closeBtn.addEventListener("click", () => spoofModal.classList.add("hidden"));

    spoofForm.addEventListener("submit", (e) => {
        e.preventDefault();
        const lat = document.getElementById("spoof-lat").value.trim();
        const lon = document.getElementById("spoof-lon").value.trim();

        if (selectedDeviceId) {
            database.ref(`devices/${selectedDeviceId}/systemSettings/latitude`).set(lat);
            database.ref(`devices/${selectedDeviceId}/systemSettings/longitude`).set(lon);
            pushLocalSimulatedAuditLog("DEVICE_CONTROL", `Published coordinates mock spoof override packet: (${lat}, ${lon})`);
        }
        spoofModal.classList.add("hidden");
    });

    document.getElementById("btn-refresh-location").addEventListener("click", () => {
        if (!selectedDeviceId) return;
        // Request GPS fix coordinate update
        dispatchRemoteCommand("request_gps_refresh");
        alert("Published coordinates telemetry request payload.");
    });
}

function setupTroubleshootingActions() {
    // Flashlight Master switch (original setup compat)
    const intensitySlider = document.getElementById("torch-intensity-slider");
    intensitySlider.addEventListener("input", (e) => {
        const val = e.target.value;
        document.getElementById("torch-intensity-val").innerText = `Level ${val} (${val <= 3 ? 'Low' : val <= 7 ? 'Medium' : 'High'})`;
        updatePolicyValue("flashlight_intensity_level", val.toString());
        dispatchRemoteCommand("flashlight_set_intensity", null, val.toString());
    });

    document.querySelectorAll(".torch-mode-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            const mode = btn.getAttribute("data-mode");
            document.querySelectorAll(".torch-mode-btn").forEach(b => b.classList.remove("active"));
            btn.classList.add("active");
            updatePolicyValue("flashlight_mode", mode);
            dispatchRemoteCommand("flashlight_set_mode", null, mode);
        });
    });

    // Simulated Camera Stream Overlay triggers
    const mediaModal = document.getElementById("media-stream-modal");
    const closeMedia = document.getElementById("close-media-modal");
    const camStream = document.getElementById("camera-stream-container");
    const micStream = document.getElementById("mic-stream-container");
    const mediaTitle = document.getElementById("media-modal-type-title");

    document.getElementById("btn-trigger-camera").addEventListener("click", () => {
        if (!selectedDeviceId) return;
        dispatchRemoteCommand("open_live_camera");
        mediaTitle.innerText = "🔴 SIMULATED CAMERA VIEWPORT BROADCAST";
        camStream.classList.remove("hidden");
        micStream.classList.add("hidden");
        mediaModal.classList.remove("hidden");
        pushLocalSimulatedAuditLog("DEVICE_CONTROL", "Opened live camera stream capture viewfinder.");
    });

    document.getElementById("btn-trigger-mic").addEventListener("click", () => {
        if (!selectedDeviceId) return;
        dispatchRemoteCommand("open_live_mic");
        mediaTitle.innerText = "🔊 SIMULATED MICROPHONE PCM STREAM";
        camStream.classList.add("hidden");
        micStream.classList.remove("hidden");
        mediaModal.classList.remove("hidden");
        pushLocalSimulatedAuditLog("DEVICE_CONTROL", "Opened live microphone stream capturing sound levels.");
    });

    closeMedia.addEventListener("click", () => {
        mediaModal.classList.add("hidden");
    });
}

function setupAntiTamperActions() {
    // VPN deployment
    document.getElementById("btn-deploy-vpn").addEventListener("click", () => {
        if (!selectedDeviceId) return;
        const pkg = document.getElementById("antitamper-vpn-pkg").value.trim();
        const cfg = document.getElementById("antitamper-vpn-config").value.trim();

        updatePolicyValue("customVpnPackage", pkg);
        updatePolicyValue("customVpnConfig", cfg);
        dispatchRemoteCommand("deploy_vpn_profile", pkg, cfg);
        alert("Custom VPN APK installation profile macro published securely.");
    });

    // Lost Mode / Wipes triggers
    document.getElementById("btn-trigger-lostmode").addEventListener("click", () => {
        if (!selectedDeviceId) return;
        const msg = document.getElementById("antitamper-lost-message").value.trim();
        updatePolicyValue("lostModeActive", "true");
        updatePolicyValue("lostModeMessage", msg);
        dispatchRemoteCommand("lost_mode_activate", null, msg);
        alert("Broadcasting Lost Mode beacon lockdown. High decibel sounds and banners triggered!");
    });

    document.getElementById("btn-trigger-locknow").addEventListener("click", () => {
        if (!selectedDeviceId) return;
        updatePolicyValue("parentLockActive", "true");
        dispatchRemoteCommand("lock");
        alert("Dispatched immediate Compliance Lock command.");
    });

    // SIM swap and theft lockout inputs
    document.getElementById("antitamper-sim-protect").addEventListener("change", (e) => {
        updatePolicyValue("lockOnSimSwap", e.target.checked ? "true" : "false");
    });

    document.getElementById("antitamper-failed-attempts").addEventListener("change", (e) => {
        updatePolicyValue("wipeFailedAttemptsThreshold", e.target.value);
    });
}

// --- Setup Database Configuration Overlay ---
function setupConfigModal() {
    const modal = document.getElementById("config-modal");
    const openBtn = document.getElementById("btn-open-config");
    const closeBtn = document.getElementById("close-config-modal");
    const resetBtn = document.getElementById("config-reset");
    const form = document.getElementById("config-form");
    
    openBtn.addEventListener("click", () => {
        document.getElementById("config-db-url").value = currentConfig.databaseURL;
        document.getElementById("config-project-id").value = currentConfig.projectId;
        document.getElementById("config-api-key").value = currentConfig.apiKey;
        modal.classList.remove("hidden");
    });
    
    closeBtn.addEventListener("click", () => modal.classList.add("hidden"));
    
    resetBtn.addEventListener("click", () => {
        currentConfig = { ...DEFAULT_CONFIG };
        localStorage.removeItem("dbs_fg_fb_config");
        modal.classList.add("hidden");
        initializeFirebaseApp();
        alert("Configuration reset to DBS simulator Realtime DB.");
    });
    
    form.addEventListener("submit", (e) => {
        e.preventDefault();
        
        currentConfig.databaseURL = document.getElementById("config-db-url").value.trim();
        currentConfig.projectId = document.getElementById("config-project-id").value.trim();
        currentConfig.apiKey = document.getElementById("config-api-key").value.trim();
        
        localStorage.setItem("dbs_fg_fb_config", JSON.stringify(currentConfig));
        modal.classList.add("hidden");
        initializeFirebaseApp();
        alert("Realtime Database established successfully.");
    });
}

// --- Setup Policy Modal ---
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
        pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Console injected Policy flag: ${key} = ${val} (${flag})`);
    });
}

// --- Setup Schedule Modal ---
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
        pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Created Screen suspension limit rule: ${targetName}`);
    });
}

// --- Action Publisher API helpers ---
function updatePolicyValue(key, value) {
    if (!selectedDeviceId) return;
    database.ref(`devices/${selectedDeviceId}/policies/${key}/value`).set(value);
    pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Console updated policy parameter '${key}' to: '${value}'`);
}

function deletePolicy(key) {
    if (!selectedDeviceId) return;
    if (confirm(`Remove MDM policy flag '${key}' from database?`)) {
        database.ref(`devices/${selectedDeviceId}/policies/${key}`).remove();
        pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Console removed policy flag '${key}'`);
    }
}

function toggleScheduleEnabled(key, enabled) {
    if (!selectedDeviceId) return;
    database.ref(`devices/${selectedDeviceId}/schedules/${key}/isEnabled`).set(enabled);
    pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Toggled schedule limit '${key}': ${enabled}`);
}

function deleteSchedule(key) {
    if (!selectedDeviceId) return;
    if (confirm("Permanently delete this screen time schedule limit?")) {
        database.ref(`devices/${selectedDeviceId}/schedules/${key}`).remove();
        pushLocalSimulatedAuditLog("POLICY_ENFORCEMENT", `Removed schedule limit '${key}'`);
    }
}

function dispatchRemoteCommand(commandType, target = null, value = null) {
    if (!selectedDeviceId) return;
    const cmdId = "cmd-" + Math.random().toString(36).substring(2, 10);
    database.ref(`devices/${selectedDeviceId}/commands/${cmdId}`).set({
        id: cmdId,
        commandType: commandType,
        target: target,
        value: value,
        timestamp: Date.now(),
        executed: false
    });
    pushLocalSimulatedAuditLog("DEVICE_CONTROL", `Console dispatched remote action payload: [${commandType.toUpperCase()}]`);
}

function pushLocalSimulatedAuditLog(eventType, message) {
    if (!selectedDeviceId) return;
    const logId = Date.now().toString();
    database.ref(`devices/${selectedDeviceId}/auditLogs/${logId}`).set({
        id: parseInt(logId),
        timestamp: Date.now(),
        eventType: eventType,
        message: message
    });
    
    // Log directly to Firestore to record admin action history permanently!
    logAdminActionToFirestore(eventType, message);
    
    // Auto-sync policies to Firestore on changes
    syncPoliciesToFirestore();
}

// --- Setup hardware control inputs actions ---
function setupDeviceControlActions() {
    // WiFi Controller
    document.getElementById("wifi-switch").addEventListener("change", (e) => {
        const checked = e.target.checked;
        writeSettingsValue("global_wifi_on", checked ? "1" : "0");
        dispatchRemoteCommand(checked ? "wifi_on" : "wifi_off");
    });

    // Master Flashlight switch
    document.getElementById("torch-switch").addEventListener("change", (e) => {
        const checked = e.target.checked;
        updatePolicyValue("flashlight_active_state", checked ? "true" : "false");
        dispatchRemoteCommand(checked ? "torch_on" : "torch_off");
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
                        const logId = Date.now().toString();
                        database.ref(`devices/${id}/auditLogs/${logId}`).set({
                            id: parseInt(logId),
                            timestamp: Date.now(),
                            eventType: "COMMAND_EXECUTION",
                            message: `Broadcasting administrative BULK execution override: [${cmdType.toUpperCase()}]`
                        });
                    });
                    
                    alert("Bulk broadcast packets dispatched successfully.");
                }
            });
        });
    });
}

// --- Clear audit logs listener ---
document.getElementById("clear-logs-btn").addEventListener("click", () => {
    if (!selectedDeviceId) return;
    if (confirm("Purge audit logging buffer history permanently?")) {
        database.ref(`devices/${selectedDeviceId}/auditLogs`).remove();
    }
});

document.getElementById("log-filter").addEventListener("input", () => {
    if (selectedDeviceId && cachedDevicesData[selectedDeviceId]) {
        renderAuditLogs(cachedDevicesData[selectedDeviceId]);
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
            latitude: "40.712800",
            longitude: "-74.006000",
            device_model: "Pixel 8 Pro",
            device_brand: "Google",
            device_sdk: "34",
            battery_percent: "98"
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
            },
            // Settings tables policies
            lock_setting_global_wifi_on: {
                key: "lock_setting_global_wifi_on",
                value: "true",
                valueType: "boolean",
                permissionFlag: "GREY_OUT"
            },
            lock_setting_global_adb_enabled: {
                key: "lock_setting_global_adb_enabled",
                value: "true",
                valueType: "boolean",
                permissionFlag: "GREY_OUT"
            },
            lock_setting_secure_accessibility_enabled: {
                key: "lock_setting_secure_accessibility_enabled",
                value: "true",
                valueType: "boolean",
                permissionFlag: "GREY_OUT"
            },
            lock_setting_system_screen_brightness: {
                key: "lock_setting_system_screen_brightness",
                value: "false",
                valueType: "boolean",
                permissionFlag: "PERMIT_KID"
            }
        },
        installedApps: {
            com_google_android_youtube: {
                packageName: "com.google.android.youtube",
                appName: "YouTube",
                isSystemApp: false,
                timeUsedMinutes: 145,
                status: "ALLOWED"
            },
            com_whatsapp: {
                packageName: "com.whatsapp",
                appName: "WhatsApp",
                isSystemApp: false,
                timeUsedMinutes: 90,
                status: "ALLOWED"
            },
            com_tencent_ig: {
                packageName: "com.tencent.ig",
                appName: "PUBG Mobile",
                isSystemApp: false,
                timeUsedMinutes: 180,
                status: "SUSPENDED"
            },
            com_android_settings: {
                packageName: "com.android.settings",
                appName: "Settings OS Panel",
                isSystemApp: true,
                timeUsedMinutes: 15,
                status: "ALLOWED"
            },
            com_android_chrome: {
                packageName: "com.android.chrome",
                appName: "Google Chrome",
                isSystemApp: true,
                timeUsedMinutes: 65,
                status: "ALLOWED"
            },
            com_android_launcher: {
                packageName: "com.android.launcher",
                appName: "Pixel Core Launcher",
                isSystemApp: true,
                timeUsedMinutes: 45,
                status: "ALLOWED"
            }
        },
        schedules: {
            sched_default_gaming: {
                id: "sched_default_gaming",
                targetName: "Gaming Limit",
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
                name: "Compliance Lockdown & Cut Wi-Fi",
                steps: ["lock", "wifi_off"]
            },
            {
                id: "macro-default-restore",
                name: "Unlock Device Work Profile",
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
        item.className = "bg-[#232731] border border-gray-800 rounded-xl p-3.5 flex flex-col sm:flex-row sm:items-center justify-between gap-3 shadow";
        
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
            <div class="flex items-center gap-2 self-end sm:self-auto shrink-0">
                <button class="run-macro-btn bg-red-500 hover:bg-red-600 text-white font-bold text-[10px] px-3 py-1.5 rounded-lg flex items-center gap-1 transition-all" data-id="${macro.id}">
                    <span class="material-symbols-outlined text-xs">play_arrow</span> Run
                </button>
                <button class="delete-macro-btn text-gray-500 hover:text-red-400 p-1.5 rounded-lg transition-all shadow" data-id="${macro.id}" title="Delete Macro">
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
            writeSettingsValue("global_wifi_on", "0");
            dispatchRemoteCommand("wifi_off");
            break;
        case "wifi_on":
            writeSettingsValue("global_wifi_on", "1");
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

let frameTicks = 0;
function updateSimulatedScreenFrames() {
    if (!selectedDeviceId) return;
    
    // Check share requests state
    database.ref(`devices/${selectedDeviceId}/policies`).once("value", (snap) => {
        const policies = snap.val() || {};
        const isSharing = policies["remoteScreenShareRequest"] && policies["remoteScreenShareRequest"].value === "true";
        const isControlling = policies["remoteScreenControlRequest"] && policies["remoteScreenControlRequest"].value === "true";
        
        const screenView = document.getElementById("viewport-active-display");
        const timeLabel = document.getElementById("mock-device-time");

        // tick device clock time
        if (timeLabel) {
            const now = new Date();
            let hours = now.getHours();
            const ampm = hours >= 12 ? 'PM' : 'AM';
            hours = hours % 12;
            hours = hours ? hours : 12; // 0 becomes 12
            const mins = now.getMinutes().toString().padStart(2, '0');
            timeLabel.innerText = `${hours.toString().padStart(2, '0')}:${mins} ${ampm}`;
        }
        
        if (isSharing || isControlling) {
            if (screenView) screenView.classList.remove("hidden");
            frameTicks++;
            const screens = [
                "MIRRORING SYSTEM DRAWER (DRAWER_EXPANDED)...",
                "RENDER FRAME: Settings -> Network Preferences",
                "RENDER FRAME: Chrome Sandbox Session Active",
                "RENDER FRAME: Launcher Workspace Panel index 0",
                "RENDER FRAME: MDM Core Enforcing Local Restrictions"
            ];
            
            document.getElementById("simulator-state-text").innerText = screens[frameTicks % screens.length];
            document.getElementById("feed-timestamp").innerText = `SYNC: OK - TS: ${new Date().toLocaleTimeString()}`;
        } else {
            if (screenView) screenView.classList.add("hidden");
        }
    });
}

let isLoginMode = true; // true = Login, false = Register / SignUp

function setupAuth() {
    const authForm = document.getElementById("auth-form");
    const authEmailInput = document.getElementById("auth-email");
    const authPasswordInput = document.getElementById("auth-password");
    const btnAuthSubmit = document.getElementById("btn-auth-submit");
    const authMessage = document.getElementById("auth-message");
    const btnToggleAuthMode = document.getElementById("btn-toggle-auth-mode");
    const btnSignOut = document.getElementById("btn-sign-out");

    if (!authForm) return;

    btnToggleAuthMode.addEventListener("click", () => {
        isLoginMode = !isLoginMode;
        if (isLoginMode) {
            btnAuthSubmit.innerHTML = `<span class="material-symbols-outlined text-xs">login</span> Authenticate Portal`;
            btnToggleAuthMode.innerText = "New Administrator? Create Portal Credential";
        } else {
            btnAuthSubmit.innerHTML = `<span class="material-symbols-outlined text-xs">person_add</span> Register Portal Admin`;
            btnToggleAuthMode.innerText = "Already Registered? Authenticate Here";
        }
    });

    authForm.addEventListener("submit", (e) => {
        e.preventDefault();
        const email = authEmailInput.value.trim();
        const password = authPasswordInput.value;

        authMessage.classList.remove("hidden", "bg-red-950/40", "text-red-400", "border-red-900/30", "bg-green-950/40", "text-green-400", "border-green-900/30");
        authMessage.classList.add("bg-gray-800/40", "text-gray-400", "border-gray-800", "block");
        authMessage.innerHTML = `
            <div class="flex items-center gap-2">
                <div class="animate-spin inline-block w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full"></div>
                <span>Processing administrative credential...</span>
            </div>
        `;

        if (isLoginMode) {
            firebase.auth().signInWithEmailAndPassword(email, password)
                .then((userCredential) => {
                    if (userCredential.user.email !== "vandosavage256@gmail.com") {
                        firebase.auth().signOut();
                        authMessage.className = "p-3.5 rounded-xl border text-[11px] font-medium leading-relaxed bg-red-950/40 text-red-400 border-red-900/30 block";
                        authMessage.innerText = "Access Denied: You are not authorized to manage this dashboard.";
                    } else {
                        authMessage.className = "p-3.5 rounded-xl border text-[11px] font-medium leading-relaxed bg-green-950/40 text-green-400 border-green-900/30 block";
                        authMessage.innerText = "Authentication successful! Redirecting to secure node...";
                        authForm.reset();
                    }
                })
                .catch((error) => {
                    authMessage.className = "p-3.5 rounded-xl border text-[11px] font-medium leading-relaxed bg-red-950/40 text-red-400 border-red-900/30 block";
                    authMessage.innerText = `Access Denied: ${error.message}`;
                });
        } else {
            if (email !== "vandosavage256@gmail.com") {
                authMessage.className = "p-3.5 rounded-xl border text-[11px] font-medium leading-relaxed bg-red-950/40 text-red-400 border-red-900/30 block";
                authMessage.innerText = "Registration Failed: This email is not authorized for administrative access.";
                return;
            }
            firebase.auth().createUserWithEmailAndPassword(email, password)
                .then((userCredential) => {
                    authMessage.className = "p-3.5 rounded-xl border text-[11px] font-medium leading-relaxed bg-green-950/40 text-green-400 border-green-900/30 block";
                    authMessage.innerText = "Administrative credential successfully provisioned! Access granted.";
                    authForm.reset();
                })
                .catch((error) => {
                    authMessage.className = "p-3.5 rounded-xl border text-[11px] font-medium leading-relaxed bg-red-950/40 text-red-400 border-red-900/30 block";
                    authMessage.innerText = `Registration Failed: ${error.message}`;
                });
        }
    });

    if (btnSignOut) {
        btnSignOut.addEventListener("click", () => {
            firebase.auth().signOut()
                .then(() => {
                    console.log("Admin signed out.");
                })
                .catch((error) => {
                    alert("Sign out failure: " + error.message);
                });
        });
    }
}

function logAdminActionToFirestore(actionType, message) {
    if (!firestore || !firebase.auth().currentUser) return;
    
    const adminEmail = firebase.auth().currentUser.email;
    firestore.collection("admin_audit_trail").add({
        timestamp: firebase.firestore.FieldValue.serverTimestamp(),
        admin: adminEmail,
        deviceId: selectedDeviceId || "GLOBAL",
        action: actionType,
        message: message
    })
    .then((docRef) => {
        console.log("Logged admin action to Firestore with ID:", docRef.id);
        
        // Let's add a log line in the monospaced terminal to show this was stored in Firestore!
        const terminalScreen = document.getElementById("terminal-screen");
        if (terminalScreen) {
            const row = document.createElement("div");
            row.className = "flex gap-2 hover:bg-gray-900 py-0.5 px-1 rounded transition-colors text-purple-400";
            row.innerHTML = `
                <span class="text-gray-600 select-none">${new Date().toLocaleTimeString()}</span>
                <span class="font-bold">[FIRESTORE_AUDIT]</span>
                <span class="text-gray-300">Logged to Cloud Firestore: ${message} (doc: ${docRef.id.substring(0, 6)}...)</span>
            `;
            terminalScreen.appendChild(row);
        }
    })
    .catch((err) => {
        console.error("Failed to log admin action to Firestore:", err);
    });
}

function syncPoliciesToFirestore() {
    if (!selectedDeviceId || !firestore) return;
    
    const deviceData = cachedDevicesData[selectedDeviceId];
    if (!deviceData) return;
    
    const docRef = firestore.collection("devices_policies").doc(selectedDeviceId);
    
    docRef.set({
        deviceId: selectedDeviceId,
        lastSynced: firebase.firestore.FieldValue.serverTimestamp(),
        policies: deviceData.policies || {},
        systemSettings: deviceData.systemSettings || {},
        installedApps: deviceData.installedApps || {}
    }, { merge: true })
    .then(() => {
        console.log("Successfully synchronized device snapshot to Firestore.");
    })
    .catch((err) => {
        console.error("Failed to synchronize to Firestore:", err);
    });
}

function setupEnrollmentActions() {
    const generateTokenBtn = document.getElementById("generate-token-btn");
    const enrollmentQrCode = document.getElementById("enrollment-qr-code");
    const enrollmentTokenDisplay = document.getElementById("enrollment-token-display");

    if (!generateTokenBtn) return;

    // Initialize QR Code generator
    const qrcode = new QRCode(enrollmentQrCode, {
        width: 256,
        height: 256,
        colorDark : "#000000",
        colorLight : "#ffffff",
        correctLevel : QRCode.CorrectLevel.H
    });

    generateTokenBtn.addEventListener("click", () => {
        const token = "DBS-GUARD-" + Math.random().toString(36).substr(2, 9).toUpperCase();
        enrollmentTokenDisplay.innerText = "Token: " + token;

        // APK URL - assuming the same host for now, or use a placeholder
        const apkUrl = window.location.origin + "/downloads/app-release.apk";
        
        // Payload for OOBE
        const payload = JSON.stringify({
            token: token,
            config: currentConfig,
            apkUrl: apkUrl
        });

        qrcode.clear();
        qrcode.makeCode(payload);
    });
}
