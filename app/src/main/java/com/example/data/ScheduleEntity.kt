package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val targetName: String, // e.g. "YouTube", "Instagram", "Games", "Entertainment"
    val isCategory: Boolean, // true if category, false if specific app name
    val startHour: Int, // 0-23
    val startMinute: Int, // 0-59
    val endHour: Int, // 0-23
    val endMinute: Int, // 0-59
    val daysOfWeek: String, // Comma-separated: "Mon,Tue,Wed,Thu,Fri,Sat,Sun" or "Daily"
    val isEnabled: Boolean = true,
    val actionType: String = "SUSPEND", // "SUSPEND", "LOCK", "DISABLE", "UNINSTALL", "BLACKLIST"
    val actionTarget: String = "" // Package name, app name, or empty
)
