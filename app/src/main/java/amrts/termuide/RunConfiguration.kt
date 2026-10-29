package amrts.termuide

import android.content.Context
import java.io.File
import java.util.Collections
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Local run overrides; project build commands and source documents keep their existing owners. */
class RunConfiguration @JvmOverloads constructor(
    val entryFile: String = "",
    arguments: List<String> = emptyList(),
    val workingDirectory: String = "",
    val pythonEnvironment: String = ""
) {
    val arguments: List<String> = Collections.unmodifiableList(ArrayList(arguments))

    init {
        require(pythonEnvironment.none { it.isISOControl() }) { "Environment paths cannot contain control characters" }
        require('\u0000' !in entryFile && '\u0000' !in workingDirectory && arguments.none { '\u0000' in it }) {
            "Paths and arguments cannot contain NUL characters"
        }
    }

    fun entryPath(scope: String, selected: String?): String? = if (entryFile.isEmpty()) selected else resolve(scope, entryFile)
    fun directoryPath(scope: String): String? = workingDirectory.takeIf { it.isNotEmpty() }?.let { resolve(scope, it) }

    fun pythonEnvironmentPath(scope: String): String? = pythonEnvironment.takeIf { it.isNotEmpty() }?.let { resolve(scope, it) }
    fun withPythonEnvironment(value: String) = RunConfiguration(entryFile, arguments, workingDirectory, value)

    override fun equals(other: Any?): Boolean = other is RunConfiguration &&
        entryFile == other.entryFile && arguments == other.arguments && workingDirectory == other.workingDirectory && pythonEnvironment == other.pythonEnvironment
    override fun hashCode(): Int = java.util.Objects.hash(entryFile, arguments, workingDirectory, pythonEnvironment)

    companion object {
        private fun resolve(scope: String, value: String): String {
            val file = File(value)
            return (if (file.isAbsolute) file else File(scope, value)).absoluteFile.toPath().normalize().toString()
        }

        @JvmStatic @Synchronized fun load(context: Context, scope: String): RunConfiguration {
            try {
                val text = context.getSharedPreferences("run_configurations", Context.MODE_PRIVATE).getString(scope, null)
                    ?: return RunConfiguration()
                val input = JSONTokener(text)
                val json = input.nextValue() as? JSONObject ?: throw JSONException("Expected a configuration object")
                if (input.nextClean() != '\u0000') throw JSONException("Trailing configuration data")
                fun string(name: String): String = if (!json.has(name)) "" else json.get(name) as? String
                    ?: throw JSONException("Expected a string: $name")
                val array = if (json.has("arguments")) json.getJSONArray("arguments") else JSONArray()
                val arguments = (0 until array.length()).map { array.get(it) as? String
                    ?: throw JSONException("Expected an argument string") }
                return RunConfiguration(string("entryFile"), arguments, string("workingDirectory"), string("pythonEnvironment"))
            } catch (error: JSONException) {
                throw IllegalArgumentException(context.getString(R.string.run_config_invalid), error)
            } catch (error: ClassCastException) {
                throw IllegalArgumentException(context.getString(R.string.run_config_invalid), error)
            }
        }

        @JvmStatic @Synchronized fun save(context: Context, scope: String, configuration: RunConfiguration): Boolean {
            val preferences = context.getSharedPreferences("run_configurations", Context.MODE_PRIVATE)
            val previous = preferences.all[scope]
            val editor = preferences.edit()
            if (configuration == RunConfiguration()) editor.remove(scope)
            else editor.putString(scope, JSONObject().put("entryFile", configuration.entryFile)
                .put("arguments", JSONArray(configuration.arguments)).put("workingDirectory", configuration.workingDirectory).put("pythonEnvironment", configuration.pythonEnvironment).toString())
            if (editor.commit()) return true
            // commit updates memory even when its disk write fails; rejected settings must not become active.
            val rollback = preferences.edit()
            when (previous) {
                is String -> rollback.putString(scope, previous)
                is Boolean -> rollback.putBoolean(scope, previous)
                is Int -> rollback.putInt(scope, previous)
                is Long -> rollback.putLong(scope, previous)
                is Float -> rollback.putFloat(scope, previous)
                is Set<*> -> rollback.putStringSet(scope, previous.filterIsInstance<String>().toSet())
                else -> rollback.remove(scope)
            }
            rollback.apply()
            return false
        }
    }
}
