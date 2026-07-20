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

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        return try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(TAG, "Failed performBackAction", e)
            false
        }
    }

    fun performHomeAction(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            Log.e(TAG, "Failed performHomeAction", e)
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return // Don't block ourselves!

        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val sharedPrefs = getSharedPreferences("familyguard_prefs", Context.MODE_PRIVATE)
                val isProvisioned = sharedPrefs.getBoolean("is_provisioned", false)

                val policies = db.mdmPolicyDao().getAllPolicies()
                val isParentLocked = isProvisioned && policies.any { it.key == "parentLockActive" && it.value.lowercase() == "true" }

                // Prevent launching other apps if not provisioned OR if parent lock is active!
                if (!isProvisioned || isParentLocked) {
                    if (packageName == "com.android.systemui") {
                        // Force-collapse the notification shade / Quick Settings drawer
                        @Suppress("DEPRECATION")
                        try {
                            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send ACTION_CLOSE_SYSTEM_DIALOGS (restricted on SDK 31+): ${e.message}")
                        }
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
                        Log.w(TAG, "[ACCESSIBILITY BLOCK] Device locked. Blocking app launch: $packageName")
                        
                        val logType = if (!isProvisioned) "ACCESS_BLOCKED" else "POLICY_ENFORCEMENT"
                        val logMsg = if (!isProvisioned) {
                            "Device not authorised yet. Blocked launch: $packageName"
                        } else {
                            "Device locked by parent. Blocked launch: $packageName"
                        }

                        // Save to local logs
                        try {
                            db.auditLogDao().insertLog(
                                AuditLogEntity(
                                    eventType = logType,
                                    message = logMsg,
                                    isSynced = false
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save audit log", e)
                        }

                        // Return to home screen or force launch our app
                        launchLockScreen()
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

                    // Check if package matches schedule
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

                            // If NOT currently in interval, access is restricted!
                            if (!isCurrentlyInInterval) {
                                Log.w(TAG, "[ACCESSIBILITY BLOCK] Schedule limit hit. Blocking app launch: $packageName")
                                
                                try {
                                    db.auditLogDao().insertLog(
                                        AuditLogEntity(
                                            eventType = "ACCESSIBILITY_BLOCK",
                                            message = "Block rule active for ${schedule.targetName}. Blocked launch: $packageName",
                                            isSynced = false
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to save audit log", e)
                                }

                                launchLockScreen()
                                break
                            }
                        } else {
                            // Day does not match, so app is blocked
                            Log.w(TAG, "[ACCESSIBILITY BLOCK] Schedule limit hit (Day mismatch). Blocking app launch: $packageName")
                            try {
                                db.auditLogDao().insertLog(
                                    AuditLogEntity(
                                        eventType = "ACCESSIBILITY_BLOCK",
                                        message = "Block rule active for ${schedule.targetName} (Day mismatch). Blocked launch: $packageName",
                                        isSynced = false
                                    )
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save audit log", e)
                            }
                            launchLockScreen()
                            break
                        }
                    }
                }

                // Also monitor security configuration attempts
                val configPackages = listOf("com.android.settings", "com.google.android.packageinstaller", "com.android.vpndialogs")
                if (configPackages.contains(packageName)) {
                    try {
                        db.auditLogDao().insertLog(
                            AuditLogEntity(
                                eventType = "ACCESSIBILITY_MONITOR",
                                message = "Settings/System configuration app accessed: $packageName",
                                isSynced = false
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save audit log", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing accessibility event", e)
            }
        }
    }

    private fun launchLockScreen() {
        // Must be run on main thread safely
        serviceScope.launch(Dispatchers.Main) {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                Log.e(TAG, "Failed performGlobalAction GLOBAL_ACTION_HOME", e)
            }
            
            try {
                Toast.makeText(
                    applicationContext,
                    "Access restricted by DBS FamilyGuard",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show Toast", e)
            }
            
            try {
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MainActivity", e)
            }
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
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                db.auditLogDao().insertLog(
                    AuditLogEntity(
                        eventType = "ACCESSIBILITY_SERVICE",
                        message = "Accessibility service successfully connected and guarding active window states.",
                        isSynced = false
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log service connection", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "DBS FamilyGuard Accessibility Service Destroyed")
    }
}
