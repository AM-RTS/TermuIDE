package amrts.termuide

import android.os.Looper
import android.app.Activity
import android.content.MutableContextWrapper
import android.widget.FrameLayout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.io.IOException
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RunConsoleTest {
    private lateinit var console: RunConsole
    private lateinit var plan: RunCommands.Plan
    private var peer: Socket? = null
    private val completions = mutableListOf<Pair<Int?, String?>>()

    @Before fun prepare() {
        console = RunConsole(Application.getInstance())
        plan = console.prepare(RunCommands.Plan("/bin/bash", listOf("-c", "exit 37"), "/tmp"), "/bin/python") { code, error ->
            completions += code to error
        }
    }
    @After fun close() { peer?.close(); console.shutdown() }

    private fun await(predicate: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(5)
        }
        fail("Console did not reach the expected state")
    }
    private fun connect(token: String = plan.arguments[6]): Socket {
        return Socket("127.0.0.1", plan.arguments[5].toInt()).apply {
            soTimeout = 2000
            getOutputStream().write("TERMUIDE1 $token\n".toByteArray())
        }
    }
    private fun authenticate() {
        val socket = connect().also { peer = it }
        val reply = ByteArray(3).also { DataInputStream(socket.getInputStream()).readFully(it) }
        assertEquals("OK\n", reply.toString(Charsets.US_ASCII))
        await { console.state.value.phase == RunConsole.Phase.RUNNING }
        val input = DataInputStream(socket.getInputStream())
        assertEquals(9, input.readInt())
        assertEquals('R'.code, input.readUnsignedByte())
        assertEquals(24, input.readInt())
        assertEquals(80, input.readInt())
    }
    private fun send(kind: Char, bytes: ByteArray) {
        DataOutputStream(peer!!.getOutputStream()).apply { writeInt(bytes.size + 1); writeByte(kind.code); write(bytes); flush() }
    }
    private fun exit(code: Int) {
        val completed = completions.size
        send('E', ByteBuffer.allocate(4).putInt(code).array())
        await { completions.size > completed }
    }
    private fun session() = RunConsole::class.java.getDeclaredField("session").apply { isAccessible = true }.get(console)!!
    private fun nativeSocketField() = session().javaClass.getDeclaredField("socket").apply { isAccessible = true }

    @Test fun authenticatedRunStreamsInputOutputAndKeepsItsRealExitStatus() {
        assertTrue(plan.background)
        assertEquals(listOf("-I", "-S", "-u", "-c"), plan.arguments.take(4))
        assertEquals(listOf("/bin/bash", "-c", "exit 37"), plan.arguments.drop(7))
        authenticate()
        send('O', "prompt: ".toByteArray())
        await { console.output.after(0) != null }
        assertEquals("prompt: ", console.output.after(0)!!.bytes.toString(Charsets.UTF_8))
        console.input("hello\n")
        val input = DataInputStream(peer!!.getInputStream())
        assertEquals(7, input.readInt())
        assertEquals('I'.code, input.readUnsignedByte())
        assertEquals("hello\n", ByteArray(6).also(input::readFully).toString(Charsets.UTF_8))
        exit(37)
        assertEquals(37, console.state.value.exitCode)
        assertEquals(listOf(37 to null), completions)
        console.termuxFinished(null)
        assertEquals(RunConsole.Phase.FINISHED, console.state.value.phase)
    }

    @Test fun hidingAndReplacingTheRendererDoesNotDisconnectOrLoseOutput() {
        authenticate()
        var first = 0
        var replacement = 0
        val old: () -> Unit = { first++ }
        console.observeOutput(old)
        console.hide()
        assertTrue(console.state.value.active)
        assertFalse(console.state.value.visible)
        console.observeOutput { replacement++ }
        console.removeOutputObserver(old)
        send('O', "retained".toByteArray())
        await { replacement == 1 }
        assertEquals(0, first)
        console.show()
        assertTrue(console.state.value.visible)
        assertEquals("retained", console.output.after(0)!!.bytes.toString(Charsets.UTF_8))
        exit(0)
    }

    @Test fun anUnauthenticatedPeerCannotOwnTheRun() {
        connect("0".repeat(64)).use { assertEquals(-1, it.getInputStream().read()) }
        assertEquals(RunConsole.Phase.CONNECTING, console.state.value.phase)
        authenticate()
        exit(0)
    }

    @Test fun invalidFramesAndDisconnectsCannotReportSuccess() {
        authenticate()
        DataOutputStream(peer!!.getOutputStream()).apply { writeInt(Int.MAX_VALUE); flush() }
        await { completions.isNotEmpty() }
        assertEquals(RunConsole.Phase.UNKNOWN, console.state.value.phase)
        assertNull(completions.single().first)
    }

    @Test fun forceStopAndInterruptUseOnlyTheAuthenticatedRunChannel() {
        authenticate()
        val input = DataInputStream(peer!!.getInputStream())
        console.interrupt()
        assertEquals(1, input.readInt())
        assertEquals('C'.code, input.readUnsignedByte())
        console.forceStop()
        assertEquals(1, input.readInt())
        assertEquals('K'.code, input.readUnsignedByte())
        exit(137)
    }

    @Test fun oversizedUnicodeInputIsRejectedAtomicallyAndResizeCannotHideTheError() {
        authenticate()
        console.input("é".repeat(9000))
        assertNotNull(console.state.value.detail)
        console.resize(65, 19)
        assertNotNull(console.state.value.detail)
        console.input("é🙂")
        val input = DataInputStream(peer!!.getInputStream())
        assertEquals(9, input.readInt()); assertEquals('R'.code, input.readUnsignedByte())
        assertEquals(19, input.readInt()); assertEquals(65, input.readInt())
        assertEquals(7, input.readInt()); assertEquals('I'.code, input.readUnsignedByte())
        assertArrayEquals("é🙂".toByteArray(), ByteArray(6).also(input::readFully))
        assertNull(console.state.value.detail)
        exit(0)
    }

    @Test fun controlKeysAndForceStopBypassAFullPendingInputQueue() {
        authenticate()
        val run = session()
        val writes = run.javaClass.getDeclaredField("writes").apply { isAccessible = true }.get(run) as ThreadPoolExecutor
        val input = DataInputStream(peer!!.getInputStream())
        for (kind in listOf('C', 'K')) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            writes.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try {
                repeat(16) { console.input("discard this queued input") }
                assertEquals(16, writes.queue.size)
                if (kind == 'C') console.input("\u0003") else console.forceStop()
                if (kind == 'K') console.interrupt() // A late keyboard event must not cancel the queued kill.
                assertEquals(1, writes.queue.size)
            } finally { release.countDown() }
            assertEquals(1, input.readInt())
            assertEquals(kind.code, input.readUnsignedByte())
        }
        exit(137)
    }

    @Test fun stoppingDuringAuthenticationClosesThatPeerAndDoesNotBlockTheNextRun() {
        val old = Socket("127.0.0.1", plan.arguments[5].toInt()).apply { soTimeout = 2000 }
        old.use {
            it.getOutputStream().write("TERM".toByteArray())
            await { nativeSocketField().get(session()) != null }
            console.forceStop()
            try { assertEquals(-1, it.getInputStream().read()) } catch (_: SocketException) { }
        }
        plan = console.prepare(RunCommands.Plan("/bin/bash", listOf("-c", "true"), "/tmp"), "/bin/python") { code, error -> completions += code to error }
        authenticate()
        exit(0)
        assertEquals(0, completions.last().first)
    }

    @Test fun authenticationHasATotalDeadlineEvenIfThePeerKeepsSendingBytes() {
        Socket("127.0.0.1", plan.arguments[5].toInt()).use {
            it.soTimeout = 2000
            it.getOutputStream().write("TERM".toByteArray())
            await { (nativeSocketField().get(session()) as? Socket)?.soTimeout?.let { timeout -> timeout > 0 } == true }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2100))
            try {
                it.getOutputStream().write('U'.code)
                assertEquals(-1, it.getInputStream().read())
            } catch (_: SocketException) { }
        }
        authenticate()
        exit(0)
    }

    @Test fun anInputWriteFailureCannotOverwriteABufferedExitStatus() {
        authenticate()
        val run = session()
        val field = nativeSocketField()
        val original = field.get(run)
        val failed = AtomicBoolean()
        field.set(run, object : Socket() {
            override fun getOutputStream(): OutputStream { failed.set(true); throw IOException("write failed") }
        })
        try {
            console.input("racing input")
            await { failed.get() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertTrue(console.state.value.active)
        } finally { field.set(run, original) }
        exit(23)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(listOf(23 to null), completions)
        assertEquals(23, console.state.value.exitCode)
    }

    @Test fun aTermuxCallbackCannotCutOffAnAuthenticatedOutputStream() {
        authenticate()
        console.termuxFinished(android.os.Bundle().apply {
            putInt("err", -1); putInt("exitCode", 0)
        })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertTrue(console.state.value.active)
        send('O', "last output".toByteArray())
        await { console.output.after(0) != null }
        exit(37)
        assertEquals("last output", console.output.after(0)!!.bytes.toString(Charsets.UTF_8))
        assertEquals(37, console.state.value.exitCode)
    }

    @Test fun rendererSurvivesHostReplacementAndItsBridgeRunsWhileDetached() {
        authenticate()
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val oldHost = FrameLayout(first.get())
            var oldErrors = 0
            var newErrors = 0
            console.attachRenderer(oldHost) { oldErrors++ }
            val terminal = oldHost.getChildAt(0) as ConsoleTerminalView
            val bridge = shadowOf(terminal).getJavascriptInterface("ConsoleBridge")
            val ready = bridge.javaClass.getDeclaredMethod("ready").apply { isAccessible = true }
            ready.invoke(bridge)
            shadowOf(Looper.getMainLooper()).idle()
            console.detachRenderer(oldHost)
            assertSame(Application.getInstance(), (terminal.context as MutableContextWrapper).baseContext)
            assertFalse(shadowOf(terminal).wasDestroyCalled())
            first.pause().stop().destroy()
            val input = bridge.javaClass.getDeclaredMethod("input", String::class.java).apply { isAccessible = true }
            input.invoke(bridge, "detached response")
            shadowOf(Looper.getMainLooper()).idle()
            val wire = DataInputStream(peer!!.getInputStream())
            assertEquals(18, wire.readInt()); assertEquals('I'.code, wire.readUnsignedByte())
            assertEquals("detached response", ByteArray(17).also(wire::readFully).toString(Charsets.UTF_8))
            val newHost = FrameLayout(second.get())
            console.attachRenderer(newHost) { newErrors++ }
            console.detachRenderer(oldHost)
            assertSame(terminal, newHost.getChildAt(0))
            assertSame(second.get(), (terminal.context as MutableContextWrapper).baseContext)
            console.detachRenderer(newHost)
            console.hide()
            exit(0)
            assertTrue(shadowOf(terminal).wasDestroyCalled())
            assertEquals(0, oldErrors + newErrors)
        } finally { second.pause().stop().destroy() }
    }
}

class ConsoleOutputTest {
    @Test fun boundedHistoryMarksGapsAndKeepsByteSequencesWithoutDecoding() {
        val output = ConsoleOutput(6)
        output.append(byteArrayOf(1, 2, 3))
        output.append(byteArrayOf(4, 5, -16))
        output.append(byteArrayOf(-97, -104, -128))
        val replay = output.after(0)!!
        assertTrue(replay.reset)
        assertArrayEquals(byteArrayOf(4, 5, -16, -97, -104, -128), replay.bytes)
        assertEquals(3L, replay.sequence)
        assertArrayEquals(byteArrayOf(-97, -104, -128), output.after(2)!!.bytes)
        assertFalse(output.after(2)!!.reset)
        assertNull(output.after(3))
        output.clear()
        assertNull(output.after(0))
    }
}
