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
private const val TV_TAG = "AirRemoteTv"

class TvClient(private val identity: TvIdentity, initialPort: Int = 6466) {
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var pairingSocket: SSLSocket? = null
    private var pairingHost = ""
    private var features = 0
    private val voice = TvVoiceSession()
    private val power = TvPowerSession()
    private val keyStartup = TvKeyStartupGate()
    private val writeLock = Any()
    private val connectionLock = Any()
    private val connectingSockets = mutableSetOf<SSLSocket>()
    private val remotePorts = TvConnectionPorts(initialPort)
    private var connectionVersion = 0L
    private var pairingVersion = 0L
    private val keyboard = TvKeyboard()
    
    private fun open(host: String, port: Int, pairing: Boolean, version: Long? = null, pairVersion: Long? = null): SSLSocket {
        val openingAt = android.os.SystemClock.elapsedRealtime()
        fun trace(stage: String) {
            if (!pairing && Log.isLoggable(TV_TAG, Log.DEBUG))
                Log.d(TV_TAG, "Transport $stage port=$port elapsed=${android.os.SystemClock.elapsedRealtime()} durationMs=${android.os.SystemClock.elapsedRealtime() - openingAt}")
        }
        trace("opening")
        val s = identity.context(host, pairing).socketFactory.createSocket() as SSLSocket
        return try {
            if (pairVersion != null) synchronized(connectionLock) {
                if (pairVersion != pairingVersion) throw CancellationException("Сопряжение отменено")
                pairingSocket = s
                pairingHost = host
            }
            if (version != null) synchronized(connectionLock) {
                if (version != connectionVersion) throw CancellationException("Соединение отменено")
                connectingSockets.add(s)
            }
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 12000
            s.connect(InetSocketAddress(host, port), if (port == 6465) 1500 else 5000)
            trace("tcp")
            s.startHandshake()
            trace("tls")
            s
        } catch (e: Exception) { runCatching { s.close() }; throw e }
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
        val version = resetPairing()
        val s = open(host, 6467, pairing = true, pairVersion = version)
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
        require((digest[0].toInt() and 255) == code.substring(0, 2).toInt(16)) { "Неверный код подключения" }
        check(exchange(s, pairMessage().setSecret(Secret.newBuilder().setSecret(ByteString.copyFrom(digest))).build()).hasSecretAck())
        identity.pin(pairingHost, server)
        cancelPairing()
    }
    
    fun cancelPairing() {
        resetPairing()
    }

    private fun resetPairing(): Long {
        val (version, closing) = synchronized(connectionLock) {
            pairingVersion++
            pairingVersion to pairingSocket.also { pairingSocket = null }
        }
        runCatching { closing?.close() }
        return version
    }
    
    fun close() {
        val closing = synchronized(connectionLock) {
            connectionVersion++
            power.reset()
            (connectingSockets.toList() + listOfNotNull(socket)).also {
                connectingSockets.clear()
                socket = null
                keyStartup.close()
            }
        }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) { closing.forEach { runCatching { it.close() } } }
    }
    
    fun listen(host: String, onKeyboard: (KeyboardUpdate) -> Unit, onVoiceRequest: (Boolean) -> Unit, preferredPort: Int? = null, ready: (Boolean) -> Unit) {
        val version = synchronized(connectionLock) { connectionVersion }
        remotePorts.connect(host, preferredPort) { port, established ->
            listenPort(host, port, version, onKeyboard, onVoiceRequest) { powered ->
                established()
                ready(powered)
            }
        }
    }

    private fun listenPort(
        host: String, port: Int, version: Long,
        onKeyboard: (KeyboardUpdate) -> Unit, onVoiceRequest: (Boolean) -> Unit,
        ready: (Boolean) -> Unit,
    ) {
        val s = open(host, port, pairing = false, version = version)
        if (Log.isLoggable(TV_TAG, Log.DEBUG)) Log.d(TV_TAG, "Connected port=$port")
        try {
            synchronized(writeLock) {
                synchronized(connectionLock) {
                    if (version != connectionVersion) throw CancellationException("Соединение отменено")
                    socket = s
                    keyStartup.open(s)
                }
                features = 0
                power.reset()
                keyboard.reset()
                voice.reset()
                onVoiceRequest(false)
                onKeyboard(keyboard.snapshot())
            }
            val input = s.inputStream.buffered()
            while (true) {
                val msg = RemoteMessage.parseDelimitedFrom(input) ?: throw EOFException("Соединение с ТВ закрыто")
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
                    // close() can leave buffered messages in the previous reader.
                    // None may change the new session or send replies on its socket.
                    if (socket !== s) throw EOFException("Соединение с ТВ изменилось")
                    keyboard.receive(msg)?.let(onKeyboard)
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
                            keyStartup.ready(s)
                            if ((features and 32) != 0) power.update(msg.remoteStart.started)
                            if (Log.isLoggable(TV_TAG, Log.DEBUG)) Log.d(TV_TAG, "Power port=$port on=${msg.remoteStart.started} elapsed=${android.os.SystemClock.elapsedRealtime()}")
                            // Google's wake endpoint can remain silent while healthy.
                            // The normal control endpoint sends heartbeats, so retain
                            // its deadline to detect a stalled network connection.
                            s.soTimeout = if (port == 6465) 0 else 65000
                            ready(msg.remoteStart.started)
                        }
                        msg.hasRemoteVoiceBegin() -> {
                            voice.request(msg.remoteVoiceBegin.sessionId)
                            onVoiceRequest(voice.requested != null)
                        }
                        msg.hasRemoteVoiceSessionEnd() || msg.hasRemoteVoiceEnd() -> {
                            val id = if (msg.hasRemoteVoiceSessionEnd()) msg.remoteVoiceSessionEnd.sessionId else msg.remoteVoiceEnd.sessionId
                            if (voice.end(id)) onVoiceRequest(false)
                        }
                    }
                }
            }
        } finally {
            synchronized(connectionLock) {
                if (socket === s) {
                    socket = null
                    keyStartup.close()
                }
            }
            s.close()
        }
    }
    
    private fun send(message: MessageLite) = synchronized(writeLock) {
        val s = socket ?: throw EOFException("Нет соединения с ТВ")
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
    
    suspend fun ensureAwake(ackTimeoutMillis: Long = 5_000, wake: (() -> Unit)? = null) =
        power.ensureAwake(ackTimeoutMillis) { if (wake != null) wake() else sendKey(26) }

    fun prepareExternalWake(): CompletableDeferred<Unit>? = power.explicitWakeSent()

    fun externalWakeFailed(pending: CompletableDeferred<Unit>?, error: Exception) = power.failed(pending, error)

    fun sendKey(code: Int, direction: RemoteDirection = RemoteDirection.SHORT) {
        val expected = synchronized(writeLock) { socket ?: throw EOFException("Нет соединения с ТВ") }
        keyStartup.awaitReady(expected)
        synchronized(writeLock) {
            if (socket !== expected) throw EOFException("Соединение с ТВ изменилось")
            if ((code == 26 || code == 177 || code == 224) && Log.isLoggable(TV_TAG, Log.DEBUG)) Log.d(TV_TAG, "Power key=$code port=${expected.port} direction=$direction elapsed=${android.os.SystemClock.elapsedRealtime()}")
            send(RemoteMessage.newBuilder().setRemoteKeyInject(RemoteKeyInject.newBuilder().setKeyCodeValue(code).setDirection(direction)).build())
            power.keySent(code, direction)
        }
    }
    
    fun launchAppLink(link: String) {
        check((features and 512) != 0) { "ТВ не поддерживает запуск приложений по сети" }
        send(
            RemoteMessage.newBuilder().setRemoteAppLinkLaunchRequest(
                RemoteAppLinkLaunchRequest.newBuilder().setAppLink(link),
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

    fun audio(id: Int, bytes: ByteArray, count: Int) = synchronized(writeLock) {
        if (voice.active != id) return@synchronized
        require(count in 1..minOf(bytes.size, 20 * 1024))
        send(RemoteVoiceClientMessage.newBuilder().setRemoteVoicePayload(ByteString.copyFrom(bytes, 0, count)).build())
    }

    fun endVoice(id: Int) = synchronized(writeLock) {
        if (voice.end(id)) send(RemoteMessage.newBuilder()
            .setRemoteVoiceEnd(RemoteVoiceEnd.newBuilder().setSessionId(id)).build())
    }
}
