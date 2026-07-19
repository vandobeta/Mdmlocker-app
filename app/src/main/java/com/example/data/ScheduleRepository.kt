package com.example.data

import kotlinx.coroutines.flow.Flow

class ScheduleRepository(private val scheduleDao: ScheduleDao) {
    val allSchedules: Flow<List<ScheduleEntity>> = scheduleDao.getAllSchedulesFlow()

    suspend fun getAllSchedulesList(): List<ScheduleEntity> = scheduleDao.getAllSchedules()

    suspend fun insertOrUpdate(schedule: ScheduleEntity) {
        scheduleDao.insertOrUpdateSchedule(schedule)
    }

    suspend fun delete(schedule: ScheduleEntity) {
        scheduleDao.deleteSchedule(schedule)
    }

    suspend fun clearAll() {
        scheduleDao.clearAllSchedules()
    }
}
