package amrts.termuide

import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.robolectric.Shadows.shadowOf

internal fun drainSourceSaves() {
    drainFileTasks()
    val saver = Application.getInstance().saver
    val worker = DocumentSaver::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(saver) as ExecutorService
    repeat(30) {
        worker.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        if (!saver.isSaving) return
    }
    error("Source saves did not finish")
}

/** Drain worker completions and any follow-up file work posted by the current host. */
internal fun drainFileTasks() {
    val worker = Application::class.java.getDeclaredField("fileWorker").apply { isAccessible = true }
        .get(Application.getInstance()) as java.util.concurrent.ThreadPoolExecutor
    repeat(30) {
        val submitted = worker.taskCount
        worker.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        if (worker.taskCount == submitted + 1) return
    }
    error("Workspace file tasks did not settle")
}
