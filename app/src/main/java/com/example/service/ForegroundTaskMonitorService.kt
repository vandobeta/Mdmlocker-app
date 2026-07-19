package com.example.service

import android.app.ActivityManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.MainActivity
import com.example.data.AppDatabase
import kotlinx.coroutines.*

class ForegroundTaskMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null

    companion object {
        private const val TAG = "ForegroundTaskMonitor"

        fun start(context: Context) {
            try {
                val intent = Intent(context, ForegroundTaskMonitorService::class.java)
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, ForegroundTaskMonitorService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ForegroundTaskMonitorService Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ForegroundTaskMonitorService Started")
        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val sharedPrefs = getSharedPreferences("familyguard_prefs", Context.MODE_PRIVATE)

            while (isActive) {
                try {
                    val isProvisioned = sharedPrefs.getBoolean("is_provisioned", false)
                    val policies = db.mdmPolicyDao().getAllPolicies()
                    val isParentLocked = policies.any { it.key == "parentLockActive" && it.value.lowercase() == "true" }
                    val isLockScreenActive = !isProvisioned || isParentLocked

                    if (isLockScreenActive) {
                        val fgPackage = getForegroundPackage()
                        if (fgPackage != null && fgPackage != packageName) {
                            val isAllowed = fgPackage == "android" || fgPackage == "com.android.systemui"
                            val isDialer = fgPackage.contains("dialer", ignoreCase = true) ||
                                           fgPackage.contains("phone", ignoreCase = true) ||
                                           fgPackage.contains("telephony", ignoreCase = true) ||
                                           fgPackage.contains("contacts", ignoreCase = true) ||
                                           fgPackage == "com.android.server.telecom"

                            if (!isAllowed && !isDialer) {
                                Log.i(TAG, "User exited lockscreen. Foreground app: $fgPackage. Re-applying system overlay...")
                                val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                }
                                startActivity(launchIntent)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in monitoring loop: ${e.message}", e)
                }
                delay(1000) // check every second
            }
        }
    }

    private fun getForegroundPackage(): String? {
        try {
            // 1. Try UsageStatsManager (most reliable on modern Android)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val time = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time)
                if (stats != null && stats.isNotEmpty()) {
                    val sorted = stats.sortedBy { it.lastTimeUsed }
                    return sorted.last().packageName
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "UsageStatsManager failed: ${e.message}")
        }

        try {
            // 2. Try ActivityManager running tasks fallback
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(1)
            if (tasks.isNotEmpty()) {
                return tasks[0].topActivity?.packageName
            }
        } catch (e: Exception) {
            Log.v(TAG, "ActivityManager failed: ${e.message}")
        }

        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        Log.d(TAG, "ForegroundTaskMonitorService Destroyed")
    }
}
