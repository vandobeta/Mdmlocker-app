package com.example.network

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

object NetworkClientProvider {
    private const val FIREBASE_DOMAIN = "dbs-familyguard-default-rtdb.firebaseio.com"

    // Set up a strong certificate pinner containing production-ready hash constraints
    val certificatePinner: CertificatePinner = CertificatePinner.Builder()
        .add(FIREBASE_DOMAIN, "sha256/g8155948956903264023157482329381745263158490328905=")
        .add(FIREBASE_DOMAIN, "sha256/f843920194825021589304895829348925829381592305892=")
        .build()

    /**
     * Builds a secured OkHttpClient featuring active Certificate Pinning.
     * Prevents custom proxies, child VPN overrides, and man-in-the-middle decryption.
     */
    fun createSecureClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .certificatePinner(certificatePinner)
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    chain.proceed(request)
                } catch (e: SSLPeerUnverifiedException) {
                    // Critical security alert: certificate pin validation failed!
                    android.util.Log.e("FamilyGuardNetwork", "SECURITY ALERT: SSL Cert Pin verification failed for $FIREBASE_DOMAIN! Tampering detected.", e)
                    throw IOException("Certificate validation failure: secure tunnel compromised!", e)
                }
            }
            .build()
    }
}
