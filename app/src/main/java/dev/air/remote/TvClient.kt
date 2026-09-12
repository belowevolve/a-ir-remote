package dev.air.remote

import com.google.polo.wire.protobuf.PoloProto.*
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import remote.Remotemessage.*
import java.io.EOFException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

class TvClient(private val identity: TvIdentity) {
    @Volatile private var socket: SSLSocket? = null
    private var pairingSocket: SSLSocket? = null
    private var pairingHost = ""
    private var imeCounter = 0
    private var fieldCounter = 0
    private var features = 0
    private var voiceReady: CompletableDeferred<Int>? = null
    private val writeLock = Any()
    private fun open(host: String, port: Int, pairing: Boolean): SSLSocket {
        val s = identity.context(host, pairing).socketFactory.createSocket() as SSLSocket
        try {
            s.soTimeout = 12000
            s.connect(InetSocketAddress(host, port), 5000)
            s.startHandshake()
            return s
        } catch (e: Exception) { s.close(); throw e }
    }
    private fun pairMessage() = OuterMessage.newBuilder().setProtocolVersion(2).setStatus(OuterMessage.Status.STATUS_OK)
    private fun exchange(s: SSLSocket, msg: OuterMessage): OuterMessage {
        msg.writeDelimitedTo(s.outputStream); s.outputStream.flush()
        val reply = OuterMessage.parseDelimitedFrom(s.inputStream) ?: throw EOFException("ТВ закрыл сопряжение")
        check(reply.status == OuterMessage.Status.STATUS_OK) { "ТВ отклонил сопряжение: ${reply.status}" }
        return reply
    }
    fun beginPairing(host: String) {
        cancelPairing()
        val s = open(host, 6467, true)
        pairingSocket = s; pairingHost = host
        try {
            check(exchange(s, pairMessage().setPairingRequest(PairingRequest.newBuilder().setServiceName("air_remote").setClientName("Air Remote")).build()).hasPairingRequestAck())
            val enc = Options.Encoding.newBuilder().setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL).setSymbolLength(6)
            check(exchange(s, pairMessage().setOptions(Options.newBuilder().setPreferredRole(Options.RoleType.ROLE_TYPE_INPUT).addInputEncodings(enc)).build()).hasOptions())
            check(exchange(s, pairMessage().setConfiguration(Configuration.newBuilder().setClientRole(Options.RoleType.ROLE_TYPE_INPUT).setEncoding(enc)).build()).hasConfigurationAck())
        } catch (e: Exception) { cancelPairing(); throw e }
    }
    fun finishPairing(code: String) {
        require(code.matches(Regex("[0-9a-fA-F]{6}"))) { "Введите 6 символов с экрана ТВ" }
        val s = pairingSocket ?: error("Начните сопряжение заново")
        val server = s.session.peerCertificates[0] as X509Certificate
        val hash = MessageDigest.getInstance("SHA-256")
        for (cert in listOf(identity.certificate, server)) {
            val key = cert.publicKey as RSAPublicKey
            for (n in listOf(key.modulus, key.publicExponent)) {
                val bytes = n.toByteArray()
                hash.update(if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes)
            }
        }
        hash.update(code.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val digest = hash.digest()
        require((digest[0].toInt() and 255) == code.substring(0, 2).toInt(16)) { "Код не совпадает. Проверьте экран ТВ." }
        check(exchange(s, pairMessage().setSecret(Secret.newBuilder().setSecret(ByteString.copyFrom(digest))).build()).hasSecretAck())
        identity.pin(pairingHost, server)
        cancelPairing()
    }
    fun cancelPairing() { pairingSocket?.close(); pairingSocket = null }
    fun close() { socket?.close(); socket = null; voiceReady?.cancel() }
    fun listen(host: String, ready: () -> Unit, imeShown: (String) -> Unit) {
        val s = open(host, 6466, false)
        socket = s
        try {
            while (!s.isClosed) {
                val msg = RemoteMessage.parseDelimitedFrom(s.inputStream) ?: throw EOFException("Соединение с ТВ закрыто")
                when {
                    msg.hasRemoteConfigure() -> {
                        features = msg.remoteConfigure.code1 and  (1 or 2 or 4 or 8 or 16 or 32 or 64 or 512)
                        send(RemoteMessage.newBuilder().setRemoteConfigure(RemoteConfigure.newBuilder().setCode1(features)
                            .setDeviceInfo(RemoteDeviceInfo.newBuilder().setUnknown1(1).setUnknown2("1").setPackageName("dev.air.remote").setAppVersion("0.1"))).build())
                    }
                    msg.hasRemoteSetActive() -> send(RemoteMessage.newBuilder().setRemoteSetActive(RemoteSetActive.newBuilder().setActive(features)).build())
                    msg.hasRemotePingRequest() -> send(RemoteMessage.newBuilder().setRemotePingResponse(RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1)).build())
                    msg.hasRemoteStart() -> ready()
                    msg.hasRemoteImeBatchEdit() -> { imeCounter = msg.remoteImeBatchEdit.imeCounter; fieldCounter = msg.remoteImeBatchEdit.fieldCounter }
                    msg.hasRemoteImeShowRequest() -> {
                        val field = msg.remoteImeShowRequest.remoteTextFieldStatus
                        fieldCounter = field.counterField
                        imeShown(field.value)
                    }
                    msg.hasRemoteVoiceBegin() -> voiceReady?.complete(msg.remoteVoiceBegin.sessionId)
                }
            }
        } finally { s.close(); if (socket === s) socket = null }
    }
    private fun send(message: MessageLite) = synchronized(writeLock) {
        val s = socket ?: error("Нет соединения с ТВ")
        message.writeDelimitedTo(s.outputStream); s.outputStream.flush()
    }
    fun key(code: Int) = send(RemoteMessage.newBuilder().setRemoteKeyInject(RemoteKeyInject.newBuilder().setKeyCodeValue(code).setDirection(RemoteDirection.SHORT)).build())
    fun text(value: String) {
        require(value.isNotEmpty()) { "Введите текст" }
        send(RemoteMessage.newBuilder().setRemoteImeBatchEdit(RemoteImeBatchEdit.newBuilder().setImeCounter(imeCounter).setFieldCounter(fieldCounter)
            .addEditInfo(RemoteEditInfo.newBuilder().setInsert(1).setTextFieldStatus(RemoteImeObject.newBuilder().setStart(value.length - 1).setEnd(value.length - 1).setValue(value)))).build())
    }
    fun launchApp(appLinkOrPackage: String) {
        require(appLinkOrPackage.isNotBlank()) { "Укажите package id приложения" }
        check(features and 512 != 0) { "ТВ не поддерживает запуск приложений по сети" }
        val link = if (appLinkOrPackage.contains("://")) appLinkOrPackage
        else "market://launch?id=$appLinkOrPackage"
        send(RemoteMessage.newBuilder().setRemoteAppLinkLaunchRequest(
            RemoteAppLinkLaunchRequest.newBuilder().setAppLink(link),
        ).build())
    }
    suspend fun startVoice(): Int {
        check(features and 8 != 0) { "ТВ не сообщил о поддержке голоса" }
        val deferred = CompletableDeferred<Int>(); voiceReady = deferred
        try {
            key(84)
            val id = withTimeout(5.seconds) { deferred.await() }
            send(RemoteMessage.newBuilder().setRemoteVoiceBegin(RemoteVoiceBegin.newBuilder().setSessionId(id)).build())
            return id
        } finally { voiceReady = null }
    }
    fun audio(id: Int, bytes: ByteArray) {
        // Android TV Remote Service accepts chunks up to 20 KiB and some TVs reject
        // chunks smaller than 8 KiB. Padding is applied after splitting.
        bytes.asList().chunked(20 * 1024).forEach { chunk ->
            val samples = chunk.toByteArray().let { if (it.size < 8 * 1024) it.copyOf(8 * 1024) else it }
            send(RemoteMessage.newBuilder().setRemoteVoicePayload(
                RemoteVoicePayload.newBuilder().setSessionId(id).setSamples(ByteString.copyFrom(samples)),
            ).build())
        }
    }
    fun endVoice(id: Int) = send(RemoteMessage.newBuilder().setRemoteVoiceEnd(RemoteVoiceEnd.newBuilder().setSessionId(id)).build())
}
