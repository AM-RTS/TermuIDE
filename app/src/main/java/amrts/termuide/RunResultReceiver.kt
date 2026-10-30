package amrts.termuide

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RunResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.data ?: return
        if (intent.action != RunController.RESULT_ACTION || data.scheme !in listOf(RunController.RESULT_SCHEME, LanguageServers.RESULT_SCHEME, LanguageToolInstaller.RESULT_SCHEME) ||
            data.authority != context.packageName || data.pathSegments.size != 1) return
        val result = try { intent.getBundleExtra("result") } catch (_: RuntimeException) { null }
        if (data.scheme == LanguageToolInstaller.RESULT_SCHEME) Application.getInstance().toolInstaller.receive(data.pathSegments.single(), result)
        else if (data.scheme == LanguageServers.RESULT_SCHEME) Application.getInstance().languageServers.receive(data.pathSegments.single(), result)
        else Application.getInstance().runs.receive(data.pathSegments.single(), result)
    }
}
