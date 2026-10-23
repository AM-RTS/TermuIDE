package cn.rbc.termuc

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
