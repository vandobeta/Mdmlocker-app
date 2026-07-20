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

        // Retrieve provisioning state. Default to false on first launch to force provisioning visual block!
        val provisioned = sharedPrefs.getBoolean("is_provisioned", false)
        _isProvisioned.value = provisioned

        // Generate challenge token for current day
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
     * Attempts to unlock the device offline using the mathematical inverse/reverse string PIN
     */
    fun attemptOfflineUnlock(userInputPin: String): Boolean {
        _bypassError.value = null
        val expectedPin = _challengeToken.value.reversed()
        if (userInputPin == expectedPin) {
            setProvisionedState(true)
            return true
        } else {
            _bypassError.value = "Invalid Bypass Token. Math Signature mismatch."
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
            MdmPolicyEntity("allowSettingsModification", "false", "boolean", "GREY_OUT"),
            MdmPolicyEntity("remoteScreenShareRequest", "false", "boolean", "GREY_OUT"),
            MdmPolicyEntity("remoteScreenControlRequest", "false", "boolean", "GREY_OUT"),
            MdmPolicyEntity("selfUpdateMode", "false", "boolean", "GREY_OUT"),
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
                addManualAuditLog("DEVICE_CONTROL", "Bluetooth adapter not found (Simulated ${if (enable) "ON" else "OFF"})")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Bluetooth toggle error", e)
            addManualAuditLog("DEVICE_CONTROL", "Bluetooth toggle failed (Simulated ${if (enable) "ON" else "OFF"})")
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
            addManualAuditLog("DEVICE_CONTROL", "Wi-Fi toggle failed (Simulated ${if (enable) "ON" else "OFF"})")
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

    private fun getSystemSettingsMap(context: Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val cr = context.contentResolver
        try {
            // Global settings table
            map["global_airplane_mode_on"] = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0).toString()
            map["global_wifi_on"] = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.WIFI_ON, 0).toString()
            map["global_adb_enabled"] = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.ADB_ENABLED, 0).toString()
            map["global_development_settings_enabled"] = android.provider.Settings.Global.getInt(cr, "development_settings_enabled", 0).toString()

            // System settings table
            map["system_screen_brightness"] = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 0).toString()
            map["system_screen_off_timeout"] = android.provider.Settings.System.getLong(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 0).toString()

            // Secure settings table
            map["secure_install_non_market_apps"] = android.provider.Settings.Secure.getInt(cr, "install_non_market_apps", 0).toString()
            map["secure_location_mode"] = android.provider.Settings.Secure.getInt(cr, "location_mode", 0).toString()
            map["secure_accessibility_enabled"] = android.provider.Settings.Secure.getInt(cr, "accessibility_enabled", 0).toString()

            // Device Administration & Privileges
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, MyDeviceAdminReceiver::class.java)
            val isAdminActive = dpm.isAdminActive(adminComponent)
            val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)

            map["is_device_admin"] = isAdminActive.toString()
            map["is_device_owner"] = isDeviceOwner.toString()
            map["accessibility_active"] = (MyAccessibilityService.instance != null).toString()
            map["overlay_allowed"] = android.provider.Settings.canDrawOverlays(context).toString()

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
                pushToFirebaseInternal()
                _syncStatus.value = "Sync Success: Synchronized with Realtime DB"
            } else {
                pushToFirebaseInternal()
            }
        } catch (e: Exception) {
            _syncStatus.value = "Sync Failed: Offline Mode Active"
            android.util.Log.e("MainViewModel", "Sync internal failed: ${e.message}", e)
        }
    }

    private suspend fun pushToFirebaseInternal() {
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

        val payload = FirebaseDataPayload(
            deviceId = devId,
            schedules = schedulesMap,
            policies = policiesMap,
            auditLogs = if (logsMap.isNotEmpty()) logsMap else null,
            systemSettings = sysSettings,
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
