package belowevolve.airremote

import android.util.Log
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
import kotlin.coroutines.EmptyCoroutineContext

private const val IME_TAG = "AirRemoteIme"

class TvClient(private val identity: TvIdentity) {
    @Volatile private var socket: SSLSocket? = null
    private var pairingSocket: SSLSocket? = null
    private var pairingHost = ""
    private var features = 0
    private val voice = TvVoiceSession()
    private val writeLock = Any()
    private val connectionLock = Any()
    private val connectingSockets = mutableSetOf<SSLSocket>()
    private val remotePorts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var connectionVersion = 0L
    private val keyboard = TvKeyboard()
    
    private fun open(host: String, port: Int, pairing: Boolean, version: Long? = null): SSLSocket {
        val s = identity.context(host, pairing).socketFactory.createSocket() as SSLSocket
        return try {
            if (version != null) synchronized(connectionLock) {
                if (version != connectionVersion) throw CancellationException("Соединение отменено")
                connectingSockets.add(s)
            }
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 12000
            s.connect(InetSocketAddress(host, port), if (port == 6465) 1500 else 5000)
            s.startHandshake()
            s
        } catch (e: Exception) { s.close(); throw e }
        finally { synchronized(connectionLock) { connectingSockets.remove(s) } }
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
        val s = open(host, 6467, pairing = true)
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
    
    fun close() {
        val closing = synchronized(connectionLock) {
            connectionVersion++
            (connectingSockets.toList() + listOfNotNull(socket)).also {
                connectingSockets.clear()
                socket = null
            }
        }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) { closing.forEach { runCatching { it.close() } } }
    }
    
    fun listen(host: String, onKeyboard: (KeyboardUpdate) -> Unit, onVoiceRequest: (Boolean) -> Unit, ready: (Boolean) -> Unit) {
        val version = synchronized(connectionLock) { connectionVersion }
        // Newer Google TV services expose the same authenticated protocol on a
        // standby-allowed wake port. Older TVs only have the regular remote port.
        // Connecting here keeps the remote reachable in standby; it does not
        // turn on the screen. Remember the working port for older televisions.
        val preferredPort = remotePorts[host] ?: 6465
        val s = try {
            open(host, preferredPort, pairing = false, version = version).also { remotePorts[host] = preferredPort }
        } catch (_: java.io.IOException) {
            val fallbackPort = if (preferredPort == 6465) 6466 else 6465
            open(host, fallbackPort, pairing = false, version = version).also { remotePorts[host] = fallbackPort }
        }
        try {
            synchronized(connectionLock) {
                if (version != connectionVersion) throw CancellationException("Соединение отменено")
                socket = s
            }
            synchronized(writeLock) {
                if (socket !== s) throw CancellationException("Соединение отменено")
                keyboard.reset()
                voice.reset()
                onVoiceRequest(false)
                onKeyboard(keyboard.snapshot())
            }
            while (!s.isClosed) {
                val msg = RemoteMessage.parseDelimitedFrom(s.inputStream) ?: throw EOFException("Соединение с ТВ закрыто")
                if (Log.isLoggable(IME_TAG, Log.DEBUG)) {
                    if (msg.hasRemoteImeKeyInject()) {
                        val ime = msg.remoteImeKeyInject
                        Log.d(IME_TAG, "context app=${ime.appInfo.counter} type=${ime.appInfo.int2} action=${ime.appInfo.int3} field=${ime.textFieldStatus.counterField} length=${ime.textFieldStatus.value.length} selection=${ime.textFieldStatus.start}:${ime.textFieldStatus.end} present=${ime.hasTextFieldStatus()}")
                    }
                    if (msg.hasRemoteImeShowRequest()) {
                        val field = msg.remoteImeShowRequest.remoteTextFieldStatus
                        Log.d(IME_TAG, "show field=${field.counterField} length=${field.value.length} selection=${field.start}:${field.end}")
                    }

                }
                synchronized(writeLock) {
                    if (socket === s) {
                        keyboard.receive(msg)?.let(onKeyboard)
                    }
                }
                when {
                    msg.hasRemoteConfigure() -> {
                        features = msg.remoteConfigure.code1 and (1 or 2 or 4 or 8 or 16 or 32 or 64 or 512)
                        send(
                            RemoteMessage.newBuilder().setRemoteConfigure(
                                RemoteConfigure.newBuilder().setCode1(features).setDeviceInfo(
                                    RemoteDeviceInfo.newBuilder().setUnknown1(1).setUnknown2("1").setPackageName(BuildConfig.APPLICATION_ID).setAppVersion(BuildConfig.VERSION_NAME),
                                ),
                            ).build(),
                        )
                    }
                    msg.hasRemoteSetActive() -> send(RemoteMessage.newBuilder().setRemoteSetActive(RemoteSetActive.newBuilder().setActive(features)).build())
                    msg.hasRemotePingRequest() -> send(RemoteMessage.newBuilder().setRemotePingResponse(RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1)).build())
                    msg.hasRemoteStart() -> {
                        // Only an established session may wait for a slow heartbeat.
                        s.soTimeout = 65000
                        ready(msg.remoteStart.started)
                    }
                    msg.hasRemoteVoiceBegin() -> synchronized(writeLock) {
                        if (socket === s) {
                            voice.request(msg.remoteVoiceBegin.sessionId)
                            onVoiceRequest(voice.requested != null)
                        }
                    }
                    msg.hasRemoteVoiceSessionEnd() || msg.hasRemoteVoiceEnd() -> synchronized(writeLock) {
                        val id = if (msg.hasRemoteVoiceSessionEnd()) msg.remoteVoiceSessionEnd.sessionId else msg.remoteVoiceEnd.sessionId
                        if (socket === s && voice.end(id)) onVoiceRequest(false)
                    }
                }
            }
        } finally {
            synchronized(connectionLock) { if (socket === s) socket = null }
            s.close()
        }
    }
    
    private fun send(message: MessageLite) = synchronized(writeLock) {
        val s = socket ?: error("Нет соединения с ТВ")
        message.writeDelimitedTo(s.outputStream); s.outputStream.flush()
    }
    
    fun editKeyboard(epoch: Long, revision: Long, value: KeyboardText) = synchronized(writeLock) {
        check((features and 4) != 0) { "ТВ не поддерживает ввод текста по сети" }
        keyboard.edit(epoch, value, revision)?.let {
            if (Log.isLoggable(IME_TAG, Log.DEBUG)) {
                Log.d(IME_TAG, "send ime=${it.remoteImeBatchEdit.imeCounter} field=${it.remoteImeBatchEdit.fieldCounter} edits=${it.remoteImeBatchEdit.editInfoCount} length=${value.text.length}")
            }
            send(it)
        }
    }

    fun submitKeyboard(epoch: Long) = synchronized(writeLock) { keyboard.submit(epoch)?.let { send(it) } }

    fun backspaceKeyboard(epoch: Long, count: Int) = synchronized(writeLock) {
        keyboard.backspace(epoch)?.let { command ->
            if (Log.isLoggable(IME_TAG, Log.DEBUG)) {
                Log.d(IME_TAG, "backspace count=$count")
            }
            repeat(count) { send(command) }
        }
    }
    
    fun key(code: Int, direction: RemoteDirection = RemoteDirection.SHORT) = send(RemoteMessage.newBuilder().setRemoteKeyInject(RemoteKeyInject.newBuilder().setKeyCodeValue(code).setDirection(direction)).build())
    
    fun launchYouTube() {
        check((features and 512) != 0) { "ТВ не поддерживает запуск приложений по сети" }
        send(
            RemoteMessage.newBuilder().setRemoteAppLinkLaunchRequest(
                RemoteAppLinkLaunchRequest.newBuilder().setAppLink("https://www.youtube.com/tv"),
            ).build(),
        )
    }
    
    fun startVoice(): Int = synchronized(writeLock) {
        check((features and 8) != 0) { "ТВ не сообщил о поддержке голоса" }
        val id = voice.begin()
        try {
            send(RemoteMessage.newBuilder().setRemoteVoiceBegin(RemoteVoiceBegin.newBuilder().setSessionId(id)).build())
            id
        } catch (e: Exception) { voice.end(id); throw e }
    }

    fun isVoiceActive(id: Int): Boolean = synchronized(writeLock) { voice.active == id }

    fun audio(id: Int, bytes: ByteArray) = synchronized(writeLock) {
        if (voice.active != id) return@synchronized
        require(bytes.size in 1..(20 * 1024))
        send(RemoteVoiceClientMessage.newBuilder().setRemoteVoicePayload(ByteString.copyFrom(bytes)).build())
    }

    fun endVoice(id: Int) = synchronized(writeLock) {
        if (voice.end(id)) send(RemoteMessage.newBuilder()
            .setRemoteVoiceEnd(RemoteVoiceEnd.newBuilder().setSessionId(id)).build())
    }
}
