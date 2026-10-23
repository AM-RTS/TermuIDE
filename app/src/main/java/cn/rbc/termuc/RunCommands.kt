package cn.rbc.termuc

import java.io.File

/** Pure command planning; project command strings remain explicit user shell configuration. */
object RunCommands {
    enum class Action { RUN, DEBUG, BUILD, COMPILE }
    data class Request @JvmOverloads constructor(
        val action: Action,
        val path: String?,
        val type: Int,
        val directory: String,
        val project: Project.Config,
        val flags: String,
        val breakpoints: List<Int>,
        val configuration: RunConfiguration = RunConfiguration()
    )
    data class Plan(val executable: String, val arguments: List<String>, val directory: String, val background: Boolean = false)

    private fun usesRunConfiguration(request: Request) = request.action == Action.RUN || request.action == Action.DEBUG

    @JvmStatic fun workingDirectory(request: Request): String =
        (if (usesRunConfiguration(request)) request.configuration.directoryPath(request.project.rootPath ?: request.directory) else null)
            ?: request.project.rootPath
            ?: if (Languages.forType(request.type).id in listOf("python", "java")) File(requireNotNull(request.path)).absoluteFile.parent!!
            else request.directory

    @JvmStatic fun requiredTools(request: Request): List<String> = buildList {
        if (request.project.rootPath == null) {
            require(request.path != null && Languages.canRun(request.type)) { "This source has no runtime integration" }
            require(request.action == Action.RUN || request.action == Action.DEBUG) { "Build requires a project" }
            add(requireNotNull(Languages.forType(request.type).runtime))
        }
        if (request.action == Action.DEBUG) {
            require(Languages.forType(request.type).hasLsp) { "Debugging requires C/C++ source" }
            add("gdb")
        }
    }

    private fun quote(value: String) = "\"${Utils.escape(value)}\""

    @JvmStatic fun environment(project: Project.Config, file: File?): String = buildString {
        if (project.rootPath != null) append("p=${quote(project.rootPath)};o=${quote(project.outputDir)};")
        if (file != null) {
            append("f=${quote(file.name)};e=${quote(file.name.substringBeforeLast('.', file.name))};d=${quote(file.parent!!)};")
        }
    }

    @JvmStatic fun build(request: Request, tools: Map<String, String>): Plan {
        requiredTools(request).forEach { require(tools[it]?.startsWith('/') == true) { "Missing runtime: $it" } }
        val file = request.path?.let(::File)
        val project = request.project
        val arguments = if (usesRunConfiguration(request)) request.configuration.arguments else emptyList()
        val extraArguments = if (arguments.isEmpty()) "" else " \"\${termuc_program_args[@]}\""
        val changeDirectory = if (usesRunConfiguration(request) && request.configuration.workingDirectory.isNotEmpty())
            "cd -- ${quote(workingDirectory(request))} && " else ""
        val pause = "\ntermuc_status=\$?;read -n1 -rsp \$'\\nPress any key to exit...';exit \"\$termuc_status\""
        if (project.rootPath == null && Languages.forType(request.type).id in listOf("python", "java")) {
            val language = Languages.forType(request.type)
            val runtime = tools.getValue(language.runtime!!)
            val command = listOf("-c", "\"\$@\"$pause", "termuc-run", runtime) +
                (if (language.id == "java") listOf("-Dfile.encoding=UTF-8") else emptyList()) + file!!.absolutePath + arguments
            return Plan(Utils.PREF + "/usr/bin/bash", command, workingDirectory(request))
        }
        val body = when (request.action) {
            Action.BUILD, Action.COMPILE -> {
                require(project.rootPath != null) { "Build requires a project" }
                environment(project, file) + "mkdir -p -- \"\$o\" && " +
                    if (request.action == Action.BUILD) project.buildCmd else project.compileCmd
            }
            Action.RUN, Action.DEBUG -> buildString {
                if (project.rootPath == null) {
                    val compiler = tools.getValue(Languages.forType(request.type).compiler!!)
                    append("x=\"\$TMPDIR/m\";${quote(compiler)} ${quote(file!!.absolutePath)} ${request.flags}")
                    if (!request.flags.contains("-g")) append(" -g")
                    append(" -o \"\$x\" && ")
                } else {
                    append(environment(project, file))
                    append("x=\"\$TMPDIR/termuc\";find \"\$o\" -maxdepth 1 -type f \\( -iname '*.so' -o ! -name '*.*' \\) -exec install -D -t \"\$x\" {} \\;;")
                    append(if (project.runCmd == "\$x/\$e") "x=(\"\$x/\$e\") && " else "x=(${project.runCmd}) && ")
                    append("{ [ \"\${#x[@]}\" -gt 0 ] && [ -n \"\${x[0]}\" ] || { printf '%s\\n' 'Project run command is empty' >&2; false; }; } && ")
                    // Resolve explicit relative executables before switching to the runtime directory.
                    if (changeDirectory.isNotEmpty()) append("case \"\${x[0]}\" in /*) ;; */*) x[0]=\"\$PWD/\${x[0]}\";; esac && ")
                }
                append(changeDirectory)
                if (request.action == Action.RUN) append("\"\${x[@]}\"$extraArguments")
                else append("/system/bin/toybox nc -l -s 127.0.0.1 -p 48456 nice -n -20 ${quote(tools.getValue("gdb"))} -q -i=mi -ret -tty `tty` --args \"\${x[@]}\"$extraArguments 2>/dev/null")
            }
        }
        // Pause after failures too, and keep the program's exit status rather than read's status.
        // Keep program arguments out of compiler flags and legacy project command expansion.
        val script = (if (arguments.isEmpty()) "" else "termuc_program_args=(\"\$@\");set --;\n") + body + pause
        val command = listOf("-c", script) + if (arguments.isEmpty()) emptyList() else listOf(Utils.PREF + "/usr/bin/bash") + arguments
        return Plan(Utils.PREF + "/usr/bin/bash", command, project.rootPath ?: request.directory)
    }

    @JvmStatic @JvmOverloads fun probe(request: Request, cached: Map<String, String> = emptyMap()): Plan {
        val tools = requiredTools(request)
        val check = if (request.project.rootPath != null) "" else when (Languages.forType(request.type).id) {
            "python" -> "\"\$resolved\" -E -S -c 'import sys; sys.exit(0 if sys.version_info[0] >= 3 else 1)' >/dev/null 2>&1 || { printf '%s\\n' 'Python 3 is required; install python in Termux.' >&2; exit 127; }; "
            // Check launcher support and the compiler module without executing the user's source.
            "java" -> "\"\$resolved\" --source 11 --version >/dev/null 2>&1 && \"\$resolved\" --describe-module jdk.compiler >/dev/null 2>&1 || { printf '%s\\n' 'Java source execution requires JDK 11 or newer with jdk.compiler; install a full JDK in Termux.' >&2; exit 127; }; "
            else -> ""
        }
        // Positional parameters keep filenames and directories out of generated shell source.
        val script = "cd -- \"\$1\" || exit 126; " +
            "if [ -n \"\$2\" ] && [ ! -r \"\$2\" ]; then printf '%s\\n' 'Source is not readable in Termux' >&2; exit 126; fi; " +
            "shift 2;while [ \"\$#\" -gt 0 ]; do tool=\$1;resolved=\$2;shift 2; " +
            "if [ -z \"\$resolved\" ] || [ ! -x \"\$resolved\" ]; then resolved=\$(command -v -- \"\$tool\") || { printf 'Missing runtime: %s\\n' \"\$tool\" >&2; exit 127; }; fi; " +
            "case \"\$resolved\" in /*) ;; *) exit 127;; esac;" + check + "printf '%s\\n' \"\$resolved\";done"
        val source = if (request.project.rootPath == null) request.path.orEmpty() else ""
        return Plan(Utils.PREF + "/usr/bin/bash", listOf("-c", script, "termuc-probe", workingDirectory(request), source) + tools.flatMap { listOf(it, cached[it].orEmpty()) },
            Utils.PREF + "/home", true)
    }
}
