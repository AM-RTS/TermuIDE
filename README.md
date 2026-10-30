# TermuIDE

[Source](https://github.com/AM-RTS/TermuIDE) · [GPL v3](LICENSE.md) · [Acknowledgements](NOTICE.md) · [中文 README](README_zh.md)

TermuIDE is an independent fork of [TermuC](https://github.com/RainbowC0/TermuC), a Termux-backed C/C++ IDE. This fork adds a Material 3 Compose interface, the [Sora editor](https://github.com/Rosemoe/sora-editor/tree/0.24.4), durable recovery and basic Python/Java console execution. It is maintained by AM-RTS and contributors and is not an official TermuC or Termux release.

- App name: **TermuIDE**
- Application ID and app namespace: **`amrts.termuide`**
- Minimum Android: **8.1 / API 27**; target SDK: **34**
- Build environment: **JDK 17**; Sora dependency: **0.24.4**

## What is different in this fork

| Area | Changes from the inherited TermuC app |
| --- | --- |
| Interface | Material 3 Compose screens, settings and dialogs; adaptive browser/editor layout; labeled accessibility controls and saved form/dialog state. |
| Editor | Sora rendering/input integrated with the inherited document, undo, lexer and completion utilities; shared LSP sessions for clangd, Python pylsp, and optional Java completion/diagnostics. Unused legacy rendering/input code was removed. |
| Saving and recovery | Private durable drafts, background revision-aware saves, atomic/journaled publication, source-read failure protection, and optional auto-save. |
| Execution | Shared preparation and save checks for C/C++, basic Python 3 and Java source execution, literal program arguments, entry file and working-directory overrides. |
| Projects and tabs | `termuide.json` project files; retained workspace coordination and path-based tab selection reuse existing save/document components. |
| Debugger | Separate GDB transport/parser state and Compose presentation; fixes for overlay layout/reconnection, stale inspection data and breakpoint controls. |
| Attribution | In-app About, offline license texts, source links, and explicit upstream/component acknowledgements. |

Python/Java execution is basic console support. Python autocomplete uses the separately installed `pylsp` language server; Java completion and diagnostics use an optional, separately installed TermuIDE Java server bundle with JDK 25. Python/Java debugging, dependency management and build-tool integration remain future work. C/C++ retains clangd and GDB integration.

## Installation and project identity

Use APKs published by this fork in [its releases](https://github.com/AM-RTS/TermuIDE/releases) or build from this source. Upstream TermuC releases and its F-Droid listing are different applications.

`amrts.termuide` installs separately from `cn.rbc.termuc` and the earlier `cn.rbc.termuc.devicecheck` test app. Android does not automatically copy their private settings, saved run configurations, permissions or recovery drafts into TermuIDE. Save/export unsaved work in the old app before removing it. Shared source files remain on disk; grant TermuIDE its own storage, Termux RUN_COMMAND and overlay permissions.

Projects are detected through **`termuide.json`**. There is no `termuc.json` fallback or automatic project migration. To reuse an older project, rename its configuration to `termuide.json` and review its commands and paths, or create a new TermuIDE project. All bundled templates use the new name.

New projects require a fresh destination folder so templates cannot overwrite existing files. A failed creation keeps the current project and its unsaved documents; a partially created folder may remain after a storage error. Inspect that folder before retrying with a new name.

## Development status

The renamed TermuIDE app, About/licensing additions and preceding workspace-coordination refactor have a successful [signed-release build on GitHub Actions](https://github.com/AM-RTS/TermuIDE/actions/runs/40169775316). The prerelease workflow now requires host regression tests before publication. Local Gradle, builds and tests, plus phone and emulator checks, remain skipped. Host tests, builds and signature verification do not establish physical-device behavior; earlier device results in the [architecture notes](docs/architecture/multilanguage-runtime.md) describe the older TermuC-named checkpoint.

## Automated prereleases

`.github/workflows/release.yml` runs the JVM/Robolectric regression suite and builds a signed release APK on GitHub's Ubuntu runner for every push to `master`. A failed regression check blocks the release build, signing and publication. HTML and JUnit XML reports are uploaded as workflow artifacts on success or failure when generated. Each successful run publishes a **prerelease**, its APK, SHA-256 checksum and commit notes for that push. Manual runs are supported on `master`; reruns get a distinct release tag. Builds are not cancelled by later pushes. Pull requests retain the separate debug build workflow and cannot publish releases or access signing secrets.

Before pushing the workflow, add these repository secrets under **Settings > Secrets and variables > Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Base64 encoding of the release keystore file. |
| `KEYSTORE_PASSWORD` | Keystore password. |
| `KEY_ALIAS` | Signing key alias inside the keystore. |
| `KEY_PASSWORD` | Private-key password (may equal the keystore password). |

Use one persistent release key and keep a secure backup. Never commit the keystore, passwords or encoded keystore. For PowerShell, copy only the encoded value with `[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\release.jks')) | Set-Clipboard`, then paste it directly into the `KEYSTORE_BASE64` secret. The workflow fails if any signing secret is missing; it never publishes unsigned APKs. No personal access token is required: the publishing job uses `GITHUB_TOKEN` with `contents: write` (repository or organization policy must allow this).

CI first calls `bash ./gradlew :app:testDebugUnitTest --no-daemon --stacktrace`, followed on success by `bash ./gradlew :app:assembleRelease --no-daemon --stacktrace -PciBuildNumber="$GITHUB_RUN_NUMBER"`. It then aligns, signs and verifies the APK with Android Build Tools 34.0.0. The build number is added to the source `versionCode`, and CI version names use `0.2.4-ci.<run number>`; local builds keep the source version. Keep the workflow's run numbering and the source base version code increasing to support app updates. Tests cover workspace selection, restored prompts, save/discard completion, durable recovery, project creation, editor integration and runtime planning. Phone and emulator checks are not part of this workflow.

The repository is **AM-RTS/TermuIDE**. The workflow uses GitHub's current repository identity for release publication and generated source/change links. Existing clones should use `git remote set-url origin https://github.com/AM-RTS/TermuIDE.git`.

## Technology

TermuIDE uses `com.termux.RUN_COMMAND` to run installed tools in Termux. The shared LSP client launches clangd for C/C++, pylsp for Python, and the optional Java server described below through the existing netcat stdio bridge. Each language/folder or project has its own connection, port, capabilities and request tracking; tabs in the same scope share a server. Closing its last tab stops that session.

### Python autocomplete setup

Install Python and the server in Termux:

```sh
pkg install python
python -m pip install python-lsp-server
pylsp --help
```

In TermuIDE, select **Settings → Analysis → Completion → Language Server**, then open a `.py` file. The app checks the selected Python interpreter for `python-lsp-server`, then launches pylsp with that interpreter; Python alone is insufficient. Open **⋮ → Language tools** to see each server's status, setup/connection details, and **Restart** after installing a missing server. Tooling startup excludes the workspace and `PYTHONPATH` from its own imports before loading installed packages; ordinary script execution keeps its existing environment. The configured server port is the starting port; additional sessions use distinct ports.

Completion, definitions and signature help reuse the existing editor controls. Diagnostics and formatting depend on installed pylsp providers; the base installation does not promise linting, formatting or refactoring plugins. Unsupported requests, including clangd semantic tokens for pylsp, are suppressed. In **Language tools**, set **Python environment** to an existing virtual-environment folder (for example `.venv`) or an absolute Termux path; leave it blank for default Termux Python. Relative paths use the displayed project root or source folder, independently of the browser's run-argument scope. **Apply** saves the selection and restarts affected Python sessions. Install `python-lsp-server` inside that environment too, for example `.venv/bin/python -m pip install python-lsp-server`. Execution and pylsp use the same `<environment>/bin/python`; Jedi receives that interpreter for package discovery. A missing environment or server reports a setup error instead of silently using global Python. Existing project commands inherit its `VIRTUAL_ENV` and `PATH`; standalone C/C++ and Java retain their existing runtimes. Environment creation remains manual; **Install / Repair** can install pylsp into the saved environment.

Unsaved and recovered text is sent directly to the server without saving the source. Completion edits remain undoable and participate in the same durable recovery as typing. Python positions are translated between Jedi code points and the editor's UTF-16 offsets, including supplementary Unicode characters; filename completions replace the full filename component. Rename/code-action edits reject tabs changed since their request; recovered drafts and documents owned by another session are protected. Missing/disconnected servers leave files editable and recoverable. The prerelease workflow installs a pinned pylsp in a temporary host environment and exercises real sibling-module and environment-only package completion through the app's LSP client, plus execution with the same selected environment; this does not verify Termux/phone transport.

## Install language tools from the app

Open **⋮ → Language tools → Install / Repair** beside C/C++, Python, or Java. Review the explanation, then open Termux to confirm package sizes and answer package-manager prompts. Return to TermuIDE afterward: setup verifies the tools and restarts the affected server. No root is needed. Setup does not save or alter editor documents.

Python uses the saved environment shown in the dialog; apply environment changes before installing, and create a selected virtual environment first. Java installs JDK 25 alongside existing Java, preserves the existing runtime selection, and downloads only a checksum-verified server matching the app's pin. It also installs Python to verify/extract the archive. A matching public tools prerelease must exist; branch-only CI artifacts are not downloaded automatically. An older Termux Java alternatives layout is detected with instructions to update the existing OpenJDK package before retrying. Manual setup below remains available.

Setup runs visibly in Termux. If the app process is killed, check the Termux terminal before retrying; concurrent setup is blocked. Downloads and failures leave existing Java tools in place. Package-manager operations can still fail on unavailable mirrors, insufficient storage, or an interrupted Termux process; review the terminal output before retrying.

## Optional Java completion and diagnostics

Install [Termux OpenJDK 25](https://github.com/termux/termux-packages/tree/master/packages/openjdk-25) and the separate **termuide-java-lsp.tar.gz** tools archive from a build containing this integration. Prereleases attach it alongside the APK; the **Java tooling integration** workflow also uploads it as the `termuide-java-lsp` artifact, with an archive checksum. Verify the checksum supplied by the same build before extracting. No server or JDK is bundled in the APK, and tools are downloaded or installed only after an explicit setup confirmation.

In Termux, from the folder containing the verified archive:

```sh
pkg install openjdk-25
mkdir -p "$HOME/.local/share"
java_tools_dir=$(mktemp -d "$HOME/.local/share/termuide-java-tools.XXXXXXXX")
tar -xzf termuide-java-lsp.tar.gz -C "$java_tools_dir"
ln -sf "$java_tools_dir/termuide-java-lsp/bin/termuide-java-lsp" "$PREFIX/bin/termuide-java-lsp"
termuide-java-lsp --check
```

Each install uses a fresh folder so updates cannot retain obsolete JARs. Keep its extracted `bin` and `lib` folders together. Install under Termux's home, not shared `/sdcard` storage. The launcher uses Termux's installed JDK 25 directly when available; otherwise `java` on its PATH must be exactly version 25 with `jdk.compiler`. This requirement belongs to autocomplete: basic Java execution retains its JDK 11+ requirement and its existing runtime selection. The launcher caps the heap at 256 MiB and uses the VM's default garbage collector; Termux's 32-bit ARM JDK does not include G1.

Select **Settings → Analysis → Completion → Language Server**, open a `.java` file, and use **⋮ → Language tools** for Java status or **Restart** after installing tools. Java files in the same project/folder share one session, independently of C/C++ and Python. Recovered and unsaved buffers synchronize directly; completion edits use the existing undo and durable-recovery path. Missing tools leave documents editable and recoverable. Java package lookup follows unsaved and recovered text; dependent open-file diagnostics refresh without saving. Browser create/rename/delete operations restart affected Java sessions and reopen their live buffers. Changes made outside the IDE may still require **Restart**. Install the matching updated Java tools archive when upgrading this integration; updating the APK does not replace a separately installed server. This initial integration enables completion and diagnostics only; Java navigation, formatting, rename, build-tool import and debugging remain separate work. Analysis currently uses JDK 25 APIs; target-release and extra dependency-classpath settings are not exposed yet. A signed release was checked on a Redmi K20 Pro running Android 11 with Termux JDK 25.0.5: completion, cross-file diagnostics, browser mutations and process-death draft recovery passed. See the [device verification record](docs/architecture/multilanguage-runtime.md#java-physical-device-verification---2026-10-30). Large-project performance and other devices remain unmeasured.

The server is the [AM-RTS fork of George Fraser's Java language server](https://github.com/AM-RTS/java-language-server/tree/codex/termuide-fixes), pinned by `tools/java_lsp/revision`. The tools archive contains its MIT license, retained ReusableCompiler source/notice and GPLv2/Classpath text, the launcher’s GPLv3 license, and exact source URLs. A separate `termuide-java-lsp-sources.tar.gz` contains the pinned server sources and build inputs; dependency JARs retain their own notices. See [acknowledgements](NOTICE.md).

## Features

- [x] Highlighting
- [x] Autocompletion
- [x] Formatting
- [x] Diagnosing
- [x] Compile flags
- [x] Dark mode
- [x] Debugging
- [x] Project management
- [x] Code action
- [x] Semantic highlighting
- [x] Goto definition
- [x] Symbol renaming
- [x] Recovery of unsaved documents
- [ ] Multiple independent workspaces

## Interface

Wide windows can keep the file browser beside the editor; compact windows use a browser sheet. Settings and form controls have labeled touch targets and support larger text. Unfinished form input and pending Save/Discard prompts are restored when Android restores saved instance state.

Tap the filename/path in the top bar to switch open files or close an entry; dirty files use the existing Save/Discard/Cancel prompt. The optional tab strip remains available. The bottom action bar provides Diagnostics, Undo, Redo and Save. Diagnostics opens an on-demand bottom sheet with messages for open files, diagnostic codes when supplied, and the current execution status/exit code. Tap a message to navigate to its file and position. Typing does not open the sheet automatically; extra Sora gutter spacing changes presentation without inserting whitespace into files.

Close controls include the full path for accessibility, so files with identical names remain distinguishable. After edits, the diagnostics list waits for fresh analysis instead of reusing messages from the previous document revision.

## Unsaved document recovery

**Settings → Editor → Auto-save** optionally saves source files after a one-second typing pause. It is off by default. Failed saves retain dirty recovery drafts. Auto-save pauses for detected external file changes and pending Save/Discard or Reload prompts. Restoring a draft alone does not trigger auto-save.

Source saves run in the background. Newer edits remain dirty when an earlier revision finishes saving; Run, Build, Debug and Save-before-close wait for the save result. Local files use synced staging and atomic replacement. Termux document-provider files use synced staging, a source backup and a private recovery journal; interrupted rename sequences are restored when the source is next opened. Providers must support rename operations or saving fails without truncating the source.

Closing an active project saves `termuide.json` through the same background worker and hardened writer. Failed saves keep the project and tabs open. Edits made while that save is running cancel the close and remain recoverable. Pending closes follow Activity recreation and finish when the Activity resumes. Auto-save stays paused throughout closing so it cannot publish discarded edits; a later explicit source save cancels the pending close.

Open documents are checkpointed in private storage at 500 ms intervals while editing and flushed when the app enters the background. On restart, unsaved text is recovered into the editor without overwriting the source file. Closing dirty tabs or switching projects offers Save, Discard, and Cancel.

If reading a source fails, saving stays blocked across Activity recreation and durable recovery until a successful source reload. This prevents a partial buffer from replacing the original file. Current recovery records retain this guard and continue to read older drafts.

Drafts are excluded from Android backup. An abrupt termination before the next checkpoint can lose the latest keystrokes. Recovery snapshots contain text and document metadata independently of the editor implementation.

## Python, Java and shared execution

The [shared runtime](docs/architecture/multilanguage-runtime.md) handles C/C++ Run, Debug, Build and Compile, plus basic Python and Java execution through Termux. All use the existing background save path, revision checks and durable recovery. Preparation aborts after a failed save or if newer edits appear before dispatch.

Open a saved `.py` or `.java` file outside an active TermuIDE project and press **Run**:

- Python requires Termux's `python` executable (Python 3). Scripts run from their source directory, supporting relative files, sibling imports and terminal input.
- Java requires Termux's `java` executable from a full JDK 11 or newer with the `jdk.compiler` module. The launcher compiles and runs the UTF-8 source in memory; it does not generate an Android APK. Put the entry class first in the file and use the conventional `public static void main(String[] args)` entry point.
- Install runtimes in Termux yourself; TermuIDE checks availability and reports missing/incompatible tools. Compiler/runtime errors appear in the terminal and completion preserves the program's exit code.

Open **More options → Run configuration** to set an entry file, program arguments and a working directory. One configuration is saved per project root or standalone browser folder. Empty path fields retain the selected file and default working directory; relative paths start in the configuration's folder. Add one row per argument, without shell quoting; an empty row passes an empty argument. **Use defaults**, then **Save**, clears the overrides. Unconfirmed form values survive Activity recreation.

Run and Debug open the configured entry and save it through the existing recovery-aware path; other dirty tabs remain untouched. Arguments are passed literally, and an optional runtime directory is checked before execution. Native compilation and artifact staging keep their original directory. Build and Compile retain their existing commands, selected file and project directory.

Relative project executables such as `./build/app` retain their project location when a runtime directory is selected. Configured arguments are appended once, after expansion of the existing project command; an empty project command reports an error. A failed configuration save keeps the previous configuration active.

An active `termuide.json` project continues to use its configured commands. Run-configuration arguments are appended to the existing program arguments; selecting a Python/Java entry does not replace the project's command. Additional run settings are stored privately on this installation, so changing them cannot overwrite an unsaved `termuide.json`. Python uses pylsp rather than clangd; Java uses the optional server described above; its navigation, formatting and rename are not enabled yet. Neither enables GDB for Python/Java. Their debuggers, automatic dependency management and Maven/Gradle project integration remain separate future work.

Runtime preparation can be cancelled without affecting editor drafts. Programs use the existing Termux terminal; its controls handle running processes. Execution status distinguishes dispatch from confirmed completion, preserves nonzero exit codes, and reports unknown previous status after an app restart. Runtime checks need a Termux version supporting RUN_COMMAND results (0.109+), its RUN_COMMAND permission and external-app access. A missing result produces a bounded preparation timeout; no emulator validation is claimed.

## Acknowledgements and license

TermuIDE is a modified fork of [TermuC](https://github.com/RainbowC0/TermuC) by RainbowC0 and contributors. The inherited editor utilities come through [CodeEditor](https://github.com/MrIkso/CodeEditor) by MrIkso and contributors, including TextWarrior code by Tah Wei Hoon. Sora 0.24.4 supplies rendering/input; AndroidX, Compose Material 3 and Kotlin supply the application framework. Termux and compiler/runtime/debugger tools are installed separately.

The application retains the **GNU GPL v3** license in [LICENSE.md](LICENSE.md). Individual component notices remain in effect: Sora uses **LGPL 2.1 or later**; attributed TextWarrior files and AndroidX/Kotlin use **Apache 2.0**. See [NOTICE.md](NOTICE.md) for authors, source links, modifications and redistribution information. Full license texts and acknowledgements are also readable offline in **Settings → About → About TermuIDE**. There is no warranty; see the licenses for terms.

When distributing an APK, provide the corresponding source and build instructions for that exact release, retain author notices, and include the applicable licenses. The fork source is available in [AM-RTS/TermuIDE](https://github.com/AM-RTS/TermuIDE).

## Upstream resources

- [TermuC setup guide](https://github.com/RainbowC0/TermuC/wiki/Setup)
- [TermuC usage guide](https://github.com/RainbowC0/TermuC/wiki/Usage)
- [Upstream F-Droid listing](https://f-droid.org/packages/cn.rbc.termuc) — installs the original TermuC, not this fork.

These resources describe upstream behavior and may differ from TermuIDE.
