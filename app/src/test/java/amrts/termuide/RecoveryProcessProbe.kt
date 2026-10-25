package amrts.termuide

import java.io.File

/** A separate JVM exits without close(), onStop(), or any shutdown hook. */
object RecoveryProcessProbe {
    @JvmStatic fun main(arguments: Array<String>) {
        val directory = File(arguments[1])
        val first = arguments[2]
        val second = arguments[3]
        val owner = DocumentSessions(directory, { throw it }, 60_000)
        when (arguments[0]) {
            "write" -> {
                owner.open(first) { "first unsaved buffer" }
                owner.open(second) { "second unsaved buffer" }
                owner.changed(first)
                owner.changed(second)
                owner.flush()
            }
            "read" -> {
                check(owner.recoveryText(first) == "first unsaved buffer")
                check(owner.recoveryText(second) == "second unsaved buffer")
                check(File(first).readText() == "first original")
                check(File(second).readText() == "second original")
            }
            else -> error("Unknown probe command")
        }
        Runtime.getRuntime().halt(0)
    }
}
