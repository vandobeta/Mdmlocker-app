package com.example.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Dynamically retrieve the database URL configured in AI Studio Secrets or default to the default Firebase DB
    private val BASE_URL: String by lazy {
        var url = com.example.BuildConfig.FIREBASE_DATABASE_URL
        if (url.isNullOrBlank()) {
            url = "https://dbs-familyguard-default-rtdb.firebaseio.com/"
        }
        if (!url.endsWith("/")) {
            url += "/"
        }
        url
    }

    val api: FirebaseMockApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(FirebaseMockApi::class.java)
    }
}
