package amrts.termuide

import android.os.Looper
import android.app.Activity
import android.content.MutableContextWrapper
import android.preference.PreferenceManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
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

    @Test fun pinchResizesCoalesceWithoutCrowdingOutInputAndKeepTheLatestDimensions() {
        authenticate()
        val run = session()
        val writes = run.javaClass.getDeclaredField("writes").apply { isAccessible = true }.get(run) as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        writes.execute { entered.countDown(); release.await() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            repeat(100) { console.resize(40 + it, 20 + it) }
            assertEquals(1, writes.queue.size)
            console.input("typed during pinch")
            assertEquals(2, writes.queue.size)
            assertNull(console.state.value.detail)
        } finally { release.countDown() }
        val input = DataInputStream(peer!!.getInputStream())
        assertEquals(9, input.readInt()); assertEquals('R'.code, input.readUnsignedByte())
        assertEquals(119, input.readInt()); assertEquals(139, input.readInt())
        assertEquals(19, input.readInt()); assertEquals('I'.code, input.readUnsignedByte())
        assertEquals("typed during pinch", ByteArray(18).also(input::readFully).toString(Charsets.UTF_8))
        exit(0)
    }

    @Test fun aFullInputQueueStillPublishesTheLastResizeWithoutRejectingTypedInput() {
        authenticate()
        val run = session()
        val writes = run.javaClass.getDeclaredField("writes").apply { isAccessible = true }.get(run) as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        writes.execute { entered.countDown(); release.await() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            repeat(16) { console.input("x") }
            console.resize(71, 23)
            console.resize(63, 21)
            assertEquals(16, writes.queue.size)
            assertNull(console.state.value.detail)
        } finally { release.countDown() }
        val input = DataInputStream(peer!!.getInputStream())
        assertEquals(9, input.readInt()); assertEquals('R'.code, input.readUnsignedByte())
        assertEquals(21, input.readInt()); assertEquals(63, input.readInt())
        repeat(16) {
            assertEquals(2, input.readInt()); assertEquals('I'.code, input.readUnsignedByte())
            assertEquals('x'.code, input.readUnsignedByte())
        }
        exit(0)
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

    @Test fun consoleTextSizeSurvivesNewControllersAndResetUsesEditorDefault() {
        val original = Application.textsize
        val replacement = RunConsole(Application.getInstance())
        try {
            Application.textsize = 14
            console.resetTextSize()
            PreferenceManager.getDefaultSharedPreferences(Application.getInstance()).edit()
                .putString("console_text_size", "invalid restored preference").apply()
            assertEquals(14, console.textSize)
            console.setTextSize(22)
            assertEquals(22, replacement.textSize)
            Application.textsize = 16
            assertEquals(22, console.textSize)
            console.setTextSize(100)
            assertEquals(32, replacement.textSize)
            console.setTextSize(1)
            assertEquals(8, replacement.textSize)
            replacement.resetTextSize()
            assertEquals(16, console.textSize)
        } finally {
            console.resetTextSize()
            Application.textsize = original
            replacement.shutdown()
        }
    }

    @Test fun onlyTwoFingerGesturesResizeTextAndCancellationEndsTheGesture() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val original = Application.textsize
        val accessibility = shadowOf(Application.getInstance().getSystemService(AccessibilityManager::class.java))
        try {
            Application.textsize = 14
            console.resetTextSize()
            val host = FrameLayout(activity.get())
            console.attachRenderer(host) { fail("Renderer failed") }
            val terminal = host.getChildAt(0) as ConsoleTerminalView
            val start = SystemClock.uptimeMillis()
            var elapsed = 0L
            fun touch(action: Int, vararg positions: Float) {
                elapsed += 32
                val properties = Array(positions.size) { index -> MotionEvent.PointerProperties().apply {
                    id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coordinates = Array(positions.size) { index -> MotionEvent.PointerCoords().apply {
                    x = positions[index]; y = 200f; pressure = 1f; size = 1f
                } }
                MotionEvent.obtain(start, start + elapsed, action, positions.size, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).also {
                    terminal.onTouchEvent(it)
                    it.recycle()
                }
            }
            touch(MotionEvent.ACTION_DOWN, 100f)
            touch(MotionEvent.ACTION_MOVE, 300f)
            touch(MotionEvent.ACTION_UP, 300f)
            assertEquals(14, console.textSize)
            touch(MotionEvent.ACTION_DOWN, 100f)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 300f)
            touch(MotionEvent.ACTION_MOVE, 100f, 500f)
            touch(MotionEvent.ACTION_MOVE, 100f, 700f)
            assertTrue("Pinch out must enlarge text", console.textSize > 14)
            val enlarged = console.textSize
            touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 700f)
            touch(MotionEvent.ACTION_MOVE, 250f)
            touch(MotionEvent.ACTION_UP, 250f)
            assertEquals(enlarged, console.textSize)
            touch(MotionEvent.ACTION_DOWN, 100f)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 700f)
            touch(MotionEvent.ACTION_MOVE, 100f, 500f)
            touch(MotionEvent.ACTION_MOVE, 100f, 300f)
            touch(MotionEvent.ACTION_CANCEL, 100f, 300f)
            assertTrue("Pinch in must shrink text", console.textSize < enlarged)
            val reduced = console.textSize
            touch(MotionEvent.ACTION_DOWN, 100f)
            touch(MotionEvent.ACTION_MOVE, 400f)
            touch(MotionEvent.ACTION_UP, 400f)
            assertEquals(reduced, console.textSize)
            accessibility.setTouchExplorationEnabled(true)
            touch(MotionEvent.ACTION_DOWN, 100f)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 300f)
            touch(MotionEvent.ACTION_MOVE, 100f, 500f)
            touch(MotionEvent.ACTION_MOVE, 100f, 700f)
            touch(MotionEvent.ACTION_UP, 100f)
            assertEquals("Touch exploration keeps its two-finger gestures", reduced, console.textSize)
        } finally {
            accessibility.setTouchExplorationEnabled(false)
            console.resetTextSize()
            Application.textsize = original
            activity.pause().stop().destroy()
        }
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
            assertFalse(shadowOf(terminal).wasDestroyCalled())
            console.show()
            console.attachRenderer(newHost) { newErrors++ }
            assertSame(terminal, newHost.getChildAt(0))
            console.prepare(RunCommands.Plan("/bin/true", emptyList(), "/tmp"), "/bin/python") { _, _ -> }
            assertTrue(shadowOf(terminal).wasDestroyCalled())
            assertEquals(0, oldErrors + newErrors)
        } finally { second.pause().stop().destroy() }
    }

    private fun readyRenderer(host: FrameLayout): ConsoleTerminalView {
        console.attachRenderer(host) { fail("Renderer failed") }
        return (host.getChildAt(0) as ConsoleTerminalView).also { terminal ->
            terminal.configure(0xff000000.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt())
            val bridge = shadowOf(terminal).getJavascriptInterface("ConsoleBridge")
            bridge.javaClass.getDeclaredMethod("ready").apply { isAccessible = true }.invoke(bridge)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun copySnapshotSurvivesHostReplacementWhileOutputInputAndStopKeepWorking() {
        authenticate()
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val host = FrameLayout(first.get())
            val terminal = readyRenderer(host)
            console.selectOutput()
            assertNull(console.state.value.selection!!.text)
            assertFalse(terminal.isFocusable)
            assertFalse(terminal.requestFocus())
            assertEquals(FrameLayout.FOCUS_BLOCK_DESCENDANTS, host.descendantFocusability)
            val text = "first\n界🙂 \"quoted\" \\path"
            shadowOf(terminal).lastEvaluatedJavascriptCallback.onReceiveValue(
                org.json.JSONObject().put("text", text).put("truncated", false).toString())
            val snapshot = console.state.value.selection
            assertEquals(text, snapshot!!.text)
            console.detachRenderer(host)
            first.pause().stop().destroy()
            val replacement = FrameLayout(second.get())
            console.attachRenderer(replacement) { fail("Renderer failed") }
            assertSame(terminal, replacement.getChildAt(0))
            assertFalse(terminal.isFocusable)
            assertEquals(FrameLayout.FOCUS_BLOCK_DESCENDANTS, replacement.descendantFocusability)
            assertSame(snapshot, console.state.value.selection)
            send('O', "new output".toByteArray())
            await { console.output.after(0) != null }
            assertEquals(text, console.state.value.selection!!.text)
            val bridge = shadowOf(terminal).getJavascriptInterface("ConsoleBridge")
            bridge.javaClass.getDeclaredMethod("input", String::class.java).apply { isAccessible = true }.invoke(bridge, "still live")
            shadowOf(Looper.getMainLooper()).idle()
            val wire = DataInputStream(peer!!.getInputStream())
            assertEquals(11, wire.readInt()); assertEquals('I'.code, wire.readUnsignedByte())
            assertEquals("still live", ByteArray(10).also(wire::readFully).toString(Charsets.UTF_8))
            console.forceStop()
            assertEquals(1, wire.readInt()); assertEquals('K'.code, wire.readUnsignedByte())
            exit(137)
            assertSame(snapshot, console.state.value.selection)
            console.closeSelection()
            assertNull(console.state.value.selection)
            assertTrue(terminal.isFocusableInTouchMode)
            assertEquals(FrameLayout.FOCUS_AFTER_DESCENDANTS, replacement.descendantFocusability)
        } finally { second.pause().stop().destroy() }
    }

    @Test fun dismissedAndHiddenCopyRequestsCannotReopenOrOverwriteANewerSnapshot() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val terminal = readyRenderer(FrameLayout(activity.get()))
            val payload = org.json.JSONObject().put("text", "old").put("truncated", false).toString()
            console.selectOutput()
            val oldCallback = shadowOf(terminal).lastEvaluatedJavascriptCallback
            console.closeSelection()
            console.selectOutput()
            val newCallback = shadowOf(terminal).lastEvaluatedJavascriptCallback
            oldCallback.onReceiveValue(payload)
            assertNull(console.state.value.selection!!.text)
            newCallback.onReceiveValue(payload.replace("old", "new"))
            assertEquals("new", console.state.value.selection!!.text)
            console.closeSelection()
            console.selectOutput()
            val hiddenCallback = shadowOf(terminal).lastEvaluatedJavascriptCallback
            console.hide()
            hiddenCallback.onReceiveValue(payload)
            assertNull(console.state.value.selection)
            assertFalse(console.state.value.visible)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun copyFailuresAreBoundedAndCannotChangeRunStatus() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val terminal = readyRenderer(FrameLayout(activity.get()))
            for (payload in listOf("null", "{}", org.json.JSONObject().put("text", "x".repeat(65537)).put("truncated", true).toString())) {
                console.selectOutput()
                shadowOf(terminal).lastEvaluatedJavascriptCallback.onReceiveValue(payload)
                assertTrue(console.state.value.selection!!.failed)
                assertEquals(RunConsole.Phase.CONNECTING, console.state.value.phase)
                console.closeSelection()
            }
            console.selectOutput()
            val late = shadowOf(terminal).lastEvaluatedJavascriptCallback
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            assertTrue(console.state.value.selection!!.failed)
            late.onReceiveValue(org.json.JSONObject().put("text", "late").put("truncated", false).toString())
            assertTrue(console.state.value.selection!!.failed)
            assertEquals(RunConsole.Phase.CONNECTING, console.state.value.phase)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun copyWaitsForAllQueuedOutputToBeRenderedBeforeTakingItsSnapshot() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val terminal = readyRenderer(FrameLayout(activity.get()))
            repeat(5) { console.output.append("queued $it\n".toByteArray()) }
            console.selectOutput()
            assertTrue(shadowOf(terminal).lastEvaluatedJavascript.startsWith("consoleTerminal.write("))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            console.closeSelection()
            console.selectOutput()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse("The old request's timeout must not cancel its replacement", console.state.value.selection!!.failed)
            val bridge = shadowOf(terminal).getJavascriptInterface("ConsoleBridge")
            val rendered = bridge.javaClass.getDeclaredMethod("rendered", String::class.java).apply { isAccessible = true }
            for (sequence in listOf("2", "4")) {
                rendered.invoke(bridge, sequence)
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(console.state.value.selection!!.text)
                assertTrue(shadowOf(terminal).lastEvaluatedJavascript.startsWith("consoleTerminal.write("))
            }
            rendered.invoke(bridge, "5")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("consoleTerminal.snapshotText()", shadowOf(terminal).lastEvaluatedJavascript)
            val text = (0..4).joinToString("") { "queued $it\n" }
            shadowOf(terminal).lastEvaluatedJavascriptCallback.onReceiveValue(
                org.json.JSONObject().put("text", text).put("truncated", false).toString())
            assertEquals(text, console.state.value.selection!!.text)
        } finally { activity.pause().stop().destroy() }
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
