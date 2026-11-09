package belowevolve.airremote

import android.app.Application
import android.app.Instrumentation
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import remote.Remotemessage.*
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/** A loopback TV exercises RemoteModel and the real TLS/protobuf transport without a paired TV. */
internal fun Instrumentation.checkTvConnectionLifecycle() {
    val host = "127.0.0.1"
    val prefs = targetContext.getSharedPreferences("remote", 0)
    val pins = targetContext.getSharedPreferences("tv_identity", 0)
    val savedPrefs = prefs.all.toMap()
    val savedPins = pins.all.toMap()
    val store = ViewModelStore()
    val model = AtomicReference<RemoteModel>()
    fun <T> main(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
    fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }
            SystemClock.sleep(20)
        }
    }
    val identity = TvIdentity(targetContext)
    identity.pin(host, identity.certificate)
    val alias = checkNotNull(pins.getString("$host:identity", null))
    try {
        LoopbackTv(identity.certificate, alias).use { tv ->
            check(prefs.edit().putString("host", host).putString("name", "Lifecycle fixture")
                .putBoolean("auto_connect", true).putBoolean("auto_keyboard", true).commit())
            main {
                model.set(ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(
                    targetContext.applicationContext as Application,
                ))[RemoteModel::class.java])
                check(model.get().hasSelectedTv) { "Saved TV disappeared before the handshake" }
                model.get().showDeviceDialog()
                check(model.get().deviceDialog == TvDeviceDialogState.SETTINGS)
                model.get().dismissDeviceDialog()
                model.get().resume()
            }
            val first = tv.awaitSession()
            main { model.get().pause() }
            tv.ready(first)
            await("handshake completing in background") { main { model.get().connected } }
            tv.send(first, RemoteMessage.newBuilder().setRemotePingRequest(
                RemotePingRequest.newBuilder().setVal1(42),
            ).build())
            check(tv.awaitPing() == 42) { "Background session stopped answering heartbeats" }
            tv.send(first, RemoteMessage.newBuilder().setRemoteImeShowRequest(
                RemoteImeShowRequest.newBuilder().setRemoteTextFieldStatus(
                    RemoteTextFieldStatus.newBuilder().setCounterField(1).setValue("background").setStart(10).setEnd(10),
                ),
            ).build())
            await("background keyboard state") { main { model.get().keyboardValue.text == "background" } }
            main {
                check(!model.get().keyboardVisible) { "Background TV opened the phone keyboard" }
                model.get().resume()
            }
            repeat(10) {
                main {
                    model.get().holdOk(true)
                    model.get().holdPower(true)
                    model.get().pause()
                    check(model.get().connected) { "App switch dropped the TV" }
                    model.get().resume()
                    model.get().key(19)
                    check(model.get().deviceDialog == TvDeviceDialogState.CLOSED)
                }
                for ((code, direction) in listOf(
                    23 to RemoteDirection.START_LONG, 26 to RemoteDirection.START_LONG,
                    23 to RemoteDirection.END_LONG, 26 to RemoteDirection.END_LONG,
                    19 to RemoteDirection.SHORT,
                )) {
                    val key = tv.awaitKey()
                    check(key.keyCodeValue == code && key.direction == direction) { "Wrong key after app switch: $key" }
                }
            }
            check(tv.connections.get() == 1) { "App switch replaced a healthy TLS session" }

            // Lose the transport for real, then press every entry point before RemoteStart.
            first.close()
            await("automatic reconnect") { main { !model.get().connected && model.get().connecting } }
            val second = tv.awaitSession()
            main {
                val remote = model.get()
                remote.key(19)
                remote.launchYouTube()
                remote.holdOk(true)
                remote.holdOk(false)
                remote.holdPower(true)
                remote.holdPower(false)
                remote.power()
                remote.showKeyboard()
                remote.startVoice()
                check(!remote.requireConnection())
                check(remote.deviceDialog == TvDeviceDialogState.CLOSED) { "Reconnect opened the device picker" }
                remote.showDeviceDialog()
                check(remote.hasSelectedTv) { "Reconnect forgot the paired TV" }
                check(remote.deviceDialog == TvDeviceDialogState.SETTINGS) { "Reconnect replaced device settings with a picker" }
                remote.dismissDeviceDialog()
            }
            tv.ready(second)
            await("reconnected") { main { model.get().connected } }
            main { model.get().key(20) }
            check(tv.awaitKey().keyCodeValue == 20)

            main { model.get().pause() }
            second.close()
            await("background disconnect") { main { !model.get().connected && !model.get().connecting } }
            main { model.get().resume() }
            val third = tv.awaitSession()
            tv.ready(third)
            await("return after background disconnect") { main { model.get().connected } }

            third.close()
            await("pause during reconnect backoff") {
                main {
                    val remote = model.get()
                    (!remote.connected && remote.connecting).also { if (it) remote.pause() }
                }
            }
            await("background retry stopped") { main { !model.get().connecting } }
            check(tv.connections.get() == 3) { "A delayed retry opened a new TV session in background" }
            main { model.get().resume() }
            val fourth = tv.awaitSession()
            tv.ready(fourth)
            await("return after paused retry") { main { model.get().connected } }
            main { store.clear() }
            await("ViewModel socket cleanup") { tv.closed.get() == 4 }
            main {
                check(!model.get().connected)
                model.get().key(19)
                check(model.get().deviceDialog == TvDeviceDialogState.SETTINGS) { "Offline paired TV lost its settings" }
            }
        }
    } finally {
        main { store.clear() }
        restorePreferences(prefs, savedPrefs)
        restorePreferences(pins, savedPins)
    }
}

private fun restorePreferences(prefs: SharedPreferences, saved: Map<String, *>) {
    val editor = prefs.edit().clear()
    saved.forEach { (key, value) ->
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
    check(editor.commit()) { "Failed to restore fixture preferences" }
}

private class LoopbackTv(certificate: X509Certificate, alias: String) : Closeable {
    val connections = AtomicInteger()
    val closed = AtomicInteger()
    private val sessions = LinkedBlockingQueue<SSLSocket>()
    private val keys = LinkedBlockingQueue<RemoteKeyInject>()
    private val pings = LinkedBlockingQueue<Int>()
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val keyManager = object : X509ExtendedKeyManager() {
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = null
        override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = if (type == "RSA") arrayOf(alias) else null
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = alias.takeIf { type == "RSA" }
        override fun getCertificateChain(selected: String?) = arrayOf(certificate)
        override fun getPrivateKey(selected: String?) = store.getKey(alias, null) as java.security.PrivateKey
    }
    private val context = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), null, null) }
    private val listener = context.serverSocketFactory.createServerSocket(
        6466, 4, InetAddress.getByName("127.0.0.1"),
    ) as SSLServerSocket
    @Volatile private var active: SSLSocket? = null
    private val reader = Thread({
        while (!listener.isClosed) {
            val socket = try { listener.accept() as SSLSocket } catch (_: IOException) { break }
            active = socket
            try {
                socket.use {
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    connections.incrementAndGet()
                    send(socket, RemoteMessage.newBuilder().setRemoteConfigure(
                        RemoteConfigure.newBuilder().setCode1(1 or 2 or 4 or 8 or 32 or 512),
                    ).build())
                    sessions.offer(socket)
                    while (true) {
                        val message = RemoteMessage.parseDelimitedFrom(socket.inputStream) ?: break
                        if (message.hasRemoteKeyInject()) keys.offer(message.remoteKeyInject)
                        if (message.hasRemotePingResponse()) pings.offer(message.remotePingResponse.val1)
                    }
                }
            } catch (_: IOException) {
                // The test deliberately closes both client and server sockets.
            } finally {
                active = null
                closed.incrementAndGet()
            }
        }
    }, "LoopbackTv").apply { start() }

    fun awaitSession(): SSLSocket = checkNotNull(sessions.poll(5, TimeUnit.SECONDS)) { "No TV session" }
    fun awaitKey(): RemoteKeyInject = checkNotNull(keys.poll(5, TimeUnit.SECONDS)) { "TV key not delivered" }
    fun awaitPing(): Int = checkNotNull(pings.poll(5, TimeUnit.SECONDS)) { "TV heartbeat not answered" }
    fun ready(socket: SSLSocket) = send(socket, RemoteMessage.newBuilder()
        .setRemoteStart(RemoteStart.newBuilder().setStarted(true)).build())

    fun send(socket: SSLSocket, message: RemoteMessage) = synchronized(socket) {
        message.writeDelimitedTo(socket.outputStream)
        socket.outputStream.flush()
    }

    override fun close() {
        listener.close()
        active?.close()
        reader.join(3_000)
        check(!reader.isAlive) { "Loopback TV did not stop" }
    }
}
