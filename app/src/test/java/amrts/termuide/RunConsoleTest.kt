package amrts.termuide

import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    private fun exit(code: Int) { send('E', ByteBuffer.allocate(4).putInt(code).array()); await { completions.isNotEmpty() } }

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
