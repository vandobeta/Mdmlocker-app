package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MdmPolicyDao {
    @Query("SELECT * FROM mdm_policies ORDER BY `key` ASC")
    fun getAllPoliciesFlow(): Flow<List<MdmPolicyEntity>>

    @Query("SELECT * FROM mdm_policies")
    suspend fun getAllPolicies(): List<MdmPolicyEntity>

    @Query("SELECT * FROM mdm_policies WHERE `key` = :key LIMIT 1")
    suspend fun getPolicyByKey(key: String): MdmPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePolicy(policy: MdmPolicyEntity)

    @Delete
    suspend fun deletePolicy(policy: MdmPolicyEntity)

    @Query("DELETE FROM mdm_policies")
    suspend fun clearAllPolicies()
}
