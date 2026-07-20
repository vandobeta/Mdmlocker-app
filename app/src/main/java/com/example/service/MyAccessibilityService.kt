package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.AuditLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

class MyAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val TAG = "FamilyGuardAccess"
        @Volatile
        var instance: MyAccessibilityService? = null
        @Volatile
        var isLockScreenActive: Boolean = false
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (isLockScreenActive) {
            val keyCode = event.keyCode
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK || 
                keyCode == android.view.KeyEvent.KEYCODE_HOME || 
                keyCode == android.view.KeyEvent.KEYCODE_APP_SWITCH) {
                
                Log.i(TAG, "Intercepted and absorbed navigation key $keyCode because Device is Locked.")
                
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    launchLockScreen()
                }
                return true // Consume key event entirely!
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "DBS FamilyGuard Accessibility Service Created")
    }

    fun performBackAction(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performHomeAction(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return // Don't block ourselves!

        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val sharedPrefs = getSharedPreferences("familyguard_prefs", Context.MODE_PRIVATE)
            val isProvisioned = sharedPrefs.getBoolean("is_provisioned", false)

            val policies = db.mdmPolicyDao().getAllPolicies()
            val isParentLocked = policies.any { it.key == "parentLockActive" && it.value.lowercase() == "true" }
            val isLocked = !isProvisioned || isParentLocked

            // Defensive: if the user attempts to disable this Accessibility Service,
            // block the settings panel and restore the service.
            if (isLocked && isAccessibilitySettingsPackage(packageName)) {
                logBlock(db, "ACCESSIBILITY_BLOCK", "Attempted to open Accessibility settings. Blocked.")
                launchLockScreen()
                return@launch
            }

            // Defensive: if the user attempts to open developer options or ADB settings,
            // block them when under lockdown.
            if (isLocked && isDevToolsPackage(packageName)) {
                logBlock(db, "ACCESSIBILITY_BLOCK", "Attempted to open Developer Options/ADB settings. Blocked.")
                launchLockScreen()
                return@launch
            }

            // Defensive: block uninstall/package installer UI when locked.
            if (isLocked && isPackageInstallerPackage(packageName)) {
                logBlock(db, "ACCESSIBILITY_BLOCK", "Attempted to open Package Installer/Uninstaller. Blocked.")
                launchLockScreen()
                return@launch
            }

            // Prevent launching other apps if not provisioned OR if parent lock is active!
            if (!isProvisioned || isParentLocked) {
                if (packageName == "com.android.systemui") {
                    // Force-collapse the notification shade / Quick Settings drawer
                    @Suppress("DEPRECATION")
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                }

                // If device is in lockdown, only allow our own app, system UI, and dialer/phone apps
                val allowedPackages = listOf(
                    this@MyAccessibilityService.packageName,
                    "android",
                    "com.android.systemui"
                )
                val isDialer = packageName.contains("dialer", ignoreCase = true) ||
                               packageName.contains("phone", ignoreCase = true) ||
                               packageName.contains("telephony", ignoreCase = true) ||
                               packageName.contains("contacts", ignoreCase = true) ||
                               packageName == "com.android.server.telecom"

                if (packageName !in allowedPackages && !isDialer) {
                    val logType = if (!isProvisioned) "ACCESS_BLOCKED" else "POLICY_ENFORCEMENT"
                    val logMsg = if (!isProvisioned) {
                        "Device not authorised yet. Blocked launch: $packageName"
                    } else {
                        "Device locked by parent. Blocked launch: $packageName"
                    }

                    logBlock(db, logType, logMsg)
                    launchLockScreen()
                    return@launch
                }
                return@launch
            }

            // Normal Schedule Evaluation
            val schedules = db.scheduleDao().getAllSchedules()
            val calendar = Calendar.getInstance()
            val currentDayOfWeek = getCurrentDayString(calendar.get(Calendar.DAY_OF_WEEK))
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)
            val currentTimeInMinutes = currentHour * 60 + currentMinute

            for (schedule in schedules) {
                if (!schedule.isEnabled) continue

                val mappedPackages = mapAppNameToPackages(schedule.targetName)
                if (mappedPackages.contains(packageName) || packageName == schedule.targetName) {
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

                        if (!isCurrentlyInInterval) {
                            logBlock(db, "ACCESSIBILITY_BLOCK", "Schedule limit hit. Blocking app launch: $packageName")
                            launchLockScreen()
                            break
                        }
                    } else {
                        logBlock(db, "ACCESSIBILITY_BLOCK", "Schedule limit hit (Day mismatch). Blocking app launch: $packageName")
                        launchLockScreen()
                        break
                    }
                }
            }

            // Also monitor security configuration attempts
            val configPackages = listOf("com.android.settings", "com.google.android.packageinstaller", "com.android.vpndialogs")
            if (configPackages.contains(packageName)) {
                db.auditLogDao().insertLog(
                    AuditLogEntity(
                        eventType = "ACCESSIBILITY_MONITOR",
                        message = "Settings/System configuration app accessed: $packageName",
                        isSynced = false
                    )
                )
            }
        }
    }

    private fun isAccessibilitySettingsPackage(packageName: String): Boolean {
        val settingsAccessibility = listOf(
            "com.android.settings",
            "com.google.android.gms",
            "com.android.settings.Settings\$AccessibilitySettingsActivity"
        )
        return settingsAccessibility.any { packageName == it || packageName.startsWith(it) }
    }

    private fun isDevToolsPackage(packageName: String): Boolean {
        return packageName == "com.android.settings" && packageName.contains("development", ignoreCase = true) ||
               packageName == "com.android.settings" && packageName.contains("aboutphone", ignoreCase = true) ||
               packageName.contains("adb", ignoreCase = true) ||
               packageName.contains("developer", ignoreCase = true)
    }

    private fun isPackageInstallerPackage(packageName: String): Boolean {
        return packageName.contains("packageinstaller", ignoreCase = true) ||
               packageName.contains("uninstaller", ignoreCase = true) ||
               packageName == "com.google.android.packageinstaller" ||
               packageName == "com.android.packageinstaller"
    }

    private suspend fun logBlock(db: AppDatabase, type: String, message: String) {
        db.auditLogDao().insertLog(
            AuditLogEntity(
                eventType = type,
                message = message,
                isSynced = false
            )
        )
    }

    private fun launchLockScreen() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(
                applicationContext,
                "Access restricted by DBS FamilyGuard",
                Toast.LENGTH_SHORT
            ).show()
            
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        }
    }

    private fun mapAppNameToPackages(name: String): List<String> {
        val lower = name.trim().lowercase()
        return when {
            lower.contains("youtube") -> listOf("com.google.android.youtube", "com.google.android.apps.youtube.kids")
            lower.contains("minecraft") -> listOf("com.mojang.minecraftpe")
            lower.contains("tiktok") -> listOf("com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go")
            lower.contains("games") -> listOf("com.king.candycrushsaga", "com.supercell.clashofclans", "com.roblox.client")
            lower.contains("social") || lower.contains("facebook") || lower.contains("instagram") -> 
                listOf("com.facebook.katana", "com.instagram.android", "com.twitter.android", "com.snapchat.android")
            else -> listOf(name)
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

    override fun onInterrupt() {
        Log.d(TAG, "DBS FamilyGuard Accessibility Service Interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "DBS FamilyGuard Accessibility Service Connected")
        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            db.auditLogDao().insertLog(
                AuditLogEntity(
                    eventType = "ACCESSIBILITY_SERVICE",
                    message = "Accessibility service successfully connected and guarding active window states.",
                    isSynced = false
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "DBS FamilyGuard Accessibility Service Destroyed")
    }
}
