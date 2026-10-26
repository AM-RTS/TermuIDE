package amrts.termuide

import android.os.Handler
import android.os.Looper
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LspTransportTest {
    @Test fun framingUsesUtf8ByteLengthsAndKeepsConsecutiveMessagesSeparate() {
        val payload = "{\"text\":\"🧪é\"}"
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val frame = "content-length: ${bytes.size}\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n".toByteArray() + bytes
        val input = object : java.io.ByteArrayInputStream(frame + frame) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(1, length))
        }
        assertEquals(payload, Lsp.readPayload(input))
        assertEquals(payload, Lsp.readPayload(input))
        assertNull(Lsp.readPayload(input))
    }

    @Test fun malformedOrTruncatedFramesFailBeforeTheyCanReachTheEditor() {
        val inputs = listOf(
            "Content-Type: application/json\r\n\r\n{}",
            "Content-Length: -1\r\n\r\n",
            "Content-Length: 2147483647\r\n\r\n",
            "Content-Length: 999999999999\r\n\r\n",
            "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
            "Content-Length: 2\r\n\r\n{",
            "Content-Length: 2\r",
            "Content-Length: 2\n\n{}",
            "X: " + "a".repeat(8192) + "\r\n\r\n"
        )
        for (frame in inputs) {
            try { Lsp.readPayload(frame.byteInputStream()); fail("Accepted invalid frame: ${frame.take(50)}") }
            catch (_: java.io.IOException) {}
        }
        try {
            Lsp.readPayload(java.io.ByteArrayInputStream("Content-Length: 1\r\n\r\n".toByteArray() + byteArrayOf(0xff.toByte())))
            fail("Invalid UTF-8 was accepted")
        } catch (_: java.io.IOException) {}
    }

    @Test fun unresponsiveInitializationClosesTheSessionAndAllowsRetry() {
        ServerSocket(0).use { server ->
            server.soTimeout = 3000
            val lsp = Lsp(LanguageServers.Server.PYTHON, null, "127.0.0.1", server.localPort)
            Lsp::class.java.getDeclaredField("mRead").apply { isAccessible = true }.set(lsp, Handler(Looper.getMainLooper()))
            val reader = Thread { lsp.run() }
            reader.start()
            server.accept().use { connection ->
                val socketField = Lsp::class.java.getDeclaredField("sk").apply { isAccessible = true }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                while (!(socketField.get(lsp) as Socket).isConnected && System.nanoTime() < deadline) Thread.sleep(5)
                val socket = socketField.get(lsp) as Socket
                assertTrue(socket.isConnected)
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31))
                reader.join(3000)
                assertFalse(reader.isAlive)
                assertTrue(lsp.hasFailed())
                assertTrue(socket.isClosed)
            }
        }
    }

    @Test fun initializedIdleSessionDoesNotExpireWithTheHandshakeDeadline() {
        ServerSocket(0).use { server ->
            server.soTimeout = 3000
            val lsp = Lsp(LanguageServers.Server.CLANGD, null, "127.0.0.1", server.localPort)
            Lsp::class.java.getDeclaredField("mRead").apply { isAccessible = true }.set(lsp, Handler(Looper.getMainLooper()))
            val reader = Thread { lsp.run() }
            reader.start()
            server.accept().use {
                val field = Lsp::class.java.getDeclaredField("sk").apply { isAccessible = true }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                while (!(field.get(lsp) as Socket).isConnected && System.nanoTime() < deadline) Thread.sleep(5)
                val socket = field.get(lsp) as Socket
                lsp.initialized()
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31))
                assertFalse(socket.isClosed)
                lsp.end()
                reader.join(3000)
                assertFalse(reader.isAlive)
                assertFalse(lsp.hasFailed())
            }
        }
    }

    @Test fun serverEofClosesConnectionAndStopsSender() {
        ServerSocket(0).use { server ->
            server.soTimeout = 3000
            Application.lsp_host = "127.0.0.1"
            Application.lsp_port = server.localPort
            val lsp = Lsp()
            Lsp::class.java.getDeclaredField("mRead").apply { isAccessible = true }.set(lsp, Handler(Looper.getMainLooper()))
            val failure = AtomicReference<Throwable>()
            val reader = Thread { try { lsp.run() } catch (error: Throwable) { failure.set(error) } }
            reader.start()
            server.accept().use { /* Closing the server end must release both transport threads. */ }
            reader.join(3000)
            assertFalse(reader.isAlive)
            assertNull(failure.get())
            val socket = Lsp::class.java.getDeclaredField("sk").apply { isAccessible = true }.get(lsp) as Socket
            assertTrue(socket.isClosed)
            assertTrue(lsp.isEnded)
            assertFalse(lsp.isConnected)
        }
    }
}
