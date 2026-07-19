package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.AppDatabase
import com.example.data.ScheduleEntity
import com.example.data.MdmPolicyEntity
import com.example.receiver.MyDeviceAdminReceiver
import com.example.receiver.MdmCrashHandler
import kotlinx.coroutines.*
import java.util.Calendar

class ScheduleEnforcerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var enforcementJob: Job? = null
    private var sessionCallback: PackageInstaller.SessionCallback? = null
    private var lastSuspendedPackages: Set<String> = emptySet()

    companion object {
        private const val CHANNEL_ID = "familyguard_enforcement_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "FamilyGuardService"

        fun start(context: Context) {
            val intent = Intent(context, ScheduleEnforcerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScheduleEnforcerService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DBS FamilyGuard Policy Enforcer Service Created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Active Corporate Policy Enforcement Enabled"))

        // Set up the robust Uncaught Exception Handler crash receiver safely without redundant nesting
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (defaultHandler !is MdmCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(MdmCrashHandler(applicationContext, defaultHandler))
        }

        val database = AppDatabase.getDatabase(applicationContext)
        val policyDao = database.mdmPolicyDao()
        registerPackageInstallerCallback(policyDao)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "DBS FamilyGuard Enforcer Service Started")
        startEnforcementLoop()
        try {
            ForegroundTaskMonitorService.start(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ForegroundTaskMonitorService: ${e.message}")
        }
        return START_STICKY
    }

    private fun startEnforcementLoop() {
        enforcementJob?.cancel()
        enforcementJob = serviceScope.launch {
            val database = AppDatabase.getDatabase(applicationContext)
            val scheduleDao = database.scheduleDao()
            val policyDao = database.mdmPolicyDao()

            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(applicationContext, MyDeviceAdminReceiver::class.java)

            while (isActive) {
                try {
                    val isDeviceOwner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        dpm.isDeviceOwnerApp(packageName)
                    } else {
                        false
                    }
                    val isAdminActive = dpm.isAdminActive(adminComponent)

                    // Whitelist ourselves as lock task package if Device Owner
                    if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            if (!dpm.isLockTaskPermitted(packageName)) {
                                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
                                Log.i(TAG, "[LOCK TASK] Whitelisted $packageName for enterprise lockdown Kiosk mode.")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to configure Lock Task packages", e)
                        }
                    }

                    // 1. Evaluate and Suspend App Access Schedules
                    val schedules = scheduleDao.getAllSchedules()
                    val calendar = Calendar.getInstance()
                    val currentDayOfWeek = getCurrentDayString(calendar.get(Calendar.DAY_OF_WEEK))
                    val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
                    val currentMinute = calendar.get(Calendar.MINUTE)
                    val currentTimeInMinutes = currentHour * 60 + currentMinute

                    val blockedAppNames = mutableListOf<String>()
                    val packagesToSuspendOffline = mutableListOf<String>()

                    for (schedule in schedules) {
                        if (!schedule.isEnabled) continue

                        val dayMatches = schedule.daysOfWeek.equals("Daily", ignoreCase = true) ||
                                schedule.daysOfWeek.split(",").map { it.trim().lowercase() }
                                    .contains(currentDayOfWeek.lowercase())

                        if (dayMatches) {
                            val startTotalMinutes = schedule.startHour * 60 + schedule.startMinute
                            val endTotalMinutes = schedule.endHour * 60 + schedule.endMinute

                            val isCurrentlyInInterval = if (startTotalMinutes <= endTotalMinutes) {
                                currentTimeInMinutes in startTotalMinutes..endTotalMinutes
                            } else {
                                currentTimeInMinutes >= startTotalMinutes || currentTimeInMinutes <= endTotalMinutes
                            }

                            // Blacklist rules are always active when enabled, others active when outside allowed window
                            val shouldRestrict = !isCurrentlyInInterval || schedule.actionType == "BLACKLIST"

                            if (shouldRestrict) {
                                when (schedule.actionType.uppercase()) {
                                    "LOCK" -> {
                                        if (isAdminActive) {
                                            try {
                                                Log.w(TAG, "[OFFLINE ACTION] Enforcing scheduled lock action.")
                                                dpm.lockNow()
                                            } catch (lockEx: Exception) {
                                                Log.e(TAG, "Failed to execute offline lock action", lockEx)
                                            }
                                        }
                                    }
                                    "SUSPEND", "DISABLE" -> {
                                        if (schedule.actionTarget.isNotEmpty()) {
                                            packagesToSuspendOffline.add(schedule.actionTarget)
                                        } else {
                                            blockedAppNames.add(schedule.targetName)
                                        }
                                    }
                                    "BLACKLIST" -> {
                                        if (schedule.actionTarget.isNotEmpty()) {
                                            packagesToSuspendOffline.add(schedule.actionTarget)
                                        }
                                    }
                                    "UNINSTALL" -> {
                                        if (schedule.actionTarget.isNotEmpty()) {
                                            packagesToSuspendOffline.add(schedule.actionTarget)
                                            Log.w(TAG, "[OFFLINE ACTION] Scheduled uninstall active: suspending target ${schedule.actionTarget}")
                                        }
                                    }
                                    else -> {
                                        blockedAppNames.add(schedule.targetName)
                                    }
                                }
                            }
                        } else {
                            if (schedule.actionType == "BLACKLIST" && schedule.actionTarget.isNotEmpty()) {
                                packagesToSuspendOffline.add(schedule.actionTarget)
                            } else {
                                blockedAppNames.add(schedule.targetName)
                            }
                        }
                    }

                    val uniqueBlockedNames = blockedAppNames.distinct()

                    // Map blocked application names/categories to actual real-world package names
                    val targetPackagesToSuspend = mutableListOf<String>()
                    for (name in uniqueBlockedNames) {
                        val packages = mapAppNameToPackages(name)
                        targetPackagesToSuspend.addAll(packages)
                    }
                    targetPackagesToSuspend.addAll(packagesToSuspendOffline)

                    val filteredSuspended = targetPackagesToSuspend.filter { it != packageName && it.isNotBlank() }.toSet()
                    if (filteredSuspended != lastSuspendedPackages) {
                        val oldSet = lastSuspendedPackages
                        lastSuspendedPackages = filteredSuspended
                        
                        val added = filteredSuspended - oldSet
                        val removed = oldSet - filteredSuspended
                        
                        serviceScope.launch {
                            val db = AppDatabase.getDatabase(applicationContext)
                            if (added.isNotEmpty()) {
                                db.auditLogDao().insertLog(
                                    com.example.data.AuditLogEntity(
                                        eventType = "POLICY_ENFORCEMENT",
                                        message = "Enforced app restriction: Suspended ${added.joinToString(", ")}",
                                        isSynced = false
                                    )
                                )
                            }
                            if (removed.isNotEmpty()) {
                                db.auditLogDao().insertLog(
                                    com.example.data.AuditLogEntity(
                                        eventType = "POLICY_ENFORCEMENT",
                                        message = "Released app restriction: Un-suspended ${removed.joinToString(", ")}",
                                        isSynced = false
                                    )
                                )
                            }
                        }
                    }

                    // Enforce package suspension natively if Device Owner / Admin privileges are active
                    if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        try {
                            val activePackages = filteredSuspended.toTypedArray()
                            if (activePackages.isNotEmpty()) {
                                dpm.setPackagesSuspended(adminComponent, activePackages, true)
                                Log.i(TAG, "[EMM SUSPEND] Natively suspended packages: ${activePackages.joinToString(", ")}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to natively suspend packages via DPM", e)
                        }
                    }

                    // 2. Proactive Hardware Enforcement (Requirement 2)
                    enforceHardwareRadiosAndZenMode(isDeviceOwner, adminComponent)

                    // 3. Enforce 1-Second EMM System Settings & Policy Restrictions (Requirement 3 & 5)
                    val policies = policyDao.getAllPolicies()
                    for (policy in policies) {
                        if (policy.permissionFlag == "GREY_OUT") {
                            applyMdmPolicy(policy, dpm, adminComponent, isDeviceOwner, isAdminActive)
                        }
                    }

                    // Enforce lock-mode and EMM screen capture policies (screenshot & screen recording block system-wide)
                    if (isAdminActive) {
                        try {
                            val sharedPrefs = getSharedPreferences("familyguard_prefs", Context.MODE_PRIVATE)
                            val isProvisioned = sharedPrefs.getBoolean("is_provisioned", false)
                            val isParentLocked = policies.any { it.key == "parentLockActive" && it.value.lowercase() == "true" }
                            val isLockActive = !isProvisioned || isParentLocked

                            val explicitScreenCapturePolicy = policies.firstOrNull { it.key == "disallowScreenCapture" }
                            val isExplicitlyDisabled = explicitScreenCapturePolicy?.value?.lowercase() == "true"
                            val shouldDisableCapture = isLockActive || isExplicitlyDisabled

                            if (dpm.getScreenCaptureDisabled(adminComponent) != shouldDisableCapture) {
                                Log.i(TAG, "[POLICY ENFORCEMENT] Screen capture disabled state mismatch. Forcing screenCaptureDisabled=$shouldDisableCapture (lockActive=$isLockActive, explicit=$isExplicitlyDisabled)")
                                dpm.setScreenCaptureDisabled(adminComponent, shouldDisableCapture)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to apply screen capture policy", e)
                        }
                    }

                    // 4. Update status notifications dynamically
                    if (uniqueBlockedNames.isNotEmpty()) {
                        updateNotification("Restricted access active: ${uniqueBlockedNames.size} app policies suspended")
                    } else {
                        updateNotification("All standard applications allowed. Corporate policies active.")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in policy enforcement loop", e)
                }

                delay(1000) // Sweeps the system tables every 1,000 milliseconds to roll back tampering
            }
        }
    }

    /**
     * Map friendly app/category name into actual Android application package IDs.
     */
    private fun mapAppNameToPackages(name: String): List<String> {
        val lower = name.trim().lowercase()
        return when {
            lower.contains("youtube") -> listOf("com.google.android.youtube", "com.google.android.apps.youtube.kids")
            lower.contains("minecraft") -> listOf("com.mojang.minecraftpe")
            lower.contains("tiktok") -> listOf("com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go")
            lower.contains("games") -> listOf("com.king.candycrushsaga", "com.supercell.clashofclans", "com.roblox.client")
            lower.contains("social") || lower.contains("facebook") || lower.contains("instagram") -> 
                listOf("com.facebook.katana", "com.instagram.android", "com.twitter.android", "com.snapchat.android")
            else -> listOf(name) // Fallback to direct package ID if entered directly
        }
    }

    /**
     * Proactively forces hardware states:
     * - Turns off Flight Mode natively.
     * - Enables Wi-Fi hardware module.
     * - Enables Mobile Cellular Data connections.
     * - Activates Zen Mode (Do Not Disturb).
     */
    private fun enforceHardwareRadiosAndZenMode(isDeviceOwner: Boolean, adminComponent: ComponentName) {
        val contentResolver = contentResolver

        // Turn off Flight Mode
        try {
            val isFlightModeOn = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            if (isFlightModeOn) {
                Log.w(TAG, "[HARDWARE RADIO] Tampering detected: Airplane Mode is ON. Forcing Airplane Mode OFF.")
                Settings.Global.putInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
                // Broadcast change intent
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    putExtra("state", false)
                }
                sendBroadcast(intent)
                
                serviceScope.launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.auditLogDao().insertLog(
                        com.example.data.AuditLogEntity(
                            eventType = "HARDWARE_ROLLBACK",
                            message = "Anti-tamper rollback: Airplane Mode forced OFF.",
                            isSynced = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "Unable to write directly to AIRPLANE_MODE_ON (requires Device Owner/System status): ${e.message}")
        }

        // Enable Wi-Fi hardware
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                Log.w(TAG, "[HARDWARE RADIO] Forcing Wi-Fi Module ON.")
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = true
                
                serviceScope.launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.auditLogDao().insertLog(
                        com.example.data.AuditLogEntity(
                            eventType = "HARDWARE_ROLLBACK",
                            message = "Anti-tamper rollback: Wi-Fi radio module forced back ON.",
                            isSynced = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force Wi-Fi enabled", e)
        }

        // Enable Mobile Data connections
        try {
            Settings.Global.putInt(contentResolver, "mobile_data", 1)
        } catch (e: Exception) {
            Log.v(TAG, "Unable to force mobile_data key directly: ${e.message}")
        }

        // Activate Zen Mode (Do Not Disturb) to silence visual/audible feedback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE) {
                    Log.i(TAG, "[HARDWARE RADIO] Activating Zen Mode (Do Not Disturb) to silence all feedback.")
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "Notification policy access not granted yet or requires system privileges: ${e.message}")
        }
    }

    /**
     * Applies corporate EMM rules using the DevicePolicyManager and UserManager APIs.
     * Instantly rolls back user configurations if they violate corporate policy rules.
     */
    private fun applyMdmPolicy(
        policy: MdmPolicyEntity,
        dpm: DevicePolicyManager,
        adminComponent: ComponentName,
        isDeviceOwner: Boolean,
        isAdminActive: Boolean
    ) {
        if (!isAdminActive) return

        val key = policy.key
        val value = policy.value
        val isTrue = value.lowercase() == "true"

        try {
            when (key) {
                "cameraDisabled" -> {
                    if (dpm.getCameraDisabled(adminComponent) != isTrue) {
                        Log.w(TAG, "[POLICY ROLLBACK] Camera state mismatch. Forcing cameraDisabled=$isTrue")
                        dpm.setCameraDisabled(adminComponent, isTrue)
                    }
                }
                "statusBarDisabled" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isDeviceOwner) {
                        try {
                            // Note: We catch security exceptions since setStatusBarDisabled is strictly Device Owner
                            dpm.setStatusBarDisabled(adminComponent, isTrue)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to enforce statusBarDisabled", e)
                        }
                    }
                }
                "minimumPasswordLength" -> {
                    val reqLength = value.toIntOrNull() ?: 0
                    if (dpm.getPasswordMinimumLength(adminComponent) != reqLength) {
                        Log.w(TAG, "[POLICY ROLLBACK] Password min length mismatch. Forcing minLength=$reqLength")
                        dpm.setPasswordQuality(adminComponent, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING)
                        dpm.setPasswordMinimumLength(adminComponent, reqLength)
                    }
                }
                "disallowOutgoingCalls" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        toggleUserRestriction(dpm, adminComponent, UserManager.DISALLOW_OUTGOING_CALLS, isTrue)
                    }
                }
                "disallowConfigCredentials" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        // Secure boundaries: prevents child from injecting custom proxy/VPN certificates
                        toggleUserRestriction(dpm, adminComponent, UserManager.DISALLOW_CONFIG_CREDENTIALS, isTrue)
                    }
                }
                "disallowFactoryReset" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        toggleUserRestriction(dpm, adminComponent, UserManager.DISALLOW_FACTORY_RESET, isTrue)
                    }
                }
                "disallowSafeBoot" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        toggleUserRestriction(dpm, adminComponent, UserManager.DISALLOW_SAFE_BOOT, isTrue)
                    }
                }
                "disallowUsbFileTransfer" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        toggleUserRestriction(dpm, adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER, isTrue)
                    }
                }
                "disallowModifyAccounts" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        toggleUserRestriction(dpm, adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS, isTrue)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying EMM watchdog rule for $key", e)
        }
    }

    private fun toggleUserRestriction(dpm: DevicePolicyManager, admin: ComponentName, restrictionKey: String, enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val hasRestriction = dpm.getUserRestrictions(admin).getBoolean(restrictionKey, false)
                if (hasRestriction != enable) {
                    Log.w(TAG, "[EMM RESTRICTION] Toggling user restriction $restrictionKey -> $enable")
                    if (enable) {
                        dpm.addUserRestriction(admin, restrictionKey)
                    } else {
                        dpm.clearUserRestriction(admin, restrictionKey)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed toggling user restriction: $restrictionKey", e)
            }
        }
    }

    /**
     * Intercepts the OS package installation stream (Requirement 4).
     * If an update Targets our package name and selfUpdateMode is disabled,
     * the update is passively dropped and killed instantly.
     */
    private fun registerPackageInstallerCallback(policyDao: com.example.data.MdmPolicyDao) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val packageInstaller = packageManager.packageInstaller
                sessionCallback = object : PackageInstaller.SessionCallback() {
                    override fun onCreated(sessionId: Int) {
                        evaluateSession(sessionId, policyDao)
                    }

                    override fun onBadgingChanged(sessionId: Int) {}
                    override fun onActiveChanged(sessionId: Int, active: Boolean) {}
                    override fun onProgressChanged(sessionId: Int, progress: Float) {}
                    override fun onFinished(sessionId: Int, success: Boolean) {}
                }
                packageInstaller.registerSessionCallback(sessionCallback!!, android.os.Handler(android.os.Looper.getMainLooper()))
                Log.d(TAG, "[ENTERPRISE APP LIFECYCLE] PackageInstaller Session Callback Registered Successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register PackageInstaller Callback", e)
            }
        }
    }

    private fun evaluateSession(sessionId: Int, policyDao: com.example.data.MdmPolicyDao) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val packageInstaller = packageManager.packageInstaller
                val sessionInfo = packageInstaller.getSessionInfo(sessionId)
                val targetPackage = sessionInfo?.appPackageName
                if (targetPackage == packageName) {
                    serviceScope.launch {
                        val selfUpdateModePolicy = policyDao.getPolicyByKey("selfUpdateMode")
                        val selfUpdateMode = selfUpdateModePolicy?.value?.lowercase() == "true"
                        if (!selfUpdateMode) {
                            Log.w(TAG, "[ENTERPRISE APP LIFECYCLE] Intercepted unauthorized update session $sessionId targeting $packageName. selfUpdateMode is false! Abandoning session to drop update process passively.")
                            packageInstaller.abandonSession(sessionId)
                        } else {
                            Log.i(TAG, "[ENTERPRISE APP LIFECYCLE] selfUpdateMode is true. Corporate self-update session $sessionId allowed to proceed.")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating package installer session", e)
            }
        }
    }

    private fun unregisterPackageInstallerCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && sessionCallback != null) {
            try {
                packageManager.packageInstaller.unregisterSessionCallback(sessionCallback!!)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering package installer callback", e)
            }
        }
    }

    private fun getCurrentDayString(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            Calendar.SUNDAY -> "Sun"
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            Calendar.SATURDAY -> "Sat"
            else -> "Daily"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "FamilyGuard Policy Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DBS FamilyGuard Policy Enforcer")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        Log.d(TAG, "DBS FamilyGuard Enforcer Service Destroyed")
        enforcementJob?.cancel()
        unregisterPackageInstallerCallback()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
