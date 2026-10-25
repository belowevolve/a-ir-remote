package belowevolve.airremote

import android.content.Context
import android.net.Network
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

internal data class CompanionEndpoint(val host: String, val port: Int, val fingerprint: String, val token: String) {
    fun code() = "airremote://$host:$port/$fingerprint/$token"

    fun open(network: Network, kind: String, onSocket: (SSLSocket) -> Unit = {}): SSLSocket {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = error("Not a TLS server")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val certificate = chain.firstOrNull() ?: throw java.security.cert.CertificateException("Нет сертификата ПК")
                val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                val expected = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (!MessageDigest.isEqual(digest, expected)) throw java.security.cert.CertificateException("Сертификат ПК изменился. Выполни сопряжение заново")
                certificate.checkValidity()
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            onSocket(socket)
            network.bindSocket(socket)
            socket.tcpNoDelay = true
            socket.sendBufferSize = 4096
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(lanAddress(host), port), 3000)
            socket.startHandshake()
            val hello = JSONObject().put("token", token).put("kind", kind).toString() + "\n"
            socket.outputStream.write(hello.toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
            val reply = readLine(socket.inputStream)
            check(reply == "OK") { if (reply.startsWith("ERROR ")) reply.removePrefix("ERROR ") else "ПК отклонил подключение" }
            return socket
        } catch (e: Exception) { runCatching { socket.close() }; throw e }
    }
    companion object {
        fun parse(value: String): CompanionEndpoint {
            val uri = runCatching { URI(value.trim()) }.getOrElse { throw IllegalArgumentException("Неверный код подключения") }
            val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            require(uri.scheme == "airremote" && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                uri.port in 1..65535 && parts.size == 2 && parts[0].matches(Regex("[a-f0-9]{64}")) &&
                parts[1].matches(Regex("[A-Za-z0-9_-]{43}"))) { "Неверный код подключения" }
            val host = uri.host ?: throw IllegalArgumentException("Нет адреса ПК")
            lanAddress(host)
            return CompanionEndpoint(host, uri.port, parts[0], parts[1])
        }
        fun load(context: Context): CompanionEndpoint? = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
            .getString("endpoint", null)?.let { runCatching { parse(it) }.getOrNull() }
        fun save(context: Context, endpoint: CompanionEndpoint?) {
            context.getSharedPreferences("companion", Context.MODE_PRIVATE).edit().apply {
                if (endpoint == null) remove("endpoint") else putString("endpoint", endpoint.code())
            }.apply()
        }
        fun readLine(input: InputStream): String {
            val result = java.io.ByteArrayOutputStream()
            while (result.size() < 4096) {
                val next = input.read()
                if (next < 0) throw java.io.EOFException("ПК закрыл соединение")
                if (next == 10) return result.toString(Charsets.UTF_8.name())
                result.write(next)
            }
            throw java.io.IOException("Некорректный ответ ПК")
        }
    }
}
