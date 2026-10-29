package amrts.termuide

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier
import java.util.function.ToIntFunction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks the latest requested command. Termux continues to own actual processes and terminals. */
class RunController @JvmOverloads constructor(
    context: Context,
    private val documents: Supplier<DocumentSessions>,
    private val save: Function<String, CompletableFuture<Boolean>>,
    private val matchesContext: ToIntFunction<RunCommands.Request>,
    private val debug: Consumer<RunCommands.Request>,
    private val transport: Transport = Transport { plan, result ->
        Utils.run(context.applicationContext, plan.executable, plan.arguments.toTypedArray(), plan.directory, plan.background, result)
    }
) {
    fun interface Transport { fun send(plan: RunCommands.Plan, result: PendingIntent) }
    enum class Stage { IDLE, CHECKING, AWAITING_SAVE, DISPATCHED, FINISHED, FAILED, UNKNOWN, CANCELLED }
    data class Status(val stage: Stage = Stage.IDLE, val source: String? = null, val detail: String? = null, val exitCode: Int? = null) {
        val preparing: Boolean get() = stage == Stage.CHECKING || stage == Stage.AWAITING_SAVE
    }
    private class Job(var request: RunCommands.Request) {
        val id = UUID.randomUUID().toString()
        var point: DocumentSessions.SavePoint? = null
        var token: String? = null
        var callback: PendingIntent? = null
        var timeout: Runnable? = null
        var resume: (() -> Unit)? = null
        var probe = true
        var cancelled = false
    }
    private val app = context.applicationContext
    private val history = app.getSharedPreferences("runtime_status", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Runtime preparation").apply { isDaemon = true } }
    private val mutableState = MutableStateFlow(if (history.contains("pending"))
        Status(Stage.UNKNOWN, detail = app.getString(R.string.runtime_result_unknown)) else Status())
    val state: StateFlow<Status> = mutableState.asStateFlow()
    private val toolCache = mutableMapOf<String, String>()
    private var current: Job? = null

    fun start(request: RunCommands.Request) {
        check(Looper.myLooper() == Looper.getMainLooper())
        current?.let(::clearCallback)
        val job = Job(request.copy(breakpoints = request.breakpoints.toList()))
        current = job
        status(job, Stage.CHECKING)
        try { RunCommands.requiredTools(request) }
        catch (error: IllegalArgumentException) { fail(job, error.message); return }
        withHost(job) { beginSave(job) }
    }

    private fun refreshProject(job: Job) {
        status(job, Stage.CHECKING)
        worker.execute {
            try {
                val config = Project.refreshed(job.request.project)
                main.post {
                    withHost(job) {
                        if (!validDocument(job)) { fail(job, app.getString(R.string.runtime_changed)); return@withHost }
                        job.request = job.request.copy(project = config)
                        if (config.rootPath != null) Project.publish(config)
                        probe(job)
                    }
                }
            } catch (error: Exception) { main.post { fail(job, error.message) } }
        }
    }

    private fun beginSave(job: Job) {
        status(job, Stage.AWAITING_SAVE)
        val future = try { job.request.path?.let { save.apply(it) } ?: CompletableFuture.completedFuture(true) }
        catch (error: Exception) { fail(job, app.getString(R.string.save_failed)); return }
        future.whenComplete { clean, error ->
            val completion = Runnable {
                withHost(job) {
                    if (error != null) fail(job, app.getString(R.string.save_failed))
                    else if (clean != true) fail(job, app.getString(R.string.save_newer_changes))
                    else {
                        val path = job.request.path
                        job.point = if (path == null) null else try { documents.get().prepareSave(path) }
                            catch (_: IllegalStateException) { fail(job, app.getString(R.string.runtime_changed)); return@withHost }
                        if (!validDocument(job)) fail(job, app.getString(R.string.runtime_changed))
                        else refreshProject(job)
                    }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) completion.run() else main.post(completion)
        }
    }

    private fun probe(job: Job) {
        status(job, Stage.CHECKING)
        send(job, RunCommands.probe(job.request, toolCache), true)
    }

    private fun validDocument(job: Job): Boolean = job.point?.let { documents.get().isCurrent(it) } ?: (job.request.path == null)

    /** Context is unavailable during Activity recreation; resume once the restored host is ready. */
    private fun withHost(job: Job, action: () -> Unit) {
        if (current !== job || job.cancelled) return
        when (matchesContext.applyAsInt(job.request)) {
            0 -> job.resume = action
            1 -> action()
            else -> fail(job, app.getString(R.string.runtime_changed), Stage.CANCELLED)
        }
    }

    fun onHostReady() {
        val job = current ?: return
        val continuation = job.resume ?: return
        job.resume = null
        withHost(job, continuation)
    }

    private fun send(job: Job, plan: RunCommands.Plan, probe: Boolean) {
        clearCallback(job)
        job.probe = probe
        val token = UUID.randomUUID().toString()
        val result = resultCallback(app, RESULT_SCHEME, token)
        job.token = token
        job.callback = result
        if (probe) {
            job.timeout = Runnable { fail(job, app.getString(R.string.runtime_timeout)) }.also { main.postDelayed(it, PROBE_TIMEOUT) }
        } else {
            job.point = null
            status(job, Stage.DISPATCHED)
        }
        try {
            transport.send(plan, result)
            if (!probe) history.edit().putString("pending", job.id).apply()
            if (!probe && job.request.action == RunCommands.Action.DEBUG) debug.accept(job.request)
        } catch (error: RuntimeException) { fail(job, error.message ?: app.getString(R.string.runtime_setup_required)) }
    }

    fun receive(token: String, result: Bundle?) {
        val job = current ?: return
        if (job.token != token) return
        try { handleResult(job, result) }
        catch (_: RuntimeException) { fail(job, app.getString(R.string.runtime_result_unknown), Stage.UNKNOWN) }
    }

    private fun handleResult(job: Job, result: Bundle?) {
        val probing = job.probe
        clearCallback(job)
        if (probing) {
            val outcome = readProbeResult(app, result, RunCommands.requiredTools(job.request))
            val resolved = outcome.tools
            if (resolved == null) { fail(job, outcome.message, if (outcome.unknown) Stage.UNKNOWN else Stage.FAILED); return }
            toolCache.putAll(resolved)
            withHost(job) {
                if (!validDocument(job)) fail(job, app.getString(R.string.runtime_changed))
                else try { send(job, RunCommands.build(job.request, resolved), false) }
                catch (error: IllegalArgumentException) { fail(job, error.message) }
            }
        } else {
            if (result == null || result.get("err") !is Int) { fail(job, app.getString(R.string.runtime_result_unknown), Stage.UNKNOWN); return }
            if (result.getInt("err") != -1) {
                if (history.getString("pending", null) == job.id) history.edit().remove("pending").apply()
                fail(job, result.getString("errmsg")?.takeIf { it.isNotBlank() } ?: app.getString(R.string.runtime_setup_required)); return
            }
            if (result.get("exitCode") !is Int) { fail(job, app.getString(R.string.runtime_result_unknown), Stage.UNKNOWN); return }
            val exit = result.getInt("exitCode")
            if (exit == 127) toolCache.clear()
            if (history.getString("pending", null) == job.id) history.edit().remove("pending").apply()
            mutableState.value = Status(Stage.FINISHED, job.request.path, exitCode = exit)
        }
    }

    fun cancelPreparation() {
        val job = current ?: return
        if (state.value.preparing) fail(job, app.getString(R.string.runtime_cancelled), Stage.CANCELLED)
    }

    fun onConfigurationChanged() {
        toolCache.clear()
        val action = current?.request?.action
        if (action == RunCommands.Action.RUN || action == RunCommands.Action.DEBUG) cancelPreparation()
    }

    fun dismissStatus() {
        if (state.value.preparing) cancelPreparation() else mutableState.value = Status()
    }

    private fun status(job: Job, stage: Stage) {
        if (current === job) mutableState.value = Status(stage, job.request.path)
    }

    private fun fail(job: Job, message: String?, stage: Stage = Stage.FAILED) {
        if (current !== job) return
        clearCallback(job)
        job.cancelled = true
        job.point = null
        job.resume = null
        toolCache.clear()
        mutableState.value = Status(stage, job.request.path, message?.take(500) ?: app.getString(R.string.runtime_setup_required))
    }

    private fun clearCallback(job: Job) {
        job.timeout?.let { main.removeCallbacks(it) }
        job.timeout = null
        job.callback?.cancel()
        job.callback = null
        job.token = null
    }

    fun shutdown() {
        current?.let(::clearCallback)
        current = null
        worker.shutdownNow()
    }

    data class ProbeResult(val tools: Map<String, String>?, val message: String? = null, val unknown: Boolean = false, val missing: Boolean = false)

    companion object {
        @JvmStatic fun resultCallback(context: Context, scheme: String, token: String): PendingIntent {
            val intent = Intent(context, RunResultReceiver::class.java).setAction(RESULT_ACTION)
                .setData(Uri.Builder().scheme(scheme).authority(context.packageName).appendPath(token).build())
            val flags = PendingIntent.FLAG_ONE_SHOT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, 0, intent, flags)
        }

        @JvmStatic fun readProbeResult(context: Context, result: Bundle?, required: List<String>): ProbeResult {
            val unknown = context.getString(R.string.runtime_result_unknown)
            try {
                if (result == null || result.get("err") !is Int) return ProbeResult(null, unknown, true)
                if (result.getInt("err") != -1) return ProbeResult(null,
                    result.getString("errmsg")?.takeIf { it.isNotBlank() }?.take(500) ?: context.getString(R.string.runtime_setup_required))
                if (result.get("exitCode") !is Int) return ProbeResult(null, unknown, true)
                val exit = result.getInt("exitCode")
                if (exit != 0) return ProbeResult(null,
                    result.getString("stderr")?.trim()?.takeIf { it.isNotBlank() }?.take(500) ?: context.getString(R.string.runtime_setup_required), missing = exit == 127)
                val paths = result.getString("stdout").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
                if (paths.size != required.size || paths.any { !it.startsWith('/') || it.any { char -> char.isISOControl() } }) return ProbeResult(null, unknown, true)
                return ProbeResult(required.zip(paths).toMap())
            } catch (_: RuntimeException) { return ProbeResult(null, unknown, true) }
        }

        const val RESULT_ACTION = "amrts.termuide.RUNTIME_RESULT"
        const val RESULT_SCHEME = "termuide-run"
        const val PROBE_TIMEOUT = 15_000L
    }
}
