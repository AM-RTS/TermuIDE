package amrts.termuide

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DebugSessionTest {
    private class Fixture : AutoCloseable {
        val updates = mutableListOf<DebugSession.Snapshot>()
        val session = DebugSession { updates += it }
        val sender = DebugSession.Sender(Socket())
        init { DebugSession::class.java.getDeclaredField("sender").apply { isAccessible = true }.set(session, sender) }
        fun command(): String = sender.poll(2, TimeUnit.SECONDS) ?: error("Missing GDB command")
        fun token(command: String) = command.takeWhile { it.isDigit() }
        val state get() = updates.last()
        override fun close() = session.close()
    }

    @Test fun parserHandlesTokensNestedListsAndEscapedStrings() {
        val record = GdbMi.parse("42^done,stack-args=[frame={level=\"0\",args=[name=\"argc\"]}]")!!
        assertEquals("42", record.token)
        val frame = ((record.values["stack-args"] as List<*>).single() as Map<*, *>)["frame"] as Map<*, *>
        assertEquals("argc", ((frame["args"] as List<*>).single() as Map<*, *>)["name"])
        assertEquals("line\nquote\" slash\\ tab\t", GdbMi.parse("~\"line\\nquote\\\" slash\\\\ tab\\t\"")!!.text)
        assertNull(GdbMi.parse("(gdb)"))
        assertThrows(IOException::class.java) { GdbMi.parse("^done,stack=[frame={level=\"0\"") }
    }

    @Test fun malformedStringsAndTrailingStreamDataAreRejected() {
        for (line in listOf("~\"unfinished", "^done,name=\"unfinished", "~\"valid\" garbage"))
            assertThrows(line, IOException::class.java) { GdbMi.parse(line) }
    }

    @Test fun runningInvalidatesPendingVariableAndFrameRequests() {
        Fixture().use { f ->
            f.session.accept("*stopped")
            f.session.refreshVariables()
            val args = f.token(f.command())
            f.session.refreshFrames()
            val frames = f.token(f.command())
            f.session.accept("*running,thread-id=\"all\"")
            f.session.accept("$args^done,stack-args=[frame={args=[name=\"old\"]}]")
            f.session.accept("$frames^done,stack=[frame={level=\"0\",func=\"old\"}]")
            assertNull("Late arguments must not queue evaluations while running", f.sender.poll())
            assertTrue(f.state.frames.isEmpty())
            assertTrue(f.state.variables.isEmpty())
        }
    }

    @Test fun refreshingAfterExpandingChildrenDeletesTheRootObject() {
        Fixture().use { f ->
            f.session.accept("*stopped")
            f.session.refreshVariables()
            val args = f.token(f.command())
            f.session.accept("$args^done,stack-args=[frame={args=[name=\"parent\"]}]")
            val variable = f.token(f.command())
            val locals = f.token(f.command())
            f.session.accept("$locals^done,locals=[]")
            f.session.accept("$variable^done,name=\"root\",numchild=\"1\",value=\"{...}\",type=\"Struct\"")
            f.session.expandVariable(f.state.variables.single())
            val children = f.token(f.command())
            f.session.accept("$children^done,children=[child={name=\"root.x\",exp=\"x\",numchild=\"0\",value=\"1\"}]")
            f.session.refreshVariables()
            assertEquals("-var-delete root", f.command())
        }
    }

    @Test fun malformedVariableResultsNeverCreateEmptyUiKeys() {
        Fixture().use { f ->
            f.session.accept("*stopped")
            f.session.refreshVariables()
            val args = f.token(f.command())
            f.session.accept("$args^done,stack-args=[frame={args=[name=\"valid\"]}]")
            val variable = f.token(f.command())
            f.command()
            f.session.accept("$variable^done,value=\"missing object name\"")
            assertTrue(f.state.variables.isEmpty())
        }
    }

    @Test fun argumentAndLocalResultsCanArriveOutOfOrderAmongAsyncEvents() {
        Fixture().use { f ->
            f.session.accept("*stopped,reason=\"breakpoint-hit\",bkptno=\"1\"")
            f.session.refreshVariables()
            val arguments = f.token(f.command())
            f.session.accept("$arguments^done,stack-args=[frame={level=\"0\",args=[name=\"argc\"]}]")
            val argument = f.token(f.command())
            val locals = f.token(f.command())
            f.session.accept("~\"a real newline\\n\"")
            f.session.accept("$locals^done,locals=[name=\"counter\"]")
            val local = f.token(f.command())
            f.session.accept("$local^done,name=\"var2\",numchild=\"0\",value=\"7\",type=\"int\"")
            val previous = f.state
            f.session.accept("$argument^done,name=\"var1\",numchild=\"0\",value=\"1\",type=\"int\"")
            assertEquals(listOf("argc", "counter"), f.state.variables.map { it.expression })
            assertEquals(listOf("1", "7"), f.state.variables.map { it.value })
            assertEquals(1, previous.variables.size)
            assertEquals("counter", previous.variables.single().expression)
            assertEquals("a real newline\n", f.state.console)
            assertThrows(UnsupportedOperationException::class.java) { (f.state.variables as MutableList).clear() }
        }
    }

    @Test fun framesChildrenConsoleAndErrorsRetainTheirExistingFeatures() {
        Fixture().use { f ->
            f.session.accept("*stopped,reason=\"signal-received\"")
            f.session.refreshFrames()
            val frames = f.token(f.command())
            f.session.accept("$frames^done,stack=[frame={level=\"0\",func=\"main\",file=\"main.c\",line=\"6\"},frame={level=\"1\",func=\"start\",from=\"libc.so\"}]")
            assertEquals(listOf("#0 main() main.c:6", "#1 start() libc.so"), f.state.frames)
            val parent = DebugSession.Variable(mapOf("name" to "var1", "numchild" to "1"), "parent")
            f.session.expandVariable(parent)
            val children = f.token(f.command())
            f.session.accept("$children^done,numchild=\"1\",children=[child={name=\"var1.x\",exp=\"x\",numchild=\"0\",value=\"2\",type=\"int\"}]")
            assertEquals("x", f.state.variables.single().expression)
            assertEquals("2", f.state.variables.single().value)
            f.session.expandVariable(parent)
            val rejectedChildren = f.token(f.command())
            f.session.accept("$rejectedChildren^error,msg=\"Cannot expand variable\"")
            assertEquals("x", f.state.variables.single().expression)
            f.session.refreshFrames()
            val rejectedFrames = f.token(f.command())
            f.session.accept("$rejectedFrames^error,msg=\"No stack available\"")
            assertEquals(2, f.state.frames.size)
            f.session.console("print \"hello\"")
            assertTrue(f.command().contains("\\\"hello\\\""))
            assertTrue(f.state.console.contains("(gdb) print \"hello\""))
            f.session.accept("^error,msg=\"Cannot evaluate expression\"")
            assertTrue(f.state.debug.contains("Cannot evaluate expression"))
            f.session.accept("*stopped,reason=\"exited-normally\"")
            assertTrue(f.state.exited)
        }
    }

    @Test fun refreshingDiscardsStaleVariableResultsAndDeletesTheirGdbObjects() {
        Fixture().use { f ->
            f.session.accept("*stopped")
            f.session.refreshVariables()
            val args = f.token(f.command())
            f.session.accept("$args^done,stack-args=[frame={args=[{name=\"old\"}]}]")
            val variable = f.token(f.command())
            f.command() // locals
            f.session.refreshVariables()
            f.command() // new argument request
            f.session.accept("$variable^done,name=\"old-object\",numchild=\"0\",value=\"99\",type=\"int\"")
            assertTrue(f.state.variables.isEmpty())
            assertEquals("-var-delete old-object", f.command())
        }
    }

    @Test fun transportConnectsSendsInitialCommandsAndPublishesDisconnectOnEof() {
        ServerSocket(0).use { server ->
            val updates = CopyOnWriteArrayList<DebugSession.Snapshot>()
            val disconnected = CountDownLatch(1)
            val commands = CopyOnWriteArrayList<String>()
            val peer = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    repeat(4) { commands += input.readLine() }
                    socket.getOutputStream().write("1^done,bkpt={number=\"7\"}\n*stopped,reason=\"breakpoint-hit\",bkptno=\"7\"\n".toByteArray())
                    socket.getOutputStream().flush()
                }
            }.apply { isDaemon = true; start() }
            DebugSession(server.localPort) { state ->
                updates += state
                if (!state.connected && updates.any { it.connected }) disconnected.countDown()
            }.use { session ->
                session.connect(listOf("main.c:6"))
                assertTrue(disconnected.await(5, TimeUnit.SECONDS))
                peer.join(1000)
                assertEquals(listOf("-gdb-set mi-async 1", "-enable-pretty-printing", "1-break-insert \"main.c:6\"", "-exec-run"), commands)
                assertTrue(updates.any { it.status == DebugSession.STOPPED && it.hitBreakpoint == 1 })
                assertFalse(updates.last().connected)
            }
        }
    }

    @Test fun breakpointsUseQuotedLocationsAndActualIdsEvenAfterAnInsertionFails() {
        ServerSocket(0).use { server ->
            val commands = CopyOnWriteArrayList<String>()
            val ready = CountDownLatch(1)
            val reply = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val updates = CopyOnWriteArrayList<DebugSession.Snapshot>()
            val peer = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    repeat(5) { commands += input.readLine() }
                    ready.countDown()
                    check(reply.await(5, TimeUnit.SECONDS))
                    val output = socket.getOutputStream()
                    output.write("1^error,msg=\"Missing source\"\n2^done,bkpt={number=\"42\"}\n".toByteArray())
                    output.flush()
                    commands += input.readLine()
                    output.write("*stopped,reason=\"breakpoint-hit\",bkptno=\"42\"\n".toByteArray())
                    output.flush()
                    commands += input.readLine()
                }
            }.apply { isDaemon = true; start() }
            DebugSession(server.localPort) { state ->
                updates += state
                if (state.status == DebugSession.STOPPED) stopped.countDown()
            }.use { session ->
                try {
                    session.connect(listOf("missing.c:1", "file name \"quoted\".cpp:7"))
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    session.toggleBreakpoint(1, false) // The insertion result has not arrived yet.
                    reply.countDown()
                    assertTrue(stopped.await(5, TimeUnit.SECONDS))
                    session.toggleBreakpoint(1, true)
                    peer.join(5000)
                    assertFalse(peer.isAlive)
                    assertEquals("2-break-insert \"file name \\\"quoted\\\".cpp:7\"", commands[3])
                    assertEquals(listOf("-break-disable 42", "-break-enable 42"), commands.takeLast(2))
                    assertTrue(updates.any { it.hitBreakpoint == 2 })
                } finally { reply.countDown() }
            }
        }
    }
}
