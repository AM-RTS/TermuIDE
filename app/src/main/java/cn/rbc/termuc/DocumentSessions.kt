package cn.rbc.termuc

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Supplier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DocumentSession(val path: String, val revision: Long, val dirty: Boolean, val sourceLoaded: Boolean = true)

class DocumentSessions(
    directory: File,
    private val onError: Consumer<IOException>,
    private val debounceMillis: Long
) : Closeable {
    private class Entry(var session: DocumentSession, val order: Long, var text: String?) {
        var source: Supplier<String>? = null
        var pending: ScheduledFuture<*>? = null
        var scheduleVersion = 0L
    }

    private val store = DraftStore(directory)
    private val entries = linkedMapOf<String, Entry>()
    private val failures = mutableMapOf<String, IOException>()
    private val mutableState = MutableStateFlow<List<DocumentSession>>(emptyList())
    val state: StateFlow<List<DocumentSession>> = mutableState.asStateFlow()
    private val writer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "Document recovery").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var nextOrder = 0L

    init {
        store.readAll(onError).forEach { record ->
            entries[record.session.path] = Entry(record.session, record.order, record.text)
            nextOrder = maxOf(nextOrder, record.order + 1)
        }
        publish()
    }

    @Synchronized
    fun open(path: String, source: Supplier<String>) = open(path, source, isSourceLoaded(path))

    @Synchronized
    fun open(path: String, source: Supplier<String>, sourceLoaded: Boolean) {
        require(File(path).isAbsolute)
        val entry = entries.getOrPut(path) { Entry(DocumentSession(path, 0, false), nextOrder++, null) }
        entry.source = source
        entry.text = null
        if (entry.session.sourceLoaded != sourceLoaded)
            entry.session = entry.session.copy(revision = entry.session.revision + 1, sourceLoaded = sourceLoaded)
        publish()
        queue(entry, 0)
    }

    @Synchronized
    fun changed(path: String) {
        val entry = entries[path] ?: return
        entry.session = entry.session.copy(revision = entry.session.revision + 1, dirty = true)
        publish()
        queue(entry, debounceMillis)
    }

    @Synchronized
    fun setDirty(path: String, dirty: Boolean) {
        val entry = entries[path] ?: return
        if (entry.session.dirty == dirty) return
        entry.session = entry.session.copy(dirty = dirty)
        publish()
        queue(entry, debounceMillis)
    }

    @Synchronized
    fun isDirty(path: String): Boolean = entries[path]?.session?.dirty == true

    @Synchronized
    fun isSourceLoaded(path: String): Boolean = entries[path]?.session?.sourceLoaded ?: true

    @Synchronized
    fun setSourceLoaded(path: String, loaded: Boolean) {
        val entry = entries[path] ?: return
        if (entry.session.sourceLoaded == loaded) return
        entry.session = entry.session.copy(revision = entry.session.revision + 1, sourceLoaded = loaded)
        publish()
        queue(entry, 0)
    }

    @Synchronized
    fun saved(path: String) {
        val entry = entries[path] ?: return
        entry.session = entry.session.copy(revision = entry.session.revision + 1, dirty = false)
        entry.text = null
        publish()
        queue(entry, 0)
    }

    class SavePoint internal constructor(val path: String, internal val revision: Long, internal val owner: Any)

    @Synchronized
    fun prepareSave(path: String): SavePoint {
        val entry = entries[path] ?: error("Document is not open")
        check(entry.session.sourceLoaded) { "Source was not loaded successfully" }
        return SavePoint(path, entry.session.revision, entry)
    }

    @Synchronized
    fun owns(point: SavePoint): Boolean = entries[point.path] === point.owner

    @Synchronized
    fun isCurrent(point: SavePoint): Boolean = entries[point.path]?.let {
        it === point.owner && it.session.revision == point.revision && !it.session.dirty
    } == true

    @Synchronized
    fun completeSave(point: SavePoint): Boolean {
        val entry = entries[point.path] ?: return false
        if (entry !== point.owner || entry.session.revision != point.revision) return false
        saved(point.path)
        return true
    }

    @Synchronized
    fun discard(path: String) {
        val entry = entries.remove(path) ?: return
        entry.pending?.cancel(false)
        publish()
        writer.execute { attempt(path) { store.remove(path) } }
    }

    @Synchronized
    fun closeDocument(path: String) {
        check(!isDirty(path)) { "Save or explicitly discard changes before closing" }
        discard(path)
    }

    @Synchronized
    fun closeIfSource(path: String, source: Supplier<String>) {
        val entry = entries[path] ?: return
        if (entry.source === source && !entry.session.dirty) closeDocument(path)
    }

    fun detach(path: String, source: Supplier<String>) {
        synchronized(this) { if (entries[path]?.source !== source) return }
        val text = source.get()
        synchronized(this) {
            val entry = entries[path] ?: return
            if (entry.source !== source) return
            entry.text = if (entry.session.dirty) text else null
            entry.source = null
            queue(entry, 0)
        }
    }

    fun recoveryText(path: String): String? {
        val entry: Entry
        val source: Supplier<String>?
        synchronized(this) {
            entry = entries[path]?.takeIf { it.session.dirty } ?: return null
            source = entry.source
            if (source == null) return entry.text
        }
        return source?.get()
    }

    @Throws(IOException::class)
    fun flush() {
        synchronized(this) {
            entries.values.filter {
                it.source != null || it.pending?.isDone == false || failures.containsKey(it.session.path)
            }.forEach { queue(it, 0) }
            failures.keys.filter { it !in entries }.forEach { path ->
                writer.execute { attempt(path) { store.remove(path) } }
            }
        }
        try {
            writer.submit {}.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while saving recovery data", error)
        } catch (error: ExecutionException) {
            throw IOException("Cannot save recovery data", error.cause)
        }
        synchronized(this) { failures.values.firstOrNull()?.let { throw it } }
    }

    @Throws(IOException::class)
    override fun close() {
        try { flush() } finally { writer.shutdownNow() }
    }

    private fun publish() {
        mutableState.value = entries.values.map { it.session }
    }

    private fun queue(entry: Entry, delay: Long) {
        if (delay > 0 && entry.pending?.isDone == false) return
        entry.pending?.cancel(false)
        val version = ++entry.scheduleVersion
        entry.pending = writer.schedule({ checkpoint(entry, version) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun checkpoint(entry: Entry, version: Long) {
        val session: DocumentSession
        val source: Supplier<String>?
        val cached: String?
        synchronized(this) {
            if (entries[entry.session.path] !== entry || entry.scheduleVersion != version) return
            entry.pending = null
            session = entry.session
            source = entry.source
            cached = entry.text
        }
        // Reading the editor buffer outside our lock avoids an inversion with change callbacks.
        val text = try {
            if (session.dirty) source?.get() ?: cached else null
        } catch (error: Exception) {
            attempt(session.path) { throw IOException("Cannot read the unsaved document", error) }
            return
        }
        synchronized(this) {
            if (entries[session.path] !== entry || entry.session.dirty != session.dirty ||
                entry.session.sourceLoaded != session.sourceLoaded ||
                entry.source !== source || (source == null && entry.text != cached)) return
        }
        attempt(session.path) {
            if (session.dirty && text == null) throw IOException("Missing unsaved document content")
            store.write(DraftStore.Record(session, entry.order, text))
        }
    }

    private fun attempt(path: String, operation: () -> Unit) {
        try {
            operation()
            synchronized(this) { failures.remove(path) }
        } catch (error: IOException) {
            synchronized(this) { failures[path] = error }
            onError.accept(error)
        }
    }
}
