package amrts.termuide

import cn.rbc.codeeditor.lang.*
import cn.rbc.codeeditor.lang.c.CLanguage
import cn.rbc.codeeditor.lang.c.CppLanguage

/** Language facts shared by file detection, editor setup and execution controls. */
object Languages {
    data class Definition(val id: String, val type: Int, val compiler: String?, val hasLsp: Boolean) {
        val runtime: String? get() = when (id) {
            "c", "cpp" -> compiler
            "python" -> "python"
            "java" -> "java"
            else -> null
        }
        fun highlighting(): Language = when (id) {
            "c" -> CLanguage.getInstance()
            "cpp" -> CppLanguage.getInstance()
            "java" -> LanguageJava.getInstance()
            "python" -> LanguagePython.getInstance()
            "javascript" -> LanguageJavascript.getInstance()
            "json" -> LanguageJson.getInstance()
            else -> LanguageNonProg.getInstance()
        }
    }

    private val definitions = listOf(
        Definition("c", EditFragment.TYPE_C, "clang", true),
        Definition("cpp", EditFragment.TYPE_CPP, "clang++", true),
        Definition("python", EditFragment.TYPE_PYTHON, null, true),
        Definition("java", EditFragment.TYPE_JAVA, "javac", true),
        Definition("javascript", EditFragment.TYPE_JAVASCRIPT, "node", false),
        Definition("json", EditFragment.TYPE_JSON, null, false),
        Definition("text", EditFragment.TYPE_TXT, null, false)
    )
    private val extensions = mapOf(
        "c" to EditFragment.TYPE_C,
        "cpp" to EditFragment.TYPE_CPP, "cc" to EditFragment.TYPE_CPP, "cxx" to EditFragment.TYPE_CPP,
        "h" to (EditFragment.TYPE_CPP or EditFragment.TYPE_HEADER),
        "hpp" to (EditFragment.TYPE_CPP or EditFragment.TYPE_HEADER),
        "java" to (EditFragment.TYPE_JAVA or EditFragment.TYPE_HEADER),
        "py" to EditFragment.TYPE_PYTHON,
        "js" to (EditFragment.TYPE_JAVASCRIPT or EditFragment.TYPE_HEADER),
        "json" to (EditFragment.TYPE_JSON or EditFragment.TYPE_HEADER)
    )

    @JvmStatic fun forType(type: Int): Definition =
        definitions.firstOrNull { it.type == (type and EditFragment.TYPE_MASK) } ?: definitions.last()

    @JvmStatic fun fileType(name: String): Int? = extensions[name.substringAfterLast('.', "")]
    @JvmStatic fun isCodeFile(name: String): Boolean = fileType(name) != null
    @JvmStatic fun canDebug(type: Int): Boolean = forType(type).id in listOf("c", "cpp")
    @JvmStatic fun canRun(type: Int): Boolean =
        type == EditFragment.TYPE_C || type == EditFragment.TYPE_CPP || forType(type).id in listOf("python", "java")
}
