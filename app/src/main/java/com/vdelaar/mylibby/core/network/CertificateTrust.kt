package com.vdelaar.mylibby.core.network

import okhttp3.internal.tls.OkHostnameVerifier
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** A server certificate the user may choose to trust, identified by its SHA-256 fingerprint. */
data class CertInfo(val host: String, val sha256: String, val subject: String, val issuer: String, val validUntil: String) {
    /** AB:CD:… for display. */
    val fingerprint: String get() = sha256.uppercase().chunked(2).joinToString(":")
}

fun X509Certificate.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }

/**
 * Normal certificate checks, plus certificates the user explicitly confirmed (self-signed home servers).
 * A certificate is accepted only if the system trusts it or its exact fingerprint was approved by the user;
 * nothing else is ever let through.
 */
class PinnedTrust(private val pins: () -> Set<String>) {

    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(null as KeyStore?)
        trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val leaf = chain?.firstOrNull() ?: throw e
                if (leaf.sha256() !in pins()) throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    val sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), null) }.socketFactory

    /** The normal host name check; for a confirmed certificate the fingerprint match replaces it (home servers are often reached by IP). */
    val hostnameVerifier = HostnameVerifier { host: String, session: SSLSession ->
        OkHostnameVerifier.verify(host, session) || run {
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            leaf != null && leaf.sha256() in pins()
        }
    }
}

/**
 * Reads the certificate a server presents without trusting it and without sending any data,
 * so the user can compare the fingerprint before deciding.
 */
fun fetchServerCertificate(host: String, port: Int): CertInfo? {
    var seen: X509Certificate? = null
    val capture = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client certificates are not used")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            seen = chain?.firstOrNull()
            throw CertificateException("certificate captured for review, not trusted")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(capture), null) }
    runCatching {
        (context.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.soTimeout = 8_000
            socket.connect(InetSocketAddress(host, port), 8_000)
            socket.startHandshake()
        }
    }
    val leaf = seen ?: return null
    return CertInfo(
        host = host,
        sha256 = leaf.sha256(),
        subject = leaf.subjectX500Principal.name,
        issuer = leaf.issuerX500Principal.name,
        validUntil = leaf.notAfter.toString(),
    )
}
