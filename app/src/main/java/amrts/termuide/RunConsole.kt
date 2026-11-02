package amrts.termuide

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import android.widget.FrameLayout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One authenticated PTY run, retained across Activity recreation but never replayed after process death. */
class RunConsole(private val context: Context) {
    enum class Phase { IDLE, CONNECTING, RUNNING, STOPPING, FINISHED, UNKNOWN }
    data class OutputSelection(val text: String? = null, val truncated: Boolean = false, val failed: Boolean = false)
    data class State(val id: String = "", val phase: Phase = Phase.IDLE, val visible: Boolean = false,
                     val exitCode: Int? = null, val detail: String? = null, val selection: OutputSelection? = null) {
        val active: Boolean get() = phase == Phase.CONNECTING || phase == Phase.RUNNING || phase == Phase.STOPPING
    }
    private class Session(val server: ServerSocket, val complete: (Int?, String?) -> Unit) {
        val id = UUID.randomUUID().toString()
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        @Volatile var socket: Socket? = null
        @Volatile var finished = false
        @Volatile var authenticated = false
        val pendingResize = AtomicReference<ByteArray?>()
        val writes = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(16),
            { task -> Thread(task, "Console input").apply { isDaemon = true } })
    }
    private class Write(val kind: Char, val action: () -> Unit) : Runnable {
        override fun run() = action()
    }
    private val main = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "Console output").apply { isDaemon = true } }
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    internal val output = ConsoleOutput()
    private var session: Session? = null
    private var outputChanged: (() -> Unit)? = null
    private var columns = 80
    private var rows = 24
    private var renderer: ConsoleTerminalView? = null
    private var rendererCreated = false
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    internal val textSize: Int
        get() = ((preferences.all["console_text_size"] as? Int) ?: Application.textsize).coerceIn(8, 32)

    internal fun setTextSize(size: Int) {
        val bounded = size.coerceIn(8, 32)
        if (bounded == textSize) return
        preferences.edit().putInt("console_text_size", bounded).apply()
        renderer?.setTextSize(bounded)
    }
    internal fun resetTextSize() {
        preferences.edit().remove("console_text_size").apply()
        renderer?.setTextSize(textSize)
    }
    internal fun selectOutput() {
        if (!state.value.visible || state.value.selection != null) return
        val request = OutputSelection()
        val id = state.value.id
        mutableState.value = state.value.copy(selection = request)
        fun complete(snapshot: OutputSelection?) {
            if (state.value.id == id && state.value.selection === request) {
                mutableState.value = state.value.copy(selection = snapshot ?: OutputSelection(failed = true))
            }
        }
        renderer?.snapshotText(::complete) ?: complete(null)
    }
    internal fun closeSelection() { mutableState.value = state.value.copy(selection = null) }

    fun show() { mutableState.value = mutableState.value.copy(visible = true) }
    fun hide() {
        mutableState.value = mutableState.value.copy(visible = false, selection = null)
        if (!state.value.active) releaseRenderer()
    }
    internal fun attachRenderer(host: FrameLayout, onError: () -> Unit) {
        val terminal = renderer ?: ConsoleTerminalView(MutableContextWrapper(context.applicationContext), this,
            if (rendererCreated) output.latestSequence else 0).also {
            renderer = it
            rendererCreated = true
        }
        terminal.attach(host, onError)
    }
    internal fun detachRenderer(host: FrameLayout) { renderer?.detach(host) }
    internal fun rendererFailed(terminal: ConsoleTerminalView) {
        if (renderer === terminal) {
            releaseRenderer()
            if (state.value.active) mutableState.value = state.value.copy(detail = context.getString(R.string.console_renderer_lost))
        }
    }
    private fun releaseRenderer() {
        val terminal = renderer
        renderer = null
        terminal?.release()
    }
    internal fun observeOutput(listener: (() -> Unit)?) { outputChanged = listener }
    internal fun removeOutputObserver(listener: () -> Unit) { if (outputChanged === listener) outputChanged = null }

    internal fun prepare(plan: RunCommands.Plan, python: String, complete: (Int?, String?) -> Unit): RunCommands.Plan {
        check(!state.value.active) { "A console program is already running" }
        releaseRenderer()
        rendererCreated = false
        val script = context.assets.open("console/runner.py").bufferedReader().use { it.readText() }
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
        val run = Session(server, complete)
        session = run
        output.clear()
        mutableState.value = State(run.id, Phase.CONNECTING, true)
        reader.execute { read(run) }
        return RunCommands.Plan(python, listOf("-I", "-S", "-u", "-c", script, server.localPort.toString(), run.token,
            plan.executable) + plan.arguments, plan.directory, true)
    }

    private fun read(run: Session) {
        try {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var accepted: Socket? = null
            while (!run.finished && SystemClock.elapsedRealtime() < deadline) {
                val peer = try { run.server.accept() } catch (_: SocketTimeoutException) { continue }
                run.socket = peer
                if (run.finished) { peer.close(); return }
                try {
                    val expected = "TERMUIDE1 ${run.token}\n".toByteArray(Charsets.US_ASCII)
                    val actual = ByteArray(expected.size)
                    val authenticationDeadline = SystemClock.elapsedRealtime() + 2000
                    var offset = 0
                    while (offset < actual.size) {
                        val remaining = authenticationDeadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0) throw SocketTimeoutException("Console authentication timed out")
                        peer.soTimeout = remaining.toInt()
                        val count = peer.getInputStream().read(actual, offset, actual.size - offset)
                        if (count < 0) throw IOException("Console authentication disconnected")
                        offset += count
                    }
                    if (!MessageDigest.isEqual(expected, actual)) { peer.close(); continue }
                    if (run.finished) { peer.close(); return }
                    run.authenticated = true
                    peer.getOutputStream().write("OK\n".toByteArray(Charsets.US_ASCII))
                    peer.soTimeout = 0
                    peer.tcpNoDelay = true
                    accepted = peer
                    break
                } catch (_: IOException) { peer.close() }
                finally { if (accepted !== peer) { peer.close(); run.socket = null; run.authenticated = false } }
            }
            val socket = accepted ?: throw IOException(context.getString(R.string.console_connect_timeout))
            run.socket = socket
            run.server.close()
            if (run.finished) { socket.close(); return }
            main.post {
                if (session === run && !run.finished && state.value.phase == Phase.CONNECTING) {
                    mutableState.value = state.value.copy(phase = Phase.RUNNING)
                    renderer?.updateInput()
                    resize(columns, rows)
                }
            }
            val input = DataInputStream(socket.getInputStream())
            while (!run.finished) {
                val size = input.readInt()
                require(size in 1..32768) { "Invalid console frame" }
                val kind = input.readUnsignedByte().toChar()
                val data = ByteArray(size - 1).also(input::readFully)
                when (kind) {
                    'O' -> {
                        val delivered = CountDownLatch(1)
                        main.post {
                            try {
                                if (session === run && !run.finished) {
                                    output.append(data)
                                    outputChanged?.invoke()
                                }
                            } finally { delivered.countDown() }
                        }
                        delivered.await()
                    }
                    'E' -> {
                        require(data.size == 4) { "Invalid exit status" }
                        val exit = ByteBuffer.wrap(data).int
                        require(exit in 0..255) { "Invalid exit status" }
                        main.post { finish(run, exit, null) }
                        return
                    }
                    else -> throw IOException("Unknown console frame")
                }
            }
        } catch (error: Exception) {
            main.post { finish(run, null, error.message ?: context.getString(R.string.console_connection_lost)) }
        } finally {
            close(run)
        }
    }

    fun input(text: String) {
        if (state.value.phase != Phase.RUNNING) return
        if (text == "\u0003") { interrupt(); return }
        if (text.length > 16384) {
            mutableState.value = state.value.copy(detail = context.getString(R.string.console_input_limit))
            return
        }
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.size > 16384) {
            mutableState.value = state.value.copy(detail = context.getString(R.string.console_input_limit))
            return
        }
        if (data.isNotEmpty()) send('I', data)
    }
    fun interrupt() {
        if (state.value.phase != Phase.RUNNING) return
        session?.writes?.queue?.clear()
        send('C')
    }
    fun forceStop() {
        val run = session ?: return
        if (!state.value.active || state.value.phase == Phase.STOPPING) return
        if (state.value.phase == Phase.CONNECTING) {
            finish(run, null, context.getString(R.string.console_stop_unconfirmed))
            return
        }
        run.writes.queue.clear()
        send('K')
        mutableState.value = state.value.copy(phase = Phase.STOPPING)
        renderer?.updateInput()
        // Closing the authenticated channel also makes the runner kill its owned process group.
        main.postDelayed({
            if (session === run && state.value.active) finish(run, null, context.getString(R.string.console_stop_unconfirmed))
        }, 2000)
    }
    fun resize(columns: Int, rows: Int) {
        if (columns !in 2..1000 || rows !in 1..1000) return
        this.columns = columns
        this.rows = rows
        send('R', ByteBuffer.allocate(8).putInt(rows).putInt(columns).array())
    }
    private fun send(kind: Char, data: ByteArray = byteArrayOf()) {
        val run = session ?: return
        if (state.value.phase != Phase.RUNNING || run.finished) return
        if (kind == 'R') {
            run.pendingResize.set(data)
            run.writes.queue.removeIf { it is Write && it.kind == 'R' }
        }
        try {
            run.writes.execute(Write(kind) {
                try {
                    val out = DataOutputStream(run.socket?.getOutputStream() ?: return@Write)
                    // Queued input also flushes the latest resize if the bounded queue could not accept it.
                    run.pendingResize.getAndSet(null)?.let { size ->
                        out.writeInt(size.size + 1)
                        out.writeByte('R'.code)
                        out.write(size)
                    }
                    if (kind != 'R') {
                        out.writeInt(data.size + 1)
                        out.writeByte(kind.code)
                        out.write(data)
                    }
                    out.flush()
                } catch (_: IOException) {
                    // The reader owns completion; a failed write must not discard buffered output/exit frames.
                    main.post {
                        if (session === run && !run.finished && state.value.phase == Phase.RUNNING) {
                            mutableState.value = state.value.copy(detail = context.getString(R.string.console_input_failed))
                        }
                    }
                }
            })
            if (kind != 'R' && state.value.detail != null) mutableState.value = state.value.copy(detail = null)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (kind != 'R') mutableState.value = state.value.copy(detail = context.getString(R.string.console_input_limit))
        }
    }

    internal fun transportFailed(message: String) { session?.let { finish(it, null, message) } }
    internal fun termuxFinished(result: Bundle?) {
        val run = session ?: return
        if (!state.value.active) return
        if (run.authenticated) return
        val error = if (result == null || result.get("err") !is Int || result.getInt("err") != -1)
            result?.getString("errmsg")?.take(500) ?: context.getString(R.string.console_connection_lost)
        else if (result.get("exitCode") !is Int || result.getInt("exitCode") != 0)
            result.getString("stderr")?.take(500) ?: context.getString(R.string.console_connection_lost)
        else context.getString(R.string.console_connection_lost)
        finish(run, null, error)
    }
    private fun finish(run: Session, exit: Int?, error: String?) {
        if (session !== run || run.finished) return
        run.finished = true
        close(run)
        val detail = if (exit == null) {
            error?.takeIf { it.isNotBlank() }?.take(500) ?: context.getString(R.string.console_connection_lost)
        } else null
        mutableState.value = state.value.copy(phase = if (exit != null) Phase.FINISHED else Phase.UNKNOWN,
            exitCode = exit, detail = detail)
        renderer?.updateInput()
        if (!state.value.visible) releaseRenderer()
        run.complete(exit, detail)
    }
    private fun close(run: Session) {
        try { run.server.close() } catch (_: IOException) { }
        try { run.socket?.close() } catch (_: IOException) { }
        run.writes.shutdownNow()
    }
    fun shutdown() {
        closeSelection()
        session?.let { it.finished = true; close(it) }
        session = null
        releaseRenderer()
        outputChanged = null
        reader.shutdownNow()
    }
}

/** Main-thread-only bounded byte history; rendering acknowledges batches instead of queuing JS per byte. */
internal class ConsoleOutput(private val limit: Int = 262144) {
    private data class Chunk(val sequence: Long, val bytes: ByteArray)
    data class Batch(val bytes: ByteArray, val sequence: Long, val reset: Boolean)
    private val chunks = ArrayDeque<Chunk>()
    private var size = 0
    private var sequence = 0L
    val latestSequence: Long get() = sequence
    fun clear() { chunks.clear(); size = 0; sequence = 0 }
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val retained = bytes.copyOfRange(maxOf(0, bytes.size - limit), bytes.size)
        chunks.addLast(Chunk(++sequence, retained))
        size += retained.size
        while (size > limit) size -= chunks.removeFirst().bytes.size
    }
    fun after(previous: Long, through: Long = Long.MAX_VALUE): Batch? {
        // shortcut: a bounded byte tail cannot exactly restore a truncated full-screen terminal; add snapshots if needed.
        val selected = chunks.filter { it.sequence > previous && it.sequence <= through }.take(2)
        if (selected.isEmpty()) return null
        val bytes = ByteArrayOutputStream().apply { selected.forEach { write(it.bytes) } }.toByteArray()
        return Batch(bytes, selected.last().sequence,
            previous < chunks.first().sequence - 1)
    }
}
