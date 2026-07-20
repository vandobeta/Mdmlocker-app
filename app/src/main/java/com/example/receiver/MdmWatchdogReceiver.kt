package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.MdmPolicyEntity
import com.example.data.AuditLogEntity
import com.example.service.ScheduleEnforcerService
import com.example.service.ForegroundTaskMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MdmWatchdogReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MdmWatchdogReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Watchdog triggered by action: $action")
        
        try {
            // Keep the policy enforcer service running
            ScheduleEnforcerService.start(context)
            ForegroundTaskMonitorService.start(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start services on broadcast trigger: ${e.message}", e)
        }

        val pendingResult = goAsync()
        val db = AppDatabase.getDatabase(context.applicationContext)
        val sharedPrefs = context.getSharedPreferences("familyguard_prefs", Context.MODE_PRIVATE)

        CoroutineScope(Dispatchers.Default).launch {
            try {
                // If boot completed, ensure enforcer service runs cleanly
                if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
                    Log.i(TAG, "Device reboot detected. Re-enforcing corporate schedules quietly.")
                    db.auditLogDao().insertLog(
                        AuditLogEntity(
                            eventType = "POLICY_ENFORCEMENT",
                            message = "Device restarted. MDM services successfully initialized and schedules rescheduled quietly.",
                            isSynced = false
                        )
                    )
                }

                // If time tampering broadcast is detected, lock down immediately
                if (action == Intent.ACTION_TIME_CHANGED || action == "android.intent.action.TIME_SET" || action == "android.intent.action.TIMEZONE_CHANGED") {
                    Log.w(TAG, "Time manipulation detected via broadcast action: $action. Instantly throwing device into lockdown!")
                    
                    // Force the parent lock to true
                    db.mdmPolicyDao().insertOrUpdatePolicy(
                        MdmPolicyEntity(
                            key = "parentLockActive",
                            value = "true",
                            valueType = "boolean",
                            permissionFlag = "GREY_OUT"
                        )
                    )
                    
                    db.auditLogDao().insertLog(
                        AuditLogEntity(
                            eventType = "TAMPER_ALERT",
                            message = "WARNING: Manual time setting or timezone modification detected. Active lockscreen triggered instantly.",
                            isSynced = false
                        )
                    )

                    // Launch MainActivity lockscreen instantly
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    context.startActivity(launchIntent)
                    pendingResult.finish()
                    return@launch
                }

                val isProvisioned = sharedPrefs.getBoolean("is_provisioned", false)
                val policies = db.mdmPolicyDao().getAllPolicies()
                val isParentLocked = policies.any { it.key == "parentLockActive" && it.value.lowercase() == "true" }

                if (!isProvisioned || isParentLocked) {
                    Log.i(TAG, "Lock screen is active. Regaining screen overlay on action: $action")
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    context.startActivity(launchIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking lock state in Watchdog Receiver: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
