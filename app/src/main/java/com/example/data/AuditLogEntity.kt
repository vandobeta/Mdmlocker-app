package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String, // e.g., "POLICY_ENFORCEMENT", "ADMIN_RECEIVER", "ACCESSIBILITY_BLOCK", "FIREBASE_SYNC"
    val message: String,
    val isSynced: Boolean = false
)
