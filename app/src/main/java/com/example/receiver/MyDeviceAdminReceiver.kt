package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.example.data.AppDatabase
import com.example.data.AuditLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "FamilyGuardAdmin"
    }

    private val receiverScope = CoroutineScope(Dispatchers.Default)

    private fun logEvent(context: Context, type: String, message: String) {
        receiverScope.launch {
            try {
                val db = AppDatabase.getDatabase(context.applicationContext)
                db.auditLogDao().insertLog(
                    AuditLogEntity(
                        eventType = type,
                        message = message,
                        isSynced = false
                    )
                )
                Log.d(TAG, "Logged event: [$type] $message")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write audit log in receiver", e)
            }
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "DBS FamilyGuard Device Admin Enabled")
        Toast.makeText(context, "DBS FamilyGuard Device Admin Enabled", Toast.LENGTH_SHORT).show()
        logEvent(context, "ADMIN_RECEIVER", "DBS FamilyGuard Device Admin has been authorized and enabled.")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "DBS FamilyGuard Device Admin Disabled")
        Toast.makeText(context, "Warning: DBS FamilyGuard Device Admin Disabled", Toast.LENGTH_LONG).show()
        logEvent(context, "ADMIN_RECEIVER", "WARNING: DBS FamilyGuard Device Admin has been disabled or revoked by user.")
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        Log.d(TAG, "Device password changed successfully")
        logEvent(context, "ADMIN_SECURITY", "Device lock screen credentials modified or added.")
    }

    override fun onPasswordExpiring(context: Context, intent: Intent) {
        super.onPasswordExpiring(context, intent)
        Log.d(TAG, "Device password is about to expire")
        logEvent(context, "ADMIN_SECURITY", "Device lock screen credential is about to expire.")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "Device password attempt failed")
        logEvent(context, "ADMIN_SECURITY_ALERT", "Failed lock screen unlock attempt detected.")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        Log.i(TAG, "Device password attempt succeeded")
        logEvent(context, "ADMIN_SECURITY", "Successful device lock screen unlock.")
    }
}
