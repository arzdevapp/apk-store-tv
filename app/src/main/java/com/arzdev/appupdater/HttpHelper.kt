package com.arzdev.appupdater

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Shared HTTPS helper with a DNS fallback.
 *
 * The funnel hostname (apk-installer.tailc8cd81.ts.net) is served by Tailscale
 * Funnel with stable public IPv4s. The Firestick runs NetGuard, a local VPN that
 * per-app filters/caches DNS — it intermittently returns "Name or service not
 * known" for the host even though public DNS + AdGuard both have it. To make App
 * Updater immune to that, requests try ordinary DNS first; on failure they fall
 * back to connecting DIRECTLY to the funnel's known public IPs while still sending
 * the real Host header + SNI, so TLS certificate verification still passes.
 *
 * SECURITY: the default system TrustManager is always used for chain validation
 * (no trust-all). For the IP fallback the hostname check is relaxed but still
 * verifies the PRESENTED CERTIFICATE's subjectAltNames actually contain the real
 * hostname before accepting — a cert for some other host is rejected.
 *
 * Requiring SNI: Tailscale Funnel drops TLS handshakes without a server-name
 * (verified 2026-08-29 — UNEXPECTED_EOF without SNI). SocketFactorySniffer
 * injects the real hostname into the SSLParameters regardless of the URL host.
 *
 * Fallback IPs = persistent public A records of the funnel (verified 2026-08-29
 * via Cloudflare/Google DoH + direct TLS 200 — do not change without re-verifying).
 */
object HttpHelper {
    private const val HOST = "apk-installer.tailc8cd81.ts.net"
    private val FALLBACK_IPS = arrayOf("208.111.35.209", "208.111.34.11")
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 20000

    private val verifier = HostnameVerifier { _, session: SSLSession ->
        val certs = session.peerCertificates
        if (certs.isEmpty() || certs[0] !is X509Certificate) return@HostnameVerifier false
        val cert = certs[0] as X509Certificate
        try {
            val san = cert.subjectAlternativeNames ?: return@HostnameVerifier false
            for (entry in san) {
                if (entry.size >= 2 && entry[0] == 2 && HOST.equals(entry[1], ignoreCase = true)) {
                    return@HostnameVerifier true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Open a configured HttpsURLConnection for [path]; tries DNS then IP fallback. */
    fun openHttps(path: String): HttpURLConnection {
        try {
            val conn = URL("https://$HOST$path").openConnection() as HttpsURLConnection
            configure(conn, viaIp = false)
            return conn
        } catch (e: Exception) {
            // DNS/hostname path failed — use IP fallback.
        }
        return openViaIp(path)
    }

    private fun openViaIp(path: String): HttpURLConnection {
        var lastErr: Exception? = null
        for (ip in FALLBACK_IPS) {
            try {
                val conn = URL("https://$ip$path").openConnection() as HttpsURLConnection
                conn.requestProperty("Host", HOST)
                configure(conn, viaIp = true)
                return conn
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw ConnectException("Cannot reach $HOST: ${lastErr?.message ?: "all fallback IPs failed"}")
    }

    private fun configure(conn: HttpsURLConnection, viaIp: Boolean) {
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        if (viaIp) {
            conn.hostnameVerifier = verifier
            conn.sslSocketFactory = SniSocketFactory(HOST)
        }
    }
}

/**
 * Wraps the system socket factory and forces SNI = [host] on every handshake,
 * because HttpsURLConnection derives SNI from the URL host (an IP in the fallback
 * path) and Tailscale Funnel declines handshakes without a hostname. CA chain
 * validation still uses the system trust store.
 */
private class SniSocketFactory(private val host: String) : SSLSocketFactory() {
    private val delegate = HttpsURLConnection.getDefaultSSLSocketFactory()

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(s: Socket, hostname: String, port: Int, autoClose: Boolean): Socket {
        return base(delegate.createSocket(s, hostname, port, autoClose))
    }
    override fun createSocket(host: String, port: Int): Socket = base(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        base(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = base(delegate.createSocket(host, port))
    override fun createSocket(addr: InetAddress, port: Int, localAddr: InetAddress, localPort: Int): Socket =
        base(delegate.createSocket(addr, port, localAddr, localPort))

    private fun base(socket: Socket): Socket {
        if (socket is SSLSocket) {
            try {
                val p = SSLParameters()
                p.serverNames = listOf(SNIHostName(host))
                socket.sslParameters = p
            } catch (_: Exception) {
                // SNI best-effort
            }
        }
        return socket
    }
}