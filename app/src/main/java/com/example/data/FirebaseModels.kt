package com.example.data

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class FirebaseSchedule(
    val id: String? = null,
    val targetName: String,
    val isCategory: Boolean,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: String,
    val isEnabled: Boolean = true,
    val actionType: String = "SUSPEND", // "SUSPEND", "LOCK", "DISABLE", "UNINSTALL", "BLACKLIST"
    val actionTarget: String = "" // Package name, app name, or empty
)

@JsonClass(generateAdapter = true)
data class FirebasePolicy(
    val key: String,
    val value: String,
    val valueType: String,
    val permissionFlag: String
)

@JsonClass(generateAdapter = true)
data class FirebaseCommand(
    val id: String,
    val commandType: String, // "lock", "suspend", "disable", "uninstall", "blacklist_app", "blink_screen"
    val target: String? = null, // e.g. package name, app name, or custom message/title
    val value: String? = null,  // e.g. custom message body
    val timestamp: Long,
    val executed: Boolean = false
)

@JsonClass(generateAdapter = true)
data class FirebaseAuditLog(
    val id: Int,
    val timestamp: Long,
    val eventType: String,
    val message: String
)

@JsonClass(generateAdapter = true)
data class FirebaseDataPayload(
    val deviceId: String,
    val schedules: Map<String, FirebaseSchedule>? = null,
    val policies: Map<String, FirebasePolicy>? = null,
    val auditLogs: Map<String, FirebaseAuditLog>? = null,
    val commands: Map<String, FirebaseCommand>? = null,
    val systemSettings: Map<String, String>? = null,
    val globalSettings: Map<String, String>? = null,
    val secureSettings: Map<String, String>? = null,
    val systemTable: Map<String, String>? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
