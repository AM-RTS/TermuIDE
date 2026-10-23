package cn.rbc.termuc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileFilter

class FileActivity : ComponentActivity(), FileFilter {
    private lateinit var adapter: FileAdapter
    private lateinit var pwd: File
    var directoryRevision by mutableIntStateOf(0)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        Utils.setNightMode(this, Application.theme)
        super.onCreate(savedInstanceState)
        val initial = intent.getStringExtra(FN)?.let { File(it).parentFile } ?: Utils.ROOT
        pwd = savedInstanceState?.getString(PD)?.let(::File) ?: initial
        adapter = FileAdapter(pwd, this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        setContentView(TermuCCompose.fileView(this))
    }

    override fun accept(file: File): Boolean {
        val name = file.name
        return file.isDirectory || name.endsWith(".ttf") || name.endsWith(".otf")
    }

    val directoryPath: String get() = pwd.absolutePath

    val directoryItems: List<String>
        get() = (0 until adapter.count).map { adapter.getItem(it).name }

    fun getDirectoryIcon(index: Int): Int = adapter.getItem(index).icon

    fun selectItem(name: String) {
        if (name == "..") {
            pwd = pwd.parentFile ?: pwd
        } else {
            val file = File(pwd, name)
            if (file.isDirectory) {
                pwd = file
            } else {
                intent.putExtra(FN, file.absolutePath)
                setResult(RESULT_OK, intent)
                finish()
                return
            }
        }
        refresh()
    }

    fun navigateBack() {
        if (Utils.ROOT == pwd) finish() else {
            pwd = pwd.parentFile ?: Utils.ROOT
            refresh()
        }
    }

    private fun refresh() {
        adapter.setPath(pwd)
        directoryRevision++
    }

    override fun onBackPressed() = navigateBack()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PD, pwd.absolutePath)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val FN = "n"
        private const val PD = "p"
    }
}
