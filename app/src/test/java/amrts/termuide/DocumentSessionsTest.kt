package amrts.termuide

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentSessionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory get() = File(temporary.root, "sessions")
    private fun sessions(delay: Long = 60_000) = DocumentSessions(directory, { }, delay)

    @Test fun restartRestoresMultipleDraftsWithoutChangingOriginals() {
        val first = temporary.newFile("first.txt").apply { writeText("disk one") }
        val second = temporary.newFile("second.cpp").apply { writeText("disk two") }
        sessions().use { owner ->
            owner.open(first.path) { "draft one\r\n🙂\uD800" }
            owner.open(second.path) { "x".repeat(90_000) }
            owner.changed(first.path)
            owner.changed(second.path)
            owner.flush()
            sessions().use { restarted ->
                assertEquals(listOf(first.path, second.path), restarted.state.value.map { it.path })
                assertEquals("draft one\r\n🙂\uD800", restarted.recoveryText(first.path))
                assertEquals("x".repeat(90_000), restarted.recoveryText(second.path))
                assertTrue(restarted.state.value.all { it.dirty && it.revision > 0 })
            }
        }
        assertEquals("disk one", first.readText())
        assertEquals("disk two", second.readText())
    }

    @Test fun debounceCapturesLatestContentWithoutLifecycleCallback() {
        val path = temporary.newFile("typing.txt").path
        val text = AtomicReference("first")
        sessions(25).use { owner ->
            owner.open(path) { text.get() }
            owner.changed(path)
            text.set("latest")
            owner.changed(path)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var restored: String? = null
            while (System.nanoTime() < deadline) {
                sessions().use { restored = it.recoveryText(path) }
                if (restored == "latest") break
                Thread.sleep(15)
            }
            assertEquals("latest", restored)
        }
    }

    @Test fun continuousTypingStillProducesRecoveryCheckpoints() {
        val path = temporary.newFile("continuous.txt").path
        val text = AtomicReference("start")
        sessions(30).use { owner ->
            owner.open(path) { text.get() }
            var checkpointDuringTyping = false
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350)
            while (System.nanoTime() < deadline) {
                text.set(text.get() + "x")
                owner.changed(path)
                // Windows can briefly deny a read while the writer atomically replaces this file.
                if (DraftStore(directory).readAll { }.any { it.session.dirty }) {
                    checkpointDuringTyping = true
                }
                Thread.sleep(5)
            }
            assertTrue("Typing must not postpone recovery indefinitely", checkpointDuringTyping)
        }
    }

    @Test fun typingDuringSnapshotDoesNotDiscardTheOnlyCheckpoint() {
        val path = temporary.newFile("snapshot-typing.txt").path
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sessions().use { owner ->
            owner.open(path) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "checkpoint text"
            }
            owner.flush()
            owner.changed(path)
            val flushing = Thread { owner.flush() }.apply { start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.changed(path)
            release.countDown()
            flushing.join(5000)
            assertFalse(flushing.isAlive)
            assertEquals("checkpoint text", DraftStore(directory).readAll { throw AssertionError(it) }.single().text)
        }
    }

    @Test fun saveAndExplicitDiscardPreventDraftResurrection() {
        val path = temporary.newFile("saved.txt").path
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sessions(10).use { owner ->
            owner.open(path) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "unsaved"
            }
            owner.changed(path)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.saved(path)
            release.countDown()
            owner.flush()
            sessions().use { assertNull(it.recoveryText(path)) }
            owner.changed(path)
            owner.discard(path)
            owner.flush()
            sessions().use { assertTrue(it.state.value.isEmpty()) }
        }
    }

    @Test fun dirtyCloseIsRejectedAndFailedSaveLeavesDraft() {
        val path = temporary.newFile("unsaved.txt").path
        sessions().use { owner ->
            owner.open(path) { "keep this" }
            owner.changed(path)
            try {
                owner.closeDocument(path)
                fail("Dirty documents need a successful save or explicit discard")
            } catch (_: IllegalStateException) { }
            owner.flush()
            sessions().use { assertEquals("keep this", it.recoveryText(path)) }
        }
    }

    @Test fun detachAndReattachKeepLatestContentAndRevision() {
        val path = temporary.newFile("rotation.txt").path
        sessions().use { owner ->
            val old = java.util.function.Supplier { "before rotation" }
            owner.open(path, old)
            owner.changed(path)
            owner.detach(path, old)
            assertEquals("before rotation", owner.recoveryText(path))
            owner.open(path) { "after rotation" }
            owner.changed(path)
            owner.detach(path, old) // A destroyed old view cannot detach the replacement.
            owner.flush()
            sessions().use { restarted ->
                assertEquals("after rotation", restarted.recoveryText(path))
                assertEquals(2L, restarted.state.value.single().revision)
            }
        }
    }

    @Test fun atomicWriteFailurePreservesPreviousDraftAndReportsError() {
        val path = temporary.newFile("atomic.txt").path
        val errors = mutableListOf<IOException>()
        DocumentSessions(directory, { synchronized(errors) { errors.add(it) } }, 60_000).use { owner ->
            val text = AtomicReference("previous")
            owner.open(path) { text.get() }
            owner.changed(path)
            owner.flush()
            val file = directory.listFiles()!!.single { it.name.endsWith(".draft") }
            val blockedTemp = File(directory, file.name + ".tmp").apply {
                mkdirs()
                File(this, "blocker").writeText("occupied")
            }
            text.set("new text")
            owner.changed(path)
            try { owner.flush(); fail("Expected the write failure") } catch (_: IOException) { }
            sessions().use { assertEquals("previous", it.recoveryText(path)) }
            assertTrue(errors.isNotEmpty())
            File(blockedTemp, "blocker").delete()
            blockedTemp.delete()
            owner.flush()
            sessions().use { assertEquals("new text", it.recoveryText(path)) }
        }
    }

    @Test fun incompleteAndCorruptFilesDoNotHideValidDrafts() {
        val path = temporary.newFile("valid.txt").path
        sessions().use { owner ->
            owner.open(path) { "valid" }
            owner.changed(path)
            owner.flush()
        }
        File(directory, "interrupted.tmp").writeText("partial write")
        val corrupt = File(directory, "corrupt.draft").apply { writeText("not a snapshot") }
        val errors = mutableListOf<IOException>()
        DocumentSessions(directory, { errors.add(it) }, 60_000).use { restarted ->
            assertEquals("valid", restarted.recoveryText(path))
            assertTrue(corrupt.exists())
            assertEquals(1, errors.size)
        }
    }

    @Test fun snapshotFailureIsReportedAndCanBeRetriedWithoutLosingDraft() {
        val path = temporary.newFile("snapshot.txt").path
        val source = AtomicReference("checkpoint")
        val errors = mutableListOf<IOException>()
        DocumentSessions(directory, { errors.add(it) }, 60_000).use { owner ->
            owner.open(path) { source.get() ?: error("Editor snapshot failed") }
            owner.changed(path)
            owner.flush()
            source.set(null)
            owner.changed(path)
            try { owner.flush(); fail("Expected snapshot failure") } catch (_: IOException) { }
            sessions().use { assertEquals("checkpoint", it.recoveryText(path)) }
            assertTrue(errors.isNotEmpty())
            source.set("recovered editor")
            owner.flush()
            sessions().use { assertEquals("recovered editor", it.recoveryText(path)) }
        }
    }

    @Test fun savedDocumentStaysOpenAndCleanCloseRemovesOnlyThatSession() {
        val first = temporary.newFile("one.txt").path
        val second = temporary.newFile("two.txt").path
        sessions().use { owner ->
            owner.open(first) { "one" }
            owner.open(second) { "two" }
            owner.changed(first)
            owner.changed(second)
            owner.flush()
            owner.saved(first)
            owner.flush()
            sessions().use { restarted ->
                assertEquals(2, restarted.state.value.size)
                assertFalse(restarted.isDirty(first))
                assertNull(restarted.recoveryText(first))
                assertEquals("two", restarted.recoveryText(second))
            }
            owner.closeDocument(first)
            owner.flush()
            sessions().use { restarted ->
                assertEquals(listOf(second), restarted.state.value.map { it.path })
                assertEquals("two", restarted.recoveryText(second))
            }
        }
    }

    @Test fun separateProcessRecoversAfterAbruptExitWithoutShutdownCallbacks() {
        val first = temporary.newFile("process-one.txt").apply { writeText("first original") }
        val second = temporary.newFile("process-two.txt").apply { writeText("second original") }
        val classpath = listOf(
            RecoveryProcessProbe::class.java, DocumentSessions::class.java,
            kotlin.Unit::class.java, kotlinx.coroutines.flow.StateFlow::class.java
        ).map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").path
        for (command in listOf("write", "read")) {
            val process = ProcessBuilder(java, "-cp", classpath, RecoveryProcessProbe::class.java.name,
                command, directory.path, first.path, second.path).redirectErrorStream(true).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("Recovery process timed out")
            }
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        }
    }

    @Test fun failedReadGuardPersistsWithDraftsAndOnlyAFullLoadCanClearIt() {
        val path = temporary.newFile("failed-read.py").path
        sessions().use { owner ->
            owner.open(path, java.util.function.Supplier { "partial draft" }, false)
            owner.changed(path)
            owner.flush()
            sessions().use { restored ->
                assertFalse(restored.isSourceLoaded(path))
                assertEquals("partial draft", restored.recoveryText(path))
                assertThrows(IllegalStateException::class.java) { restored.prepareSave(path) }
                restored.open(path) { "still partial" }
                assertFalse(restored.isSourceLoaded(path))
                restored.setSourceLoaded(path, true)
                assertTrue(restored.isSourceLoaded(path))
                assertNotNull(restored.prepareSave(path))
            }
        }
    }

    @Test fun staleSaveCompletionCannotClearAReopenedDocumentsRecovery() {
        val source = temporary.newFile("reopened.c").apply { writeText("original") }
        sessions().use { owner ->
            owner.open(source.path) { "old draft" }
            owner.changed(source.path)
            val saving = owner.prepareSave(source.path)
            owner.discard(source.path)
            owner.open(source.path) { "new owner draft" }
            owner.changed(source.path)
            assertFalse(owner.owns(saving))
            assertFalse(owner.completeSave(saving))
            assertTrue(owner.isDirty(source.path))
        }
        sessions().use { restored ->
            assertEquals("new owner draft", restored.recoveryText(source.path))
            assertTrue(restored.isDirty(source.path))
        }
        assertEquals("original", source.readText())
    }

    @Test fun editsAfterASavePointStayRecoverableAfterRestart() {
        val source = temporary.newFile("newer.py").apply { writeText("original") }
        val text = AtomicReference("saving this revision")
        sessions().use { owner ->
            owner.open(source.path) { text.get() }
            owner.changed(source.path)
            val saving = owner.prepareSave(source.path)
            text.set("newer edits\r\n🙂\uD800")
            owner.changed(source.path)
            assertFalse(owner.completeSave(saving))
        }
        sessions().use { restored ->
            assertEquals(text.get(), restored.recoveryText(source.path))
            assertTrue(restored.isDirty(source.path))
        }
        assertEquals("original", source.readText())
    }

    @Test fun versionOneDraftsRemainReadableWithoutLosingUtf16Text() {
        val path = temporary.newFile("legacy.cpp").path
        val text = "legacy\r\n🧪\uD800"
        val store = DraftStore(directory)
        store.write(DraftStore.Record(DocumentSession(path, 7, true), 0, text))
        val file = directory.listFiles()!!.single { it.name.endsWith(".draft") }
        java.io.DataOutputStream(file.outputStream()).use { output ->
            output.writeInt(0x54434452)
            output.writeInt(1)
            output.writeUTF(path)
            output.writeLong(7)
            output.writeBoolean(true)
            output.writeLong(0)
            output.writeInt(text.length)
            text.forEach { output.writeChar(it.code) }
        }
        sessions().use { restored ->
            assertEquals(text, restored.recoveryText(path))
            assertTrue(restored.isSourceLoaded(path))
            assertEquals(7L, restored.state.value.single().revision)
            assertNotNull(restored.prepareSave(path))
        }
    }
}
