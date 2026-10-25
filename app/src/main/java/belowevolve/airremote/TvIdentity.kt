package belowevolve.airremote

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** Device identity stays in Android Keystore. TV certificate is pinned after PIN verification. */
@Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
class TvIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("tv_identity", Context.MODE_PRIVATE)
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(2048)
                        .setDigests(
                            KeyProperties.DIGEST_NONE,
                            KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512
                        )
                        .setSignaturePaddings(
                            KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                            KeyProperties.SIGNATURE_PADDING_RSA_PSS
                        )
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setCertificateSubject(X500Principal("CN=Air Remote/${UUID.randomUUID()}"))
                        .setCertificateSerialNumber(BigInteger(159, SecureRandom()).add(BigInteger.ONE))
                        .setCertificateNotBefore(Date(0))
                        .setCertificateNotAfter(Date(4102444800000L))
                        .build()
                )
            }.generateKeyPair()
        }
    }

    val certificate: X509Certificate
        get() = store.getCertificate(ALIAS) as X509Certificate

    fun pin(host: String, cert: X509Certificate) {
        prefs.edit {
            putString(host, fingerprint(cert))
            putString("$host:identity", ALIAS)
        }
    }

    fun hasPin(host: String): Boolean = prefs.contains(host)

    fun forget(host: String) {
        prefs.edit { remove(host); remove("$host:identity") }
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }

    @SuppressLint("TrustAllX509TrustManager")
    fun context(host: String, pairing: Boolean): SSLContext {
        // Keep old pairings usable until the user pairs that TV again. New pairings
        // always use the installation-specific subject; retain the old private key.
        val clientAlias = if (pairing) ALIAS else prefs.getString("$host:identity", null)
            ?: if (store.containsAlias(LEGACY_ALIAS)) LEGACY_ALIAS else ALIAS
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(clientAlias)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = clientAlias
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getCertificateChain(alias: String?) = arrayOf(store.getCertificate(clientAlias) as X509Certificate)
            override fun getPrivateKey(alias: String?) = store.getKey(clientAlias, null) as PrivateKey
        }

        @SuppressLint("TrustAllX509TrustManager")
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                require(!chain.isNullOrEmpty()) { "ТВ не предоставил сертификат" }
                // First pairing is authenticated by the displayed PIN, not a public CA.
                if (!pairing) {
                    require(prefs.getString(host, null) == fingerprint(chain[0])) {
                        "Сертификат ТВ изменился. Выполните сопряжение заново."
                    }
                }
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf(km), arrayOf(trust), SecureRandom())
        }
    }

    companion object {
        private const val LEGACY_ALIAS = "air-remote-client-tls"
        private const val ALIAS = "air-remote-client-tls-v2"
    }
}
