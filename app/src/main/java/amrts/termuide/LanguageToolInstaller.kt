package amrts.termuide

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Setup owns no editor/save state. Termux owns the visible package-manager process. */
class LanguageToolInstaller(context: Context) {
    enum class Stage { IDLE, INSTALLING, CHECKING, READY, FAILED, UNKNOWN }
    data class Status(val stage: Stage = Stage.IDLE, val server: String = "", val scope: String = "", val detail: String? = null) {
        val busy: Boolean get() = stage == Stage.INSTALLING || stage == Stage.CHECKING
    }
    private val app = context.applicationContext
    private val pending = app.getSharedPreferences("language_tool_setup", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(if (pending.contains("token"))
        Status(Stage.UNKNOWN, pending.getString("server", "").orEmpty(), pending.getString("scope", "").orEmpty(), app.getString(R.string.tools_setup_unknown)) else Status())
    val state: StateFlow<Status> = mutableState.asStateFlow()
    private var timeout: Runnable? = null

    fun start(context: Context, scope: String, server: String, environment: String) {
        check(!state.value.busy) { app.getString(R.string.tools_setup_busy) }
        require(server in listOf("clangd", "pylsp", "java"))
        require(File(scope).isAbsolute && scope.none { it.isISOControl() })
        require(environment.isEmpty() || (File(environment).isAbsolute && environment.none { it.isISOControl() }))
        val script = app.assets.open("language-tools.sh").bufferedReader().use { it.readText() }
        val java = app.assets.open("install-java-tools.py").bufferedReader().use { it.readText() }
        val arguments = listOf("-c", script, "termuide-language-tools", server, environment, app.getString(R.string.java_lsp_revision), java)
        send(context, scope, server, environment, RunCommands.Plan(Utils.PREF + "/usr/bin/bash", arguments, Utils.PREF + "/home"), false)
    }

    private fun send(context: Context, scope: String, server: String, environment: String, plan: RunCommands.Plan, checking: Boolean) {
        clearCallback()
        val token = UUID.randomUUID().toString()
        check(pending.edit().putString("token", token).putString("scope", scope).putString("server", server)
            .putString("environment", environment).putBoolean("checking", checking).commit()) { "Cannot persist setup callback" }
        mutableState.value = Status(if (checking) Stage.CHECKING else Stage.INSTALLING, server, scope)
        val callback = RunController.resultCallback(context, RESULT_SCHEME, token)
        if (checking) {
            timeout = Runnable { if (pending.getString("token", null) == token) finish(Stage.FAILED, app.getString(R.string.runtime_timeout)) }
            main.postDelayed(timeout!!, RunController.PROBE_TIMEOUT)
        }
        try { Utils.run(context, plan.executable, plan.arguments.toTypedArray(), plan.directory, checking, callback) }
        catch (error: RuntimeException) { finish(Stage.FAILED, error.message ?: app.getString(R.string.runtime_setup_required)) }
    }

    fun receive(token: String, result: Bundle?) {
        if (token != pending.getString("token", null)) return
        val scope = pending.getString("scope", "").orEmpty()
        val server = pending.getString("server", "").orEmpty()
        val environment = pending.getString("environment", "").orEmpty()
        val checking = pending.getBoolean("checking", false)
        if (checking) {
            val tool = when (server) { "java" -> "termuide-java-lsp"; "pylsp" -> "python"; else -> "clangd" }
            val outcome = RunController.readProbeResult(app, result, listOf(tool))
            if (outcome.tools == null) { finish(Stage.FAILED, outcome.message); return }
            finish(Stage.READY, app.getString(R.string.tools_setup_ready))
            // The user may have changed Python environments while Termux was in front.
            val current = runCatching { RunConfiguration.load(app, scope).pythonEnvironmentPath(scope).orEmpty() }.getOrNull()
            if (server != "pylsp" || current == environment)
                Application.getInstance().languageServers.restart(app, scope, server)
            return
        }
        val error = try {
            when {
                result == null || result.get("err") !is Int -> app.getString(R.string.tools_setup_unknown)
                result.getInt("err") != -1 -> result.getString("errmsg") ?: app.getString(R.string.runtime_setup_required)
                result.get("exitCode") !is Int -> app.getString(R.string.tools_setup_unknown)
                result.getInt("exitCode") != 0 -> app.getString(R.string.tools_setup_failed, result.getInt("exitCode"))
                else -> null
            }
        } catch (_: RuntimeException) { app.getString(R.string.tools_setup_unknown) }
        if (error != null) { finish(Stage.FAILED, error); return }
        send(app, scope, server, environment, RunCommands.languageServerProbe(server, scope, environment), true)
    }

    private fun clearCallback() {
        timeout?.let { main.removeCallbacks(it) }; timeout = null
        pending.getString("token", null)?.let { RunController.resultCallback(app, RESULT_SCHEME, it).cancel() }
    }

    private fun finish(stage: Stage, detail: String?) {
        val server = pending.getString("server", "").orEmpty()
        val scope = pending.getString("scope", "").orEmpty()
        clearCallback()
        pending.edit().clear().apply()
        mutableState.value = Status(stage, server, scope, detail?.take(500))
    }

    companion object { const val RESULT_SCHEME = "termuide-tools" }
}
