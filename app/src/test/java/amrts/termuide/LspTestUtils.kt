package amrts.termuide

/** A connected in-memory sender; never launches a server or opens a device connection. */
internal fun Lsp.connectedSender(): Lsp.Sender {
    val connected = object : java.net.Socket() { override fun isConnected() = true }
    Lsp::class.java.getDeclaredField("sk").apply { isAccessible = true }.set(this, connected)
    Lsp::class.java.getDeclaredField("ready").apply { isAccessible = true }.setBoolean(this, true)
    return Lsp::class.java.getDeclaredField("mSndr").apply { isAccessible = true }.get(this) as Lsp.Sender
}
