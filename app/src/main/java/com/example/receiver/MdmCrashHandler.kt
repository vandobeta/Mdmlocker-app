package com.example.receiver

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.AuditLogEntity
import com.example.data.MdmPolicyEntity
import com.example.service.ScheduleEnforcerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MdmCrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e("MdmCrashHandler", "CRASH DETECTED on thread ${thread.name}: ${throwable.message}", throwable)
        
        try {
            val db = AppDatabase.getDatabase(context.applicationContext)
            
            // Enforce strict policies to prevent bypass due to crash
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    db.auditLogDao().insertLog(
                        AuditLogEntity(
                            eventType = "CRASH_RESTRICTION",
                            message = "CRASH DETECTED: ${throwable.localizedMessage}. System locked for security.",
                            isSynced = false
                        )
                    )
                    
                    // Force parent Lock Screen restriction immediately upon crash
                    db.mdmPolicyDao().insertOrUpdatePolicy(
                        MdmPolicyEntity(
                            key = "parentLockActive",
                            value = "true",
                            valueType = "boolean",
                            permissionFlag = "GREY_OUT"
                        )
                    )
                    Log.i("MdmCrashHandler", "Crash restrictions successfully written to local DB.")
                } catch (dbEx: Exception) {
                    Log.e("MdmCrashHandler", "Failed to write crash lock policy to DB", dbEx)
                }
            }
            
            // Try starting ScheduleEnforcerService to re-enforce lock and block app access
            ScheduleEnforcerService.start(context)
            
        } catch (e: Exception) {
            Log.e("MdmCrashHandler", "Error inside crash watchdog handler", e)
        }

        // Forward to system default uncaught exception handler so app can terminate correctly
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
