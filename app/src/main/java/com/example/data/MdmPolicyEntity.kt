package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mdm_policies")
data class MdmPolicyEntity(
    @PrimaryKey val key: String,
    val value: String,
    val valueType: String, // "boolean", "int", "string"
    val permissionFlag: String // "GREY_OUT", "PERMIT_KID"
)
