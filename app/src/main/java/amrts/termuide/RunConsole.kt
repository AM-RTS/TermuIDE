package amrts.termuide

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One authenticated PTY run, retained across Activity recreation but never replayed after process death. */
class RunConsole(private val context: Context) {
    enum class Phase { IDLE, CONNECTING, RUNNING, STOPPING, FINISHED, UNKNOWN }
    data class State(val id: String = "", val phase: Phase = Phase.IDLE, val visible: Boolean = false,
                     val exitCode: Int? = null, val detail: String? = null) {
        val active: Boolean get() = phase == Phase.CONNECTING || phase == Phase.RUNNING || phase == Phase.STOPPING
    }
    private class Session(val server: ServerSocket, val complete: (Int?, String?) -> Unit) {
        val id = UUID.randomUUID().toString()
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        @Volatile var socket: Socket? = null
        @Volatile var finished = false
        val writes = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(16),
            { task -> Thread(task, "Console input").apply { isDaemon = true } })
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

    fun show() { mutableState.value = mutableState.value.copy(visible = true) }
    fun hide() { mutableState.value = mutableState.value.copy(visible = false) }
    internal fun observeOutput(listener: (() -> Unit)?) { outputChanged = listener }
    internal fun removeOutputObserver(listener: () -> Unit) { if (outputChanged === listener) outputChanged = null }

    internal fun prepare(plan: RunCommands.Plan, python: String, complete: (Int?, String?) -> Unit): RunCommands.Plan {
        check(!state.value.active) { "A console program is already running" }
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
                try {
                    peer.soTimeout = 2000
                    val expected = "TERMUIDE1 ${run.token}\n".toByteArray(Charsets.US_ASCII)
                    val actual = ByteArray(expected.size)
                    DataInputStream(peer.getInputStream()).readFully(actual)
                    if (!MessageDigest.isEqual(expected, actual)) { peer.close(); continue }
                    if (run.finished) { peer.close(); return }
                    peer.getOutputStream().write("OK\n".toByteArray(Charsets.US_ASCII))
                    peer.soTimeout = 0
                    peer.tcpNoDelay = true
                    accepted = peer
                    break
                } catch (_: IOException) { peer.close() }
            }
            val socket = accepted ?: throw IOException(context.getString(R.string.console_connect_timeout))
            run.socket = socket
            run.server.close()
            if (run.finished) { socket.close(); return }
            main.post {
                if (session === run && !run.finished && state.value.phase == Phase.CONNECTING) {
                    mutableState.value = state.value.copy(phase = Phase.RUNNING)
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
    fun interrupt() { send('C') }
    fun forceStop() {
        val run = session ?: return
        if (!state.value.active) return
        if (state.value.phase == Phase.CONNECTING) {
            finish(run, null, context.getString(R.string.console_stop_unconfirmed))
            return
        }
        send('K')
        mutableState.value = state.value.copy(phase = Phase.STOPPING)
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
        try {
            run.writes.execute {
                try {
                    val out = DataOutputStream(run.socket?.getOutputStream() ?: return@execute)
                    out.writeInt(data.size + 1)
                    out.writeByte(kind.code)
                    out.write(data)
                    out.flush()
                } catch (error: IOException) { main.post { finish(run, null, error.message) } }
            }
            if (state.value.detail != null) mutableState.value = state.value.copy(detail = null)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            mutableState.value = state.value.copy(detail = context.getString(R.string.console_input_limit))
        }
    }

    internal fun transportFailed(message: String) { session?.let { finish(it, null, message) } }
    internal fun termuxFinished(result: Bundle?) {
        val run = session ?: return
        if (!state.value.active) return
        val error = if (result == null || result.get("err") !is Int || result.getInt("err") != -1)
            result?.getString("errmsg")?.take(500) ?: context.getString(R.string.console_connection_lost)
        else if (result.get("exitCode") !is Int || result.getInt("exitCode") != 0)
            result.getString("stderr")?.take(500) ?: context.getString(R.string.console_connection_lost)
        else context.getString(R.string.console_connection_lost)
        main.postDelayed({ if (session === run && state.value.active) finish(run, null, error) }, 3000)
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
        run.complete(exit, detail)
    }
    private fun close(run: Session) {
        try { run.server.close() } catch (_: IOException) { }
        try { run.socket?.close() } catch (_: IOException) { }
        run.writes.shutdownNow()
    }
    fun shutdown() {
        session?.let { it.finished = true; close(it) }
        session = null
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
    fun clear() { chunks.clear(); size = 0; sequence = 0 }
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val retained = bytes.copyOfRange(maxOf(0, bytes.size - limit), bytes.size)
        chunks.addLast(Chunk(++sequence, retained))
        size += retained.size
        while (size > limit) size -= chunks.removeFirst().bytes.size
    }
    fun after(previous: Long): Batch? {
        // shortcut: a bounded byte tail cannot exactly restore a truncated full-screen terminal; add snapshots if needed.
        val selected = chunks.filter { it.sequence > previous }.take(2)
        if (selected.isEmpty()) return null
        val bytes = ByteArrayOutputStream().apply { selected.forEach { write(it.bytes) } }.toByteArray()
        return Batch(bytes, selected.last().sequence,
            previous < chunks.first().sequence - 1)
    }
}
