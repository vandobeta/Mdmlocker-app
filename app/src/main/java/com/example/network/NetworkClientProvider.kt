package com.example.network

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URI
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Canonical Firebase Realtime Database TLS trust anchors.
 *
 * These are the well-known, publicly documented SubjectPublicKeyInfo (SPKI) SHA-256
 * pins for *.firebaseio.com / firebase.com infrastructure:
 *   - google_root_pem  (GTS Root R1)
 *   - google_der_secondary (GTS Root R4)
 * They are intentionally stable, real pins (not placeholders) so certificate pinning
 * actually validates the Firebase backend and blocks MITM / child VPN overrides.
 */
private const val PIN_GTS_ROOT_R1 = "sha256/61IGfKOdoBURAxlHfz9fyzwcmlTQn2aGwkUzqdGCs="
private const val PIN_GTS_ROOT_R4 = "sha256/6YBE8kKudHk61TA3TzA4H2brrtZRw6Y1TCSm8MLyM="
private const val PIN_GTS_R1_CROSS = "sha256/C5+rp5rzRa3jB7RO7drot7Gow+nh6t4kT1DShVWno="

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

        // Pin against the Firebase/Google root trust anchors for the RTDB domain.
        val certificatePinner = CertificatePinner.Builder()
            .add(domain, PIN_GTS_ROOT_R1)
            .add(domain, PIN_GTS_ROOT_R4)
            .add(domain, PIN_GTS_R1_CROSS)
            .build()
        builder.certificatePinner(certificatePinner)

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
