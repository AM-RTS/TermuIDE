package amrts.termuide

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class SettingsActivity : ComponentActivity(), Utils.ComposeDialogHost {
    private lateinit var prefs: android.content.SharedPreferences
    private var originalValues: Map<String, *> = emptyMap<String, Any>()
    private var uiRefresh: Runnable? = null
    private var installPrompt = false
    private var installPromptNoRemind = false
    private var initPrompt = false
    private var initPromptNoRemind = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Utils.setNightMode(this, Application.theme)
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val restoredValues = savedInstanceState?.getSerializable("originalValues") as? HashMap<String, *>
        originalValues = restoredValues ?: HashMap(prefs.all)
        installPrompt = savedInstanceState?.getBoolean("installPrompt") ?: false
        installPromptNoRemind = savedInstanceState?.getBoolean("installPromptNoRemind") ?: false
        initPrompt = savedInstanceState?.getBoolean("initPrompt") ?: false
        initPromptNoRemind = savedInstanceState?.getBoolean("initPromptNoRemind") ?: false
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishSettings()
        })
        setContentView(TermuCCompose.settingsView(this))
    }

    fun pickFont() {
        val intent = Intent(this, FileActivity::class.java)
        intent.putExtra(FileActivity.FN, prefs.getString(Application.KEY_MYFONT, null))
        startActivityForResult(intent, REQUEST_FONT)
    }

    fun checkTermux() = Utils.testApp(this, true)

    fun initializeTermux() = Utils.initBack(this, true)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FONT && resultCode == Activity.RESULT_OK) {
            data?.getStringExtra(FileActivity.FN)?.let { path ->
                prefs.edit()
                    .putString(Application.KEY_MYFONT, path)
                    .putString(Application.KEY_FONT, "c")
                    .apply()
            }
            uiRefresh?.run()
        }
    }

    fun setUiRefresh(callback: Runnable?) { uiRefresh = callback }

    override fun showTermuxInstallPrompt(showNeverRemind: Boolean) {
        installPrompt = true
        installPromptNoRemind = showNeverRemind
        uiRefresh?.run()
    }

    override fun showTermuxInitPrompt(showAlreadyInitialized: Boolean) {
        initPrompt = true
        initPromptNoRemind = showAlreadyInitialized
        uiRefresh?.run()
    }

    fun isInstallPromptOpen() = installPrompt
    fun isInstallPromptNoRemind() = installPromptNoRemind
    fun isInitPromptOpen() = initPrompt
    fun isInitPromptNoRemind() = initPromptNoRemind

    fun resolveInstallPrompt(launchInstaller: Boolean, neverRemind: Boolean) {
        installPrompt = false
        if (launchInstaller) Utils.startTermuxInstall(this)
        else if (neverRemind) Utils.skipTermuxInstallReminder(this)
        uiRefresh?.run()
    }

    fun resolveInitPrompt(initialize: Boolean, alreadyInitialized: Boolean) {
        initPrompt = false
        if (initialize) Utils.copyJump(this)
        else if (alreadyInitialized) Utils.skipTermuxInitReminder(this)
        uiRefresh?.run()
    }

    fun finishSettings() {
        if (::prefs.isInitialized) {
            val themeChanged = originalValues[Application.KEY_THEME] != prefs.getString(Application.KEY_THEME, "l")
            val changed = originalValues != prefs.all
            setResult(
                when {
                    themeChanged -> Activity.RESULT_FIRST_USER
                    changed -> Activity.RESULT_OK
                    else -> Activity.RESULT_CANCELED
                }
            )
        }
        finish()
    }

    override fun onBackPressed() = finishSettings()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putSerializable("originalValues", HashMap(originalValues))
        outState.putBoolean("installPrompt", installPrompt)
        outState.putBoolean("installPromptNoRemind", installPromptNoRemind)
        outState.putBoolean("initPrompt", initPrompt)
        outState.putBoolean("initPromptNoRemind", initPromptNoRemind)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (::prefs.isInitialized) updateApplicationSettings()
        super.onPause()
    }

    private fun updateApplicationSettings() {
        Application.getInstance().initConfs()
        Application.clearCache()
    }

    private companion object {
        const val REQUEST_FONT = 0
    }
}
