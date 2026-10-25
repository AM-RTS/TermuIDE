package amrts.termuide

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RunConfigurationTest {
    @Test fun configurationsPersistPerScopeAndResetToDefaults() {
        val app = Application.getInstance()
        val scope = "/run-config-test/project"
        val arguments = mutableListOf("", "two words", "\"$`\\; é")
        val config = RunConfiguration("src/main.py", arguments, "data")
        arguments.clear()
        assertEquals(3, config.arguments.size)
        assertTrue(RunConfiguration.save(app, scope, config))
        assertEquals(config, RunConfiguration.load(app, scope))
        assertEquals(RunConfiguration(), RunConfiguration.load(app, "$scope/other"))
        assertTrue(RunConfiguration.save(app, scope, RunConfiguration()))
        assertEquals(RunConfiguration(), RunConfiguration.load(app, scope))
        assertFalse(app.getSharedPreferences("run_configurations", 0).contains(scope))
    }

    @Test fun relativePathsUseTheScopeAndEmptyPathsKeepDefaults() {
        val directory = Files.createTempDirectory("run-config-paths").toFile()
        try {
            val config = RunConfiguration("src/../main.cpp", listOf("arg"), "data")
            assertEquals(File(directory, "main.cpp").path, config.entryPath(directory.path, "/selected.c"))
            assertEquals(File(directory, "data").path, config.directoryPath(directory.path))
            assertEquals("/selected.c", RunConfiguration().entryPath(directory.path, "/selected.c"))
            assertNull(RunConfiguration().directoryPath(directory.path))
            assertNull(RunConfiguration().entryPath(directory.path, null))
            val absolute = File(directory, "absolute.py").path
            assertEquals(absolute, RunConfiguration(absolute).entryPath("/other", null))
        } finally { directory.deleteRecursively() }
    }

    @Test fun nulCannotReachPathsOrProcessArgumentsAndCorruptStoredDataIsReported() {
        assertThrows(IllegalArgumentException::class.java) { RunConfiguration("main\u0000.py") }
        assertThrows(IllegalArgumentException::class.java) { RunConfiguration(arguments = listOf("a\u0000b")) }
        assertThrows(IllegalArgumentException::class.java) { RunConfiguration(workingDirectory = "bad\u0000path") }
        val app = Application.getInstance()
        val prefs = app.getSharedPreferences("run_configurations", 0)
        val scope = "/bad-config"
        try {
            for (text in listOf("broken JSON", """{"arguments":[1]}""", """{"entryFile":false}""")) {
                prefs.edit().putString(scope, text).commit()
                assertThrows(IllegalArgumentException::class.java) { RunConfiguration.load(app, scope) }
            }
        } finally { prefs.edit().remove(scope).commit() }
    }

    @Test fun failedCommitRestoresThePreviouslyActiveConfiguration() {
        val app = Application.getInstance()
        val scope = "/failed-config-commit"
        val prefs = app.getSharedPreferences("run_configurations", 0)
        val context = object : android.content.ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val original = super.getSharedPreferences(name, mode)
                return object : android.content.SharedPreferences by original {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun commit(): Boolean { editor.apply(); return false }
                        }
                    }
                }
            }
        }
        try {
            val previous = RunConfiguration("original.py", listOf("original"))
            RunConfiguration.save(app, scope, previous)
            assertFalse(RunConfiguration.save(context, scope, RunConfiguration("rejected.py")))
            assertEquals(previous, RunConfiguration.load(app, scope))
            assertFalse(RunConfiguration.save(context, scope, RunConfiguration()))
            assertEquals(previous, RunConfiguration.load(app, scope))
            prefs.edit().remove(scope).commit()
            assertFalse(RunConfiguration.save(context, scope, previous))
            assertEquals(RunConfiguration(), RunConfiguration.load(app, scope))
            prefs.edit().putInt(scope, 42).commit()
            assertFalse(RunConfiguration.save(context, scope, previous))
            assertEquals(42, prefs.getInt(scope, 0))
        } finally { prefs.edit().remove(scope).commit() }
    }

    @Test fun trailingConfigurationDataCannotBeSilentlyIgnored() {
        val app = Application.getInstance()
        val scope = "/trailing-config"
        val prefs = app.getSharedPreferences("run_configurations", 0)
        try {
            prefs.edit().putString(scope, """{"entryFile":"unexpected.py"} broken""").commit()
            assertThrows(IllegalArgumentException::class.java) { RunConfiguration.load(app, scope) }
        } finally { prefs.edit().remove(scope).commit() }
    }
}
