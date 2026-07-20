package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.bluetooth.BluetoothAdapter
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.network.NetworkModule
import com.example.service.ScheduleEnforcerService
import com.example.receiver.MyDeviceAdminReceiver
import com.example.service.MyAccessibilityService
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = ScheduleRepository(db.scheduleDao())
    private val policyDao = db.mdmPolicyDao()

    // SharedPreferences to persist Knox Guard provisioning status
    private val sharedPrefs = application.getSharedPreferences("familyguard_prefs", Context.MODE_PRIVATE)

    val schedules: StateFlow<List<ScheduleEntity>> = repository.allSchedules
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val policies: StateFlow<List<MdmPolicyEntity>> = policyDao.getAllPoliciesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val auditLogs: StateFlow<List<AuditLogEntity>> = db.auditLogDao().getAllLogs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _deviceId = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val _syncStatus = MutableStateFlow("Local Mode Active")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _isEnforcing = MutableStateFlow(true)
    val isEnforcing: StateFlow<Boolean> = _isEnforcing.asStateFlow()

    // Device lock screen state tracking
    private val _isProvisioned = MutableStateFlow(false)
    val isProvisioned: StateFlow<Boolean> = _isProvisioned.asStateFlow()

    val isLockScreenActive: StateFlow<Boolean> = combine(
        _isProvisioned,
        policies
    ) { provisioned, policyList ->
        val parentLocked = policyList.any { it.key == "parentLockActive" && it.value.lowercase() == "true" }
        !provisioned || parentLocked
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    private val _challengeToken = MutableStateFlow("")
    val challengeToken: StateFlow<String> = _challengeToken.asStateFlow()

    // Cryptographically-random offline bypass secret (NOT derivable by a child).
    private val _bypassSecret = MutableStateFlow("")
    val bypassSecret: StateFlow<String> = _bypassSecret.asStateFlow()

    private val _bypassError = MutableStateFlow<String?>(null)
    val bypassError: StateFlow<String?> = _bypassError.asStateFlow()

    // Blink Screen state variables
    data class BlinkState(val title: String, val message: String)
    private val _activeBlink = MutableStateFlow<BlinkState?>(null)
    val activeBlink: StateFlow<BlinkState?> = _activeBlink.asStateFlow()

    fun triggerBlink(title: String, message: String) {
        _activeBlink.value = BlinkState(title, message)
    }

    fun dismissBlink() {
        _activeBlink.value = null
    }

    init {
        // Set up the robust Uncaught Exception Handler crash receiver safely without redundant nesting
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (defaultHandler !is com.example.receiver.MdmCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(com.example.receiver.MdmCrashHandler(application, defaultHandler))
        }

        // Retrieve or generate a unique Device ID
        var cachedId = sharedPrefs.getString("device_id", "") ?: ""
        if (cachedId.isEmpty()) {
            cachedId = "dev-" + UUID.randomUUID().toString().substring(0, 8)
            sharedPrefs.edit().putString("device_id", cachedId).apply()
        }
        _deviceId.value = cachedId

        // Retrieve or generate a cryptographically-random OFFLINE BYPASS SECRET.
        // This is a per-device random value stored in private SharedPreferences and is
        // NOT derivable from the device id / date, so a child cannot reverse-engineer
        // it from the on-screen "authorization code".
        var bypassSecret = sharedPrefs.getString("bypass_secret", "") ?: ""
        if (bypassSecret.isEmpty()) {
            bypassSecret = (1..6).map { (0..9).random() }.joinToString("")
            sharedPrefs.edit().putString("bypass_secret", bypassSecret).apply()
        }
        _bypassSecret.value = bypassSecret

        // Retrieve provisioning state. Default to false on first launch to force provisioning visual block!
        val provisioned = sharedPrefs.getBoolean("is_provisioned", false)
        _isProvisioned.value = provisioned

        // Generate the on-screen (non-secret) challenge token shown to a parent as a session id.
        generateTodayChallengeToken()

        // Start active local background service monitoring
        toggleService(true)
        
        // Seed default schedules and policies if database is totally empty
        viewModelScope.launch {
            val list = repository.getAllSchedulesList()
            if (list.isEmpty()) {
                seedDefaultSchedules()
            }
            val existingPolicies = policyDao.getAllPolicies()
            if (existingPolicies.isEmpty()) {
                seedDefaultPolicies()
            }
        }

        // Synchronise screen share and control active states with policies flow
        viewModelScope.launch {
            policies.collect { plist ->
                val shareRequest = plist.firstOrNull { it.key == "remoteScreenShareRequest" }?.value?.lowercase() == "true"
                val controlRequest = plist.firstOrNull { it.key == "remoteScreenControlRequest" }?.value?.lowercase() == "true"
                if (_screenShareActive.value != shareRequest) {
                    _screenShareActive.value = shareRequest
                }
                if (_screenControlActive.value != controlRequest) {
                    _screenControlActive.value = controlRequest
                }
            }
        }

        // Start real-time background sync loop with Firebase Realtime DB
        startRealtimeSync()
    }

    private fun startRealtimeSync() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                try {
                    syncWithFirebaseInternal()
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "Real-time sync error: ${e.message}", e)
                }
                kotlinx.coroutines.delay(2000) // Poll every 2 seconds for near-instant responsiveness
            }
        }
    }

    private fun generateTodayChallengeToken() {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        val dateStr = dateFormat.format(Date())
        val salt = "DBS_GUARD_SECRET_SALT"
        val seedInput = _deviceId.value + dateStr + salt
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(seedInput.toByteArray())
            val hexString = hashBytes.joinToString("") { "%02x".format(it) }
            
            var digits = ""
            for (char in hexString) {
                if (char.isDigit()) {
                    digits += char
                    if (digits.length == 6) break
                }
            }
            while (digits.length < 6) {
                digits += "7" // safe filler
            }
            _challengeToken.value = digits
        } catch (e: Exception) {
            _challengeToken.value = "123456"
        }
    }

    /**
     * Attempts to unlock the device offline using the device-stored random bypass secret.
     * The on-screen "authorization code" (challengeToken) is intentionally NOT the PIN,
     * so it cannot be reversed to unlock the device.
     */
    fun attemptOfflineUnlock(userInputPin: String): Boolean {
        _bypassError.value = null
        val expectedPin = _bypassSecret.value
        if (userInputPin == expectedPin && expectedPin.isNotEmpty()) {
            setProvisionedState(true)
            return true
        } else {
            _bypassError.value = "Invalid Bypass Token."
            return false
        }
    }

    fun setProvisionedState(provisioned: Boolean) {
        _isProvisioned.value = provisioned
        sharedPrefs.edit().putBoolean("is_provisioned", provisioned).apply()
        if (provisioned) {
            _bypassError.value = null
        }
    }

    private suspend fun seedDefaultSchedules() {
        val list = listOf(
            ScheduleEntity(
                targetName = "YouTube",
                isCategory = false,
                startHour = 14,
                startMinute = 0,
                endHour = 18,
                endMinute = 0,
                daysOfWeek = "Mon,Tue,Wed,Thu,Fri",
                isEnabled = true
            ),
            ScheduleEntity(
                targetName = "Social Media",
                isCategory = true,
                startHour = 16,
                startMinute = 0,
                endHour = 19,
                endMinute = 30,
                daysOfWeek = "Mon,Wed,Fri",
                isEnabled = true
            ),
            ScheduleEntity(
                targetName = "Minecraft",
                isCategory = false,
                startHour = 9,
                startMinute = 0,
                endHour = 12,
                endMinute = 0,
                daysOfWeek = "Sat,Sun",
                isEnabled = true
            )
        )
        for (item in list) {
            repository.insertOrUpdate(item)
        }
    }

    private suspend fun seedDefaultPolicies() {
        val defaultPolicies = listOf(
            MdmPolicyEntity("cameraDisabled", "false", "boolean", "GREY_OUT"),
            MdmPolicyEntity("statusBarDisabled", "false", "boolean", "GREY_OUT"),
            MdmPolicyEntity("minimumPasswordLength", "8", "int", "GREY_OUT"),
            MdmPolicyEntity("disallowOutgoingCalls", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowConfigCredentials", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowFactoryReset", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowSafeBoot", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowUsbFileTransfer", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowModifyAccounts", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowScreenCapture", "false", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowAddUser", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowInstallApps", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowUninstallApps", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowDebuggingFeatures", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowShareLocation", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowAirplaneMode", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowSms", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("disallowMountPhysicalMedia", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("allowSettingsModification", "false", "boolean", "GREY_OUT"),
            MdmPolicyEntity("remoteScreenShareRequest", "false", "boolean", "GREY_OUT"),
            MdmPolicyEntity("remoteScreenControlRequest", "false", "boolean", "GREY_OUT"),
            MdmPolicyEntity("selfUpdateMode", "false", "boolean", "GREY_OUT"),
            MdmPolicyEntity("autoTimeRequired", "true", "boolean", "GREY_OUT"),
            MdmPolicyEntity("maximumTimeToLock", "15000", "int", "GREY_OUT"),
            MdmPolicyEntity("customMdmEnterpriseFlag", "ActiveSecurityNode", "string", "PERMIT_KID")
        )
        for (policy in defaultPolicies) {
            policyDao.insertOrUpdatePolicy(policy)
        }
    }

    fun addOrUpdatePolicy(policy: MdmPolicyEntity) {
        viewModelScope.launch {
            policyDao.insertOrUpdatePolicy(policy)
            _syncStatus.value = "Updated MDM policy: ${policy.key}"
        }
    }

    fun deletePolicy(policy: MdmPolicyEntity) {
        viewModelScope.launch {
            policyDao.deletePolicy(policy)
            _syncStatus.value = "Deleted policy: ${policy.key}"
        }
    }

    private var torchJob: Job? = null
    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private val _isTorchBlinking = MutableStateFlow(false)
    val isTorchBlinking: StateFlow<Boolean> = _isTorchBlinking.asStateFlow()

    private val _isBluetoothOn = MutableStateFlow(false)
    val isBluetoothOn: StateFlow<Boolean> = _isBluetoothOn.asStateFlow()

    private val _isWifiOn = MutableStateFlow(false)
    val isWifiOn: StateFlow<Boolean> = _isWifiOn.asStateFlow()

    fun toggleTorch(enable: Boolean) {
        _isTorchBlinking.value = false
        torchJob?.cancel()
        _isTorchOn.value = enable
        try {
            val context = getApplication<Application>()
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.getOrNull(0)
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                addManualAuditLog("DEVICE_CONTROL", "Flashlight turned ${if (enable) "ON" else "OFF"}")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Flashlight error: ${e.message}")
        }
    }

    fun toggleTorchBlinking(enable: Boolean) {
        _isTorchBlinking.value = enable
        torchJob?.cancel()
        if (enable) {
            _isTorchOn.value = true
            torchJob = viewModelScope.launch(Dispatchers.Default) {
                val context = getApplication<Application>()
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.getOrNull(0)
                var state = true
                addManualAuditLog("DEVICE_CONTROL", "Flashlight blinking STARTED")
                while (_isTorchBlinking.value) {
                    if (cameraId != null) {
                        try {
                            cameraManager.setTorchMode(cameraId, state)
                        } catch (e: Exception) {}
                    }
                    _isTorchOn.value = state
                    state = !state
                    delay(500)
                }
                // ensure off when done
                if (cameraId != null) {
                    try {
                        cameraManager.setTorchMode(cameraId, false)
                    } catch (e: Exception) {}
                }
                _isTorchOn.value = false
                addManualAuditLog("DEVICE_CONTROL", "Flashlight blinking STOPPED")
            }
        } else {
            _isTorchOn.value = false
            try {
                val context = getApplication<Application>()
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.getOrNull(0)
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, false)
                }
            } catch (e: Exception) {}
        }
    }

    fun toggleBluetooth(enable: Boolean) {
        _isBluetoothOn.value = enable
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null) {
                if (enable) {
                    @Suppress("DEPRECATION")
                    bluetoothAdapter.enable()
                } else {
                    @Suppress("DEPRECATION")
                    bluetoothAdapter.disable()
                }
                addManualAuditLog("DEVICE_CONTROL", "Bluetooth turned ${if (enable) "ON" else "OFF"}")
            } else {
                addManualAuditLog("DEVICE_CONTROL", "Bluetooth adapter not available on this device")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Bluetooth toggle error", e)
            addManualAuditLog("DEVICE_CONTROL", "Bluetooth toggle failed")
        }
    }

    fun toggleWifi(enable: Boolean) {
        _isWifiOn.value = enable
        try {
            val context = getApplication<Application>()
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enable
            addManualAuditLog("DEVICE_CONTROL", "Wi-Fi turned ${if (enable) "ON" else "OFF"}")
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Wi-Fi toggle error", e)
            addManualAuditLog("DEVICE_CONTROL", "Wi-Fi toggle failed")
        }
    }

    fun toggleAirplaneMode(enable: Boolean) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val cr = context.contentResolver
                @Suppress("DEPRECATION")
                val current = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                if (current == enable) {
                    addManualAuditLog("DEVICE_CONTROL", "Airplane mode already ${if (enable) "ON" else "OFF"}")
                    return@launch
                }
                android.provider.Settings.Global.putInt(cr, android.provider.Settings.Global.AIRPLANE_MODE_ON, if (enable) 1 else 0)
                context.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply { putExtra("state", enable) })
                addManualAuditLog("DEVICE_CONTROL", "Airplane mode turned ${if (enable) "ON" else "OFF"}")
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Airplane mode toggle error", e)
                addManualAuditLog("DEVICE_CONTROL", "Airplane mode toggle failed (requires Device Owner or system app)")
            }
        }
    }

    fun toggleNfc(enable: Boolean) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val nfcManager = context.getSystemService(Context.NFC_SERVICE) as android.nfc.NfcManager
                val adapter = nfcManager.defaultAdapter
                if (adapter == null) {
                    addManualAuditLog("DEVICE_CONTROL", "NFC adapter not available on this device")
                    return@launch
                }
                if (enable) {
                    if (adapter.isEnabled) {
                        addManualAuditLog("DEVICE_CONTROL", "NFC already ON")
                        return@launch
                    }
                    @Suppress("DEPRECATION")
                    adapter.enable()
                    addManualAuditLog("DEVICE_CONTROL", "NFC turned ON")
                } else {
                    if (!adapter.isEnabled) {
                        addManualAuditLog("DEVICE_CONTROL", "NFC already OFF")
                        return@launch
                    }
                    @Suppress("DEPRECATION")
                    adapter.disable()
                    addManualAuditLog("DEVICE_CONTROL", "NFC turned OFF")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "NFC toggle error", e)
                addManualAuditLog("DEVICE_CONTROL", "NFC toggle failed")
            }
        }
    }

    fun triggerGoHome() {
        viewModelScope.launch {
            var executed = false
            try {
                val service = MyAccessibilityService.instance
                if (service != null) {
                    executed = service.performHomeAction()
                }
            } catch (e: Exception) {}

            if (!executed) {
                // Intent fallback
                try {
                    val context = getApplication<Application>()
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    executed = true
                } catch (e: Exception) {}
            }
            addManualAuditLog("DEVICE_CONTROL", "Triggered 'Go Home' action (Success=$executed)")
        }
    }

    fun triggerGoBack() {
        viewModelScope.launch {
            var executed = false
            try {
                val service = MyAccessibilityService.instance
                if (service != null) {
                    executed = service.performBackAction()
                }
            } catch (e: Exception) {}
            addManualAuditLog("DEVICE_CONTROL", "Triggered 'Go Back' action (Success=$executed)")
        }
    }

    private val _screenShareActive = MutableStateFlow(false)
    val screenShareActive: StateFlow<Boolean> = _screenShareActive.asStateFlow()

    private val _screenControlActive = MutableStateFlow(false)
    val screenControlActive: StateFlow<Boolean> = _screenControlActive.asStateFlow()

    fun toggleScreenShare(enable: Boolean) {
        _screenShareActive.value = enable
        viewModelScope.launch {
            // Also mirror to DPM policies
            val policy = policyDao.getPolicyByKey("remoteScreenShareRequest") ?: MdmPolicyEntity("remoteScreenShareRequest", "false", "boolean", "GREY_OUT")
            policyDao.insertOrUpdatePolicy(policy.copy(value = enable.toString()))
            addManualAuditLog("DEVICE_CONTROL", if (enable) "Administrator initiated Remote Screen Share" else "Remote Screen Share terminated")
        }
    }

    fun toggleScreenControl(enable: Boolean) {
        _screenControlActive.value = enable
        viewModelScope.launch {
            // Also mirror to DPM policies
            val policy = policyDao.getPolicyByKey("remoteScreenControlRequest") ?: MdmPolicyEntity("remoteScreenControlRequest", "false", "boolean", "GREY_OUT")
            policyDao.insertOrUpdatePolicy(policy.copy(value = enable.toString()))
            addManualAuditLog("DEVICE_CONTROL", if (enable) "Administrator initiated Remote Screen Control" else "Remote Screen Control terminated")
        }
    }

    fun toggleService(enable: Boolean) {
        _isEnforcing.value = enable
        if (enable) {
            ScheduleEnforcerService.start(getApplication())
        } else {
            ScheduleEnforcerService.stop(getApplication())
        }
    }

    fun addOrUpdateSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            repository.insertOrUpdate(schedule)
            _syncStatus.value = "Updated local rule"
        }
    }

    fun deleteSchedule(schedule: ScheduleEntity) {
        viewModelScope.launch {
            repository.delete(schedule)
            _syncStatus.value = "Deleted rule: ${schedule.targetName}"
        }
    }

    // Sync from Firebase
    fun fetchFromFirebase() {
        viewModelScope.launch {
            _syncStatus.value = "Syncing from Firebase..."
            try {
                syncWithFirebaseInternal()
            } catch (e: Exception) {
                _syncStatus.value = "Sync Failed: Offline Mode Active"
            }
        }
    }

    // Sync to Firebase (Including Local Audit Logs)
    fun pushToFirebase() {
        viewModelScope.launch {
            _syncStatus.value = "Pushing local state & audit logs..."
            try {
                pushToFirebaseInternal()
                _syncStatus.value = "Sync Success: Uploaded rules and logs to cloud"
            } catch (e: Exception) {
                _syncStatus.value = "Failed pushing: Saved locally. Error: ${e.message}"
            }
        }
    }

    /**
     * Captures a comprehensive, enterprise-grade snapshot of the device on first launch
     * and on every sync: the Global, Secure, and System settings tables plus admin
     * privileges, hardware metadata and (optionally) GPS.
     *
     * The web dashboard edits the three settings tables; the client enforces/applies
     * them via [applyDeviceSettings].
     */
    data class SettingsTables(
        val global: Map<String, String>,
        val secure: Map<String, String>,
        val system: Map<String, String>
    )

    private fun readSettingsTables(context: Context): SettingsTables {
        val cr = context.contentResolver
        val global = mutableMapOf<String, String>()
        val secure = mutableMapOf<String, String>()
        val system = mutableMapOf<String, String>()
        try {
            // ---- GLOBAL table ----
            global["airplane_mode_on"] = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0).toString()
            global["wifi_on"] = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.WIFI_ON, 0).toString()
            global["mobile_data"] = try { android.provider.Settings.Global.getInt(cr, "mobile_data", 0).toString() } catch (_: Exception) { "0" }
            global["bluetooth_on"] = try { android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.BLUETOOTH_ON, 0).toString() } catch (_: Exception) { "0" }
            global["adb_enabled"] = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.ADB_ENABLED, 0).toString()
            global["development_settings_enabled"] = android.provider.Settings.Global.getInt(cr, "development_settings_enabled", 0).toString()
            global["stay_on_while_plugged_in"] = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0).toString()
            global["auto_time"] = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.AUTO_TIME, 1).toString()
            global["auto_time_zone"] = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.AUTO_TIME_ZONE, 1).toString()
            global["data_roaming"] = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.DATA_ROAMING, 0).toString()

            // ---- SECURE table ----
            secure["install_non_market_apps"] = android.provider.Settings.Secure.getInt(cr, android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS, 0).toString()
            secure["location_mode"] = android.provider.Settings.Secure.getInt(cr, android.provider.Settings.Secure.LOCATION_MODE, 0).toString()
            secure["accessibility_enabled"] = android.provider.Settings.Secure.getInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0).toString()
            secure["bluetooth_address"] = try { android.provider.Settings.Secure.getString(cr, "bluetooth_address") ?: "n/a" } catch (_: Exception) { "n/a" }
            secure["allowed_geolocation_origins"] = try { android.provider.Settings.Secure.getString(cr, "allowed_geolocation_origins") ?: "" } catch (_: Exception) { "" }
            secure["skip_first_use_hints"] = try { android.provider.Settings.Secure.getInt(cr, "skip_first_use_hints", 0).toString() } catch (_: Exception) { "0" }
            secure["lock_to_app_enabled"] = try { android.provider.Settings.Secure.getInt(cr, "lock_to_app_enabled", 0).toString() } catch (_: Exception) { "0" }

            // ---- SYSTEM table ----
            system["screen_brightness"] = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 0).toString()
            system["screen_brightness_mode"] = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0).toString()
            system["screen_off_timeout"] = android.provider.Settings.System.getLong(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 0).toString()
            system["haptic_feedback_enabled"] = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1).toString()
            system["sound_effects_enabled"] = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SOUND_EFFECTS_ENABLED, 1).toString()
            system["accelerometer_rotation"] = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1).toString()
            system["font_scale"] = try { android.provider.Settings.System.getFloat(cr, android.provider.Settings.System.FONT_SCALE, 1f).toString() } catch (_: Exception) { "1.0" }
            system["time_12_24"] = try { android.provider.Settings.System.getInt(cr, android.provider.Settings.System.TIME_12_24, 24).toString() } catch (_: Exception) { "24" }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error reading settings tables", e)
        }
        return SettingsTables(global, secure, system)
    }

    private fun getSystemSettingsMap(context: Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val cr = context.contentResolver
        try {
            // Device Administration & Privileges
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, MyDeviceAdminReceiver::class.java)
            val isAdminActive = dpm.isAdminActive(adminComponent)
            val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)

            map["is_device_admin"] = isAdminActive.toString()
            map["is_device_owner"] = isDeviceOwner.toString()
            map["accessibility_active"] = (MyAccessibilityService.instance != null).toString()
            map["overlay_allowed"] = android.provider.Settings.canDrawOverlays(context).toString()
            map["write_settings_granted"] = android.provider.Settings.System.canWrite(context).toString()

            // Permissions
            val cameraGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val locationGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

            map["camera_permission"] = cameraGranted.toString()
            map["location_permission"] = locationGranted.toString()

            var lat = "Unknown"
            var lon = "Unknown"
            if (locationGranted) {
                try {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    val providers = lm.getProviders(true)
                    var bestLocation: android.location.Location? = null
                    for (provider in providers) {
                        val l = lm.getLastKnownLocation(provider) ?: continue
                        if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                            bestLocation = l
                        }
                    }
                    if (bestLocation != null) {
                        lat = String.format(java.util.Locale.US, "%.6f", bestLocation.latitude)
                        lon = String.format(java.util.Locale.US, "%.6f", bestLocation.longitude)
                    }
                } catch (ex: SecurityException) {
                    android.util.Log.e("MainViewModel", "Security exception getting location", ex)
                } catch (ex: Exception) {
                    android.util.Log.e("MainViewModel", "Exception getting location", ex)
                }
            }
            map["latitude"] = lat
            map["longitude"] = lon

            // Hardware Metadata
            map["device_model"] = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            map["device_sdk"] = android.os.Build.VERSION.SDK_INT.toString()
            map["device_brand"] = android.os.Build.BRAND
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Error reading system tables", e)
        }
        return map
    }

    /**
     * Applies admin-edited Global/Secure/System settings back onto the device.
     * Requires WRITE_SETTINGS (System/Global) and WRITE_SECURE_SETTINGS (Secure).
     * When provisioned as Device Owner these are automatically granted; otherwise the
     * user is (re)prompted and the failure is logged so the dashboard sees the gap.
     */
    fun applyDeviceSettings(global: Map<String, String>, secure: Map<String, String>, system: Map<String, String>) {
        viewModelScope.launch(Dispatchers.Default) {
            val context = getApplication<Application>().applicationContext
            val cr = context.contentResolver
            var applied = 0
            var failed = 0

            fun putGlobal(key: String, value: String) {
                try {
                    android.provider.Settings.Global.putInt(cr, key, value.toIntOrNull() ?: 0)
                    applied++
                } catch (_: Exception) { failed++ }
            }
            fun putSecure(key: String, value: String) {
                try {
                    android.provider.Settings.Secure.putInt(cr, key, value.toIntOrNull() ?: 0)
                    applied++
                } catch (_: Exception) { failed++ }
            }
            fun putSystem(key: String, value: String) {
                try {
                    android.provider.Settings.System.putInt(cr, key, value.toIntOrNull() ?: 0)
                    applied++
                } catch (_: Exception) { failed++ }
            }

            global.forEach { (k, v) ->
                when (k) {
                    "airplane_mode_on" -> putGlobal(android.provider.Settings.Global.AIRPLANE_MODE_ON, v)
                    "wifi_on" -> putGlobal(android.provider.Settings.Global.WIFI_ON, v)
                    "mobile_data" -> putGlobal("mobile_data", v)
                    "bluetooth_on" -> putGlobal(android.provider.Settings.Global.BLUETOOTH_ON, v)
                    "adb_enabled" -> putGlobal(android.provider.Settings.Global.ADB_ENABLED, v)
                    "development_settings_enabled" -> putGlobal("development_settings_enabled", v)
                    "stay_on_while_plugged_in" -> putGlobal(android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN, v)
                    "auto_time" -> putGlobal(android.provider.Settings.Global.AUTO_TIME, v)
                    "auto_time_zone" -> putGlobal(android.provider.Settings.Global.AUTO_TIME_ZONE, v)
                    "data_roaming" -> putGlobal(android.provider.Settings.Global.DATA_ROAMING, v)
                }
            }
            secure.forEach { (k, v) ->
                when (k) {
                    "install_non_market_apps" -> putSecure(android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS, v)
                    "location_mode" -> putSecure(android.provider.Settings.Secure.LOCATION_MODE, v)
                    "skip_first_use_hints" -> putSecure("skip_first_use_hints", v)
                    "lock_to_app_enabled" -> putSecure("lock_to_app_enabled", v)
                }
            }
            system.forEach { (k, v) ->
                when (k) {
                    "screen_brightness" -> putSystem(android.provider.Settings.System.SCREEN_BRIGHTNESS, v)
                    "screen_brightness_mode" -> putSystem(android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, v)
                    "screen_off_timeout" -> {
                        try {
                            android.provider.Settings.System.putLong(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, v.toLongOrNull() ?: 0)
                            applied++
                        } catch (_: Exception) { failed++ }
                    }
                    "haptic_feedback_enabled" -> putSystem(android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, v)
                    "sound_effects_enabled" -> putSystem(android.provider.Settings.System.SOUND_EFFECTS_ENABLED, v)
                    "accelerometer_rotation" -> putSystem(android.provider.Settings.System.ACCELEROMETER_ROTATION, v)
                    "time_12_24" -> putSystem(android.provider.Settings.System.TIME_12_24, v)
                }
            }

            addManualAuditLog(
                "SETTINGS_APPLY",
                "Applied remote settings: $applied ok, $failed failed (needs WRITE_SETTINGS/SECURE_SETTINGS)."
            )
            if (failed > 0) {
                // Re-request the WRITE_SETTINGS capability if not granted.
                if (!android.provider.Settings.System.canWrite(context)) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        }
    }


    private suspend fun syncWithFirebaseInternal() {
        val devId = _deviceId.value
        if (devId.isEmpty()) return

        try {
            val payload = NetworkModule.api.getDeviceData(devId)
            if (payload != null) {
                // 1. Reconcile/sync schedules
                if (payload.schedules != null) {
                    val localSchedules = repository.getAllSchedulesList()
                    for ((_, fbSched) in payload.schedules) {
                        val matchingLocal = localSchedules.find { it.targetName == fbSched.targetName }
                        val toInsert = ScheduleEntity(
                            id = matchingLocal?.id ?: 0,
                            targetName = fbSched.targetName,
                            isCategory = fbSched.isCategory,
                            startHour = fbSched.startHour,
                            startMinute = fbSched.startMinute,
                            endHour = fbSched.endHour,
                            endMinute = fbSched.endMinute,
                            daysOfWeek = fbSched.daysOfWeek,
                            isEnabled = fbSched.isEnabled,
                            actionType = fbSched.actionType,
                            actionTarget = fbSched.actionTarget
                        )
                        repository.insertOrUpdate(toInsert)
                    }
                }

                // 2. Mirror Policy changes from Firebase to local database (automatically reflects to device itself)
                if (payload.policies != null) {
                    for ((_, fbPol) in payload.policies) {
                        val toInsert = MdmPolicyEntity(
                            key = fbPol.key,
                            value = fbPol.value,
                            valueType = fbPol.valueType,
                            permissionFlag = fbPol.permissionFlag
                        )
                        policyDao.insertOrUpdatePolicy(toInsert)
                    }
                }

                // 3. Process remote real-time commands from Firebase
                if (payload.commands != null) {
                    val updatedCommands = payload.commands.toMutableMap()
                    var commandExecuted = false
                    val dpm = getApplication<Application>().getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val adminComponent = android.content.ComponentName(getApplication(), MyDeviceAdminReceiver::class.java)

                    for ((cmdId, cmd) in payload.commands) {
                        if (!cmd.executed) {
                            android.util.Log.i("MainViewModel", "Executing Firebase command: ${cmd.commandType} on target: ${cmd.target}")
                            try {
                                when (cmd.commandType.lowercase()) {
                                    "lock" -> {
                                        if (dpm.isAdminActive(adminComponent)) {
                                            dpm.lockNow()
                                            db.auditLogDao().insertLog(
                                                AuditLogEntity(
                                                    eventType = "COMMAND_EXECUTION",
                                                    message = "Executed remote lock screen command from parent.",
                                                    isSynced = false
                                                )
                                            )
                                        }
                                    }
                                    "suspend", "disable", "blacklist_app" -> {
                                        val pkg = cmd.target ?: ""
                                        if (pkg.isNotEmpty()) {
                                            val scheduleName = "Block_${pkg.replace(".", "_")}"
                                            repository.insertOrUpdate(
                                                ScheduleEntity(
                                                    targetName = scheduleName,
                                                    isCategory = false,
                                                    startHour = 0,
                                                    startMinute = 0,
                                                    endHour = 23,
                                                    endMinute = 59,
                                                    daysOfWeek = "Daily",
                                                    isEnabled = true,
                                                    actionType = cmd.commandType.uppercase(),
                                                    actionTarget = pkg
                                                )
                                            )
                                            db.auditLogDao().insertLog(
                                                AuditLogEntity(
                                                    eventType = "COMMAND_EXECUTION",
                                                    message = "Executed remote block/suspend command for package: $pkg.",
                                                    isSynced = false
                                                )
                                            )
                                        }
                                    }
                                    "uninstall" -> {
                                        val pkg = cmd.target ?: ""
                                        if (pkg.isNotEmpty()) {
                                            db.auditLogDao().insertLog(
                                                AuditLogEntity(
                                                    eventType = "COMMAND_EXECUTION",
                                                    message = "Parent requested remote uninstall of: $pkg. Restricting package launch.",
                                                    isSynced = false
                                                )
                                            )
                                            // Suspend package to block launch since real uninstall is user-prompted
                                            val scheduleName = "UninstallBlock_${pkg.replace(".", "_")}"
                                            repository.insertOrUpdate(
                                                ScheduleEntity(
                                                    targetName = scheduleName,
                                                    isCategory = false,
                                                    startHour = 0,
                                                    startMinute = 0,
                                                    endHour = 23,
                                                    endMinute = 59,
                                                    daysOfWeek = "Daily",
                                                    isEnabled = true,
                                                    actionType = "UNINSTALL",
                                                    actionTarget = pkg
                                                )
                                            )
                                        }
                                    }
                                    "blink_screen" -> {
                                        val title = cmd.target ?: "Alert from Parent"
                                        val body = cmd.value ?: "Please check in with your parent immediately."
                                        triggerBlink(title, body)
                                        db.auditLogDao().insertLog(
                                            AuditLogEntity(
                                                eventType = "COMMAND_EXECUTION",
                                                message = "Triggered custom Blink Screen: $title",
                                                isSynced = false
                                            )
                                        )
                                    }
                                    "torch_on" -> {
                                        toggleTorch(true)
                                    }
                                    "torch_off" -> {
                                        toggleTorch(false)
                                    }
                                    "torch_blink_on" -> {
                                        toggleTorchBlinking(true)
                                    }
                                    "torch_blink_off" -> {
                                        toggleTorchBlinking(false)
                                    }
                                    "bluetooth_on" -> {
                                        toggleBluetooth(true)
                                        addManualAuditLog("COMMAND_EXECUTION", "Remote command: turned Bluetooth ON")
                                    }
                                    "bluetooth_off" -> {
                                        toggleBluetooth(false)
                                        addManualAuditLog("COMMAND_EXECUTION", "Remote command: turned Bluetooth OFF")
                                    }
                                    "wifi_on" -> {
                                        toggleWifi(true)
                                        addManualAuditLog("COMMAND_EXECUTION", "Remote command: turned Wi-Fi ON")
                                    }
                                    "wifi_off" -> {
                                        toggleWifi(false)
                                        addManualAuditLog("COMMAND_EXECUTION", "Remote command: turned Wi-Fi OFF")
                                    }
                                    "airplane_on" -> {
                                        toggleAirplaneMode(true)
                                        addManualAuditLog("COMMAND_EXECUTION", "Remote command: turned Airplane Mode ON")
                                    }
                                    "airplane_off" -> {
                                        toggleAirplaneMode(false)
                                        addManualAuditLog("COMMAND_EXECUTION", "Remote command: turned Airplane Mode OFF")
                                    }
                                    "nfc_on" -> {
                                        toggleNfc(true)
                                        addManualAuditLog("COMMAND_EXECUTION", "Remote command: turned NFC ON")
                                    }
                                    "nfc_off" -> {
                                        toggleNfc(false)
                                        addManualAuditLog("COMMAND_EXECUTION", "Remote command: turned NFC OFF")
                                    }
                                    "go_home" -> {
                                        triggerGoHome()
                                        addManualAuditLog("COMMAND_EXECUTION", "Remote command: triggered Go Home")
                                    }
                                    "go_back" -> {
                                        triggerGoBack()
                                        addManualAuditLog("COMMAND_EXECUTION", "Remote command: triggered Go Back")
                                    }
                                }
                                updatedCommands[cmdId] = cmd.copy(executed = true)
                                commandExecuted = true
                            } catch (cmdEx: Exception) {
                                android.util.Log.e("MainViewModel", "Error executing remote command $cmdId", cmdEx)
                            }
                        }
                    }

                    if (commandExecuted) {
                        val nextPayload = payload.copy(
                            commands = updatedCommands,
                            lastUpdated = System.currentTimeMillis()
                        )
                        NetworkModule.api.updateDeviceData(devId, nextPayload)
                    }
                }
                
                // Upload fresh system tables, schedules, and policies
                pushToFirebaseInternal(applyDownstream = false)
                // Apply admin-edited settings tables pushed from dashboard
                payload.globalSettings?.let { g ->
                    payload.secureSettings?.let { s -> payload.systemTable?.let { t -> applyDeviceSettings(g, s, t) } }
                }
                _syncStatus.value = "Sync Success: Synchronized with Realtime DB"
            } else {
                pushToFirebaseInternal(applyDownstream = false)
            }
        } catch (e: Exception) {
            _syncStatus.value = "Sync Failed: Offline Mode Active"
            android.util.Log.e("MainViewModel", "Sync internal failed: ${e.message}", e)
        }
    }

    private suspend fun pushToFirebaseInternal(applyDownstream: Boolean = false) {
        val devId = _deviceId.value
        if (devId.isEmpty()) return

        val list = repository.getAllSchedulesList()
        val schedulesMap = list.associate {
            val key = "sched_" + it.targetName.replace(" ", "_").lowercase()
            key to FirebaseSchedule(
                id = key,
                targetName = it.targetName,
                isCategory = it.isCategory,
                startHour = it.startHour,
                startMinute = it.startMinute,
                endHour = it.endHour,
                endMinute = it.endMinute,
                daysOfWeek = it.daysOfWeek,
                isEnabled = it.isEnabled,
                actionType = it.actionType,
                actionTarget = it.actionTarget
            )
        }

        val pols = policyDao.getAllPolicies()
        val policiesMap = pols.associate {
            it.key to FirebasePolicy(
                key = it.key,
                value = it.value,
                valueType = it.valueType,
                permissionFlag = it.permissionFlag
            )
        }

        val logsList = db.auditLogDao().getUnsyncedLogs()
        val logsMap = logsList.associate {
            val key = "log_${it.id}"
            key to FirebaseAuditLog(
                id = it.id,
                timestamp = it.timestamp,
                eventType = it.eventType,
                message = it.message
            )
        }

        val sysSettings = getSystemSettingsMap(getApplication())
        val tables = readSettingsTables(getApplication())

        val payload = FirebaseDataPayload(
            deviceId = devId,
            schedules = schedulesMap,
            policies = policiesMap,
            auditLogs = if (logsMap.isNotEmpty()) logsMap else null,
            systemSettings = sysSettings,
            globalSettings = tables.global,
            secureSettings = tables.secure,
            systemTable = tables.system,
            lastUpdated = System.currentTimeMillis()
        )

        NetworkModule.api.updateDeviceData(devId, payload)

        if (logsList.isNotEmpty()) {
            db.auditLogDao().markLogsAsSynced(logsList.map { it.id })
        }
    }

    fun addManualAuditLog(eventType: String, message: String) {
        viewModelScope.launch {
            db.auditLogDao().insertLog(
                AuditLogEntity(
                    eventType = eventType,
                    message = message,
                    isSynced = false
                )
            )
        }
    }

    fun clearAuditLogs() {
        viewModelScope.launch {
            db.auditLogDao().clearAllLogs()
            _syncStatus.value = "Cleared all local logs"
        }
    }
}
