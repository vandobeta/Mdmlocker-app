package com.example.network

import com.example.data.FirebaseDataPayload
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

interface FirebaseMockApi {
    @GET("devices/{deviceId}.json")
    suspend fun getDeviceData(@Path("deviceId") deviceId: String): FirebaseDataPayload?

    @PUT("devices/{deviceId}.json")
    suspend fun updateDeviceData(@Path("deviceId") deviceId: String, @Body payload: FirebaseDataPayload): FirebaseDataPayload
}
