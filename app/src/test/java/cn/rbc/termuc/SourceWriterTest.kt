package cn.rbc.termuc

import android.database.Cursor
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.*
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.RealObject
import android.net.Uri

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [SourceWriterTest.Paths::class, SourceWriterTest.Queries::class])
class SourceWriterTest {
    // Host File uses Windows separators; only dispatch is adapted, provider operations remain real.
    @Implements(FileHelper::class, isInAndroidSdk = false)
    class Paths {
        companion object {
            @Implementation @JvmStatic fun isTermuxFile(file: File) =
                file.absolutePath.replace('\\', '/').contains("/data/data/com.termux/")
        }
    }
    // Robolectric's resolver invokes the pre-O query overload, which DocumentsProvider rejects.
    @Implements(DocumentsProvider::class)
    class Queries : org.robolectric.shadows.ShadowContentProvider() {
        @RealObject lateinit var real: DocumentsProvider
        @Implementation fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor =
            real.queryDocument(DocumentsContract.getDocumentId(uri), projection)
    }
    class Provider : DocumentsProvider() {
        lateinit var storage: File
        var rejectPublish = false
        var rejectBackup = false
        var rejectDeleteBackup = false
        private fun physical(id: String) = File(storage, File(id).name)
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf("root_id"))
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) =
            MatrixCursor(projection ?: arrayOf(COLUMN_DOCUMENT_ID))
        override fun isChildDocument(parentDocumentId: String, documentId: String) =
            File(documentId).absolutePath.startsWith(File(parentDocumentId).absolutePath)
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(COLUMN_DOCUMENT_ID, COLUMN_MIME_TYPE)
            val cursor = MatrixCursor(columns)
            val file = physical(documentId)
            if (file.exists()) cursor.addRow(columns.map {
                when (it) {
                    COLUMN_DOCUMENT_ID -> documentId
                    COLUMN_MIME_TYPE -> if (file.isDirectory) MIME_TYPE_DIR else "text/plain"
                    COLUMN_LAST_MODIFIED -> file.lastModified()
                    COLUMN_SIZE -> file.length()
                    COLUMN_DISPLAY_NAME -> file.name
                    else -> null
                }
            })
            return cursor
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val id = File(parentDocumentId, displayName).path
            check(physical(id).createNewFile())
            return id
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            ParcelFileDescriptor.open(physical(documentId), ParcelFileDescriptor.parseMode(mode))
        override fun renameDocument(documentId: String, displayName: String): String {
            if (rejectPublish && documentId.endsWith("-pending")) throw FileNotFoundException("Publish failed")
            if (rejectBackup && displayName.endsWith("-backup")) throw FileNotFoundException("Backup failed")
            val result = File(File(documentId).parentFile, displayName).path
            if (!physical(documentId).renameTo(physical(result))) throw FileNotFoundException("Rename failed")
            return result
        }
        override fun deleteDocument(documentId: String) {
            if (rejectDeleteBackup && documentId.endsWith("-backup")) throw FileNotFoundException("Cleanup failed")
            if (!physical(documentId).delete()) throw FileNotFoundException("Delete failed")
        }
    }

    private lateinit var directory: File
    private lateinit var provider: Provider
    private val app get() = Application.getInstance()
    private val path = File("/data/data/com.termux/files/home/source.c")

    @Before fun prepare() {
        directory = Files.createTempDirectory("publish-source").toFile()
        val info = ProviderInfo().apply {
            authority = "cn.rbc.termuc.save-test"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider = Robolectric.buildContentProvider(Provider::class.java).create(info).get()
        provider.storage = directory
        app.treeUri = DocumentsContract.buildTreeDocumentUri("cn.rbc.termuc.save-test", path.parentFile.absolutePath)
    }
    @After fun clean() {
        Thread.interrupted()
        app.treeUri = null
        directory.deleteRecursively()
    }

    @Test fun localSaveReplacesTheWholeFileAndPreservesUnicode() {
        val source = File(directory, "local.c").apply { writeText("a much longer original") }
        SourceWriter.write(source, "\uFFFF 🧪\r\n")
        assertEquals("\uFFFF 🧪\r\n", source.readText())
        assertEquals(listOf(source.name), directory.listFiles()!!.map { it.name })
    }

    @Test fun interruptedLocalSaveLeavesTheOriginalUntouched() {
        val source = File(directory, "local.c").apply { writeText("original") }
        Thread.currentThread().interrupt()
        try { SourceWriter.write(source, "replacement"); fail("Interrupted save succeeded") }
        catch (_: InterruptedIOException) { }
        finally { Thread.interrupted() }
        assertEquals("original", source.readText())
    }

    @Test fun localDirectoryIsNotReplaced() {
        val source = File(directory, "folder").apply { mkdir() }
        File(source, "keep").writeText("original")
        try { SourceWriter.write(source, "replacement"); fail("Directory was replaced") }
        catch (_: java.io.IOException) { }
        assertEquals("original", File(source, "keep").readText())
    }

    @Test fun providerSavePublishesTheWholeFileAndCleansStaging() {
        val original = File(directory, path.name).apply { writeText("original") }
        SourceWriter.write(path, "replacement 🧪")
        assertEquals("replacement 🧪", original.readText())
        assertEquals(listOf(path.name), directory.listFiles()!!.map { it.name })
    }

    @Test fun failedProviderPublishRestoresTheOriginal() {
        val original = File(directory, path.name).apply { writeText("original") }
        provider.rejectPublish = true
        try { SourceWriter.write(path, "replacement"); fail("Publish failure was ignored") }
        catch (_: java.io.IOException) { }
        assertEquals("original", original.readText())
        assertFalse(directory.listFiles()!!.any { it.name.endsWith("-backup") })
    }

    @Test fun providerWithoutRenameSupportCannotTruncateTheOriginal() {
        val original = File(directory, path.name).apply { writeText("original") }
        provider.rejectBackup = true
        try { SourceWriter.write(path, "replacement"); fail("Unsupported backup was ignored") }
        catch (_: java.io.IOException) { }
        assertEquals("original", original.readText())
    }

    @Test fun openingAfterAnInterruptedProviderRenameRestoresTheOldSource() {
        val journal = SourceWriter.Journal(path)
        journal.write()
        val backup = File(directory, journal.backup.name).apply { writeText("original") }
        File(directory, journal.temporary.name).writeText("new staged content")
        FileHelper.openInputStream(path).bufferedReader().use { assertEquals("original", it.readText()) }
        assertFalse(backup.exists())
        assertEquals("original", File(directory, path.name).readText())
    }
}
