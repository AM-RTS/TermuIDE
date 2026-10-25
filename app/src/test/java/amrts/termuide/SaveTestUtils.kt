package amrts.termuide

import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.robolectric.Shadows.shadowOf

internal fun drainSourceSaves() {
    val saver = Application.getInstance().saver
    val worker = DocumentSaver::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(saver) as ExecutorService
    repeat(30) {
        worker.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        if (!saver.isSaving) return
    }
    error("Source saves did not finish")
}
