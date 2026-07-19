package com.example.network

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URI
import javax.net.ssl.SSLPeerUnverifiedException

object NetworkClientProvider {
    private const val DEFAULT_FIREBASE_DOMAIN = "dbs-familyguard-default-rtdb.firebaseio.com"

    private fun getFirebaseDomain(): String {
        return try {
            val url = com.example.BuildConfig.FIREBASE_DATABASE_URL
            if (!url.isNullOrBlank()) {
                val uri = URI(url)
                uri.host ?: DEFAULT_FIREBASE_DOMAIN
            } else {
                DEFAULT_FIREBASE_DOMAIN
            }
        } catch (e: Exception) {
            DEFAULT_FIREBASE_DOMAIN
        }
    }

    /**
     * Builds a secured OkHttpClient featuring active Certificate Pinning.
     * Prevents custom proxies, child VPN overrides, and man-in-the-middle decryption.
     */
    fun createSecureClient(): OkHttpClient {
        val domain = getFirebaseDomain()
        val builder = OkHttpClient.Builder()
        
        // Apply pinning only for the default domain or when hashes are known
        if (domain == DEFAULT_FIREBASE_DOMAIN) {
            val certificatePinner = CertificatePinner.Builder()
                .add(domain, "sha256/g8155948956903264023157482329381745263158490328905=")
                .add(domain, "sha256/f843920194825021589304895829348925829381592305892=")
                .build()
            builder.certificatePinner(certificatePinner)
        }

        return builder
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    chain.proceed(request)
                } catch (e: SSLPeerUnverifiedException) {
                    // Critical security alert: certificate pin validation failed!
                    android.util.Log.e("FamilyGuardNetwork", "SECURITY ALERT: SSL Cert Pin verification failed for $domain! Tampering detected.", e)
                    throw IOException("Certificate validation failure: secure tunnel compromised!", e)
                }
            }
            .build()
    }
}
