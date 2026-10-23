package cn.rbc.termuc

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.function.Consumer

/** Editor-independent recovery records. The source document is never written here. */
internal class DraftStore(private val directory: File) {
    data class Record(val session: DocumentSession, val order: Long, val text: String?)

    fun readAll(onError: Consumer<IOException>): List<Record> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles()
        if (files == null) {
            onError.accept(IOException("Cannot read document recovery directory"))
            return emptyList()
        }
        return files.filter { it.name.endsWith(".draft") }.mapNotNull { file ->
            try {
                DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                    if (input.readInt() != MAGIC) throw IOException("Unknown draft format")
                    val version = input.readInt()
                    if (version !in 1..VERSION) throw IOException("Unknown draft format")
                    val path = input.readUTF()
                    val revision = input.readLong()
                    val dirty = input.readBoolean()
                    val order = input.readLong()
                    val length = input.readInt()
                    if (!File(path).isAbsolute || file.name != fileFor(path).name || revision < 0 || order < 0 ||
                        length < -1 || dirty != (length >= 0) || length.toLong() * 2 > input.available()) {
                        throw IOException("Invalid document recovery record")
                    }
                    // UTF-16 code units also preserve temporarily unpaired surrogates during editing.
                    val text = if (length < 0) null else String(CharArray(length) { input.readChar() })
                    val sourceLoaded = if (version >= 2) input.readBoolean() else true
                    if (input.read() != -1) throw IOException("Trailing draft data")
                    Record(DocumentSession(path, revision, dirty, sourceLoaded), order, text)
                }
            } catch (error: IOException) {
                onError.accept(error)
                null
            }
        }.sortedBy { it.order }
    }

    fun write(record: Record) {
        Files.createDirectories(directory.toPath())
        val target = fileFor(record.session.path)
        val temporary = File(directory, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { file ->
                val output = DataOutputStream(BufferedOutputStream(file))
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(record.session.path)
                output.writeLong(record.session.revision)
                output.writeBoolean(record.session.dirty)
                output.writeLong(record.order)
                output.writeInt(record.text?.length ?: -1)
                record.text?.forEach { output.writeChar(it.code) }
                output.writeBoolean(record.session.sourceLoaded)
                output.flush()
                file.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            if (temporary.isFile) temporary.delete()
        }
    }

    fun remove(path: String) {
        val target = fileFor(path)
        Files.deleteIfExists(target.toPath())
        Files.deleteIfExists(File(directory, target.name + ".tmp").toPath())
    }

    private fun fileFor(path: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        val name = buildString {
            digest.forEach { byte ->
                append(Character.forDigit((byte.toInt() ushr 4) and 15, 16))
                append(Character.forDigit(byte.toInt() and 15, 16))
            }
        }
        return File(directory, "$name.draft")
    }

    companion object {
        private const val MAGIC = 0x54434452
        private const val VERSION = 2
    }
}
