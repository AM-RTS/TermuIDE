# TermuIDE user guide

[README](../README.md) · [Build instructions](building.md)

## Termux access

Install Termux separately and grant TermuIDE its Termux RUN_COMMAND permission. Enable `allow-external-apps=true` in `~/.termux/termux.properties`; see the [Termux RUN_COMMAND setup](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent). Grant storage access to source files and Termux's document tree when requested. GDB floating controls also need Android's overlay permission.

The play button uses **Run here**. Termux executes the program in the background and TermuIDE displays an interactive terminal. Install Termux Python first (`pkg install python`). The bridge uses its standard library. Typing, terminal colours, resizing, Ctrl+C and EOF are supported. **Force stop** targets this run's process group. Swipe down on the console header or tap **Minimize console** to return to the editor while the program runs. Swipe up on the **Run console** bar or tap it to reopen the console.

**⋮ → Run in Termux** retains the external terminal and needs no additional Python bridge for C/C++ or Java. Build, Compile and GDB still use the existing Termux flow. Cancelling preparation never stops a dispatched external command.

The in-app console supports one run at a time. Its renderer and connection survive minimizing the console and Activity recreation. Live terminal queries keep working while minimized, and a finished console keeps its screen and scrollback until the next run. App process death closes the connection and requests cleanup, without replaying execution. Force stop targets the initial process group and the terminal's foreground job. Jobs leaving both groups are not managed. If Android kills Termux or the connection fails, termination may be unconfirmed. Output history is bounded. After renderer loss, restoring that history suppresses old terminal responses to protect program input. Complex interactive programs may need restarting. Editor draft recovery is independent of console output.

## Language tools

Open **⋮ → Language tools** to inspect server status, choose a Python environment, or restart a server. Select **Settings → Analysis → Completion → Language Server** to enable server features.

**Install / Repair** opens an explicit setup confirmation, then a visible Termux session. Review download sizes and answer its prompts. Setup verifies the tools before restarting the affected server; it does not save editor documents and needs no root.

If setup stops reporting results, **Stop waiting** unlocks the app controls without stopping Termux. Check that terminal before retrying; a Termux lock prevents concurrent installers. Package-manager failures and insufficient storage must be resolved there.

### C/C++

Guided setup installs clang (including clangd), GDB and Make. For manual setup:

```sh
pkg install clang gdb make
```

### Python

Guided setup installs Python/pip for the default environment and pylsp in the saved environment. It can install clang for dependency builds. For manual setup:

```sh
pkg install python
python -m pip install python-lsp-server
pylsp --help
```

In **Language tools → Python environment**, leave the field empty for default Termux Python, or select an existing virtual-environment folder. Relative paths resolve against the displayed project root or source folder. **Apply** saves the selection and restarts affected sessions. Create environments manually, then install pylsp inside them:

```sh
python -m venv .venv
.venv/bin/python -m pip install python-lsp-server
```

Execution and pylsp use the same selected `bin/python`; a missing environment never falls back to global Python. Project commands inherit its `VIRTUAL_ENV` and `PATH`. Guided setup ignores custom pip configuration and package indexes; use manual installation when those are needed. Diagnostics, formatting and refactoring depend on installed pylsp plugins.

### Java

Basic single-file execution requires a full JDK 11+ with `jdk.compiler`. Put the entry class first and use `public static void main(String[] args)`. This mode does not discover a project or build an Android APK.

Completion/diagnostics require **JDK 25** and the separate **termuide-java-lsp.tar.gz** archive matching the app's pin in `tools/java_lsp/revision`. No JDK or server is bundled in the APK. Guided setup downloads only a matching published release asset, verifies its digest and preserves the registered Java runtime selection. It also needs Python for archive verification. Older Java alternatives layouts may require updating the existing OpenJDK package before retrying.

For manual installation, obtain the archive and checksum from the same release/build. The **Java tooling integration** workflow also uploads a `termuide-java-lsp` artifact; branch artifacts are not downloaded automatically by guided setup. Verify the supplied checksum before extracting, then run:

```sh
pkg install openjdk-25
mkdir -p "$HOME/.local/share"
java_tools_dir=$(mktemp -d "$HOME/.local/share/termuide-java-tools.XXXXXXXX")
tar -xzf termuide-java-lsp.tar.gz -C "$java_tools_dir"
"$java_tools_dir/termuide-java-lsp/bin/termuide-java-lsp" --check
ln -sf "$java_tools_dir/termuide-java-lsp/bin/termuide-java-lsp" "$PREFIX/bin/termuide-java-lsp"
```

Use a fresh folder for each version and keep `bin` and `lib` together under Termux home, not `/sdcard`. The launcher uses Termux's installed JDK 25 directly when available; otherwise PATH must supply exactly Java 25 with `jdk.compiler`. It limits the heap to 256 MiB and uses the VM's default garbage collector. This analysis runtime is separate from the Java runtime used to execute programs.

Install a matching server archive after an integration update; updating the APK alone does not replace it. Java navigation, formatting, rename, build-tool import and debugging are not enabled. External filesystem changes may require **Restart**.

## Projects and run configurations

Projects use `termuide.json`; there is no automatic `termuc.json` fallback. For an older project, rename its configuration and review commands/paths, or create a new project. New projects require a fresh destination folder. A failed creation can leave a partial folder; inspect it before retrying.

Open **⋮ → Run configuration** to set the entry file, arguments and working directory. Settings are private to this installation, per project root or standalone browser folder. Empty path fields use the selected file and default directory; relative paths start in the configuration's folder. Enter one literal argument per row without shell quoting; an empty row passes an empty argument. **Use defaults → Save** clears overrides.

An active project's configured commands remain authoritative. Arguments are appended once; selecting a Python/Java entry does not replace those commands. Build/Compile keep the selected file and project directory; changing the runtime directory does not move native compilation or staging.

Run/Debug save the entry before dispatch; newer edits or a failed save abort preparation. Other dirty tabs stay untouched. Standalone scripts default to their source directory. Runtime errors appear in Termux; the app reports completion and the exit code when Termux returns a result.

## Editor and recovery

Tap the top filename/path to switch or close open files. Dirty files offer Save/Discard/Cancel. The optional tab strip is available in settings. The bottom bar provides Diagnostics, Undo, Redo and Save; the diagnostics sheet opens on demand and lets you navigate to messages.

Private drafts checkpoint at roughly 500 ms intervals while editing and flush when the app backgrounds. Restoring a draft does not itself trigger auto-save. Auto-save pauses for external source changes and pending close/reload prompts; failed saves keep dirty drafts. If a source could not be fully read, saving remains blocked until a successful reload. Use the Reload action shown in the editor to retry; its confirmation warns that unsaved changes will be discarded.

Source saves run in the background; edits made during saving remain dirty. Local publication uses synced staging and atomic replacement. Termux document providers must support rename; interrupted publication uses a backup/journal for restoration on the next open. A failed project save keeps its project and tabs open. Recovery cannot guarantee edits before a checkpoint or survive clearing app data/uninstallation; drafts are excluded from Android backup.

The GDB console and debug-log panels keep the most recent 64 Ki UTF-16 code units per channel. An omission marker appears when older output is dropped; these panels are not a full-session log archive.
