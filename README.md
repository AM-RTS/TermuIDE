# TermuC

[![release](https://img.shields.io/github/release/RainbowC0/TermuC.svg)](https://github.com/RainbowC0/TermuC/releases/) [![license](https://img.shields.io/github/license/RainbowC0/TermuC.svg)](https://github.com/RainbowC0/TermuC/blob/master/LICENSE.md) ![CI](https://github.com/RainbowC0/TermuC/actions/workflows/build-debug.yml/badge.svg?event=push)

[中文 README](./README_zh.md)

TermuC is a simple C/C++ IDE backed on Termux, with basic Python and Java console execution, a Material 3 Compose interface and a [Sora](https://github.com/Rosemoe/sora-editor) editor.

Sora handles rendering and input. The existing document, undo and language utilities from [MrIkso/CodeEditor](https://github.com/MrIkso/CodeEditor) retain clangd integration and editor-independent recovery. The `codeeditor` module remains a dependency for those utilities; the unused old renderer, input handlers, popups and search worker have been removed.

The app pins Sora 0.24.4 ([LGPL-2.1-or-later license and source](https://github.com/Rosemoe/sora-editor)) and builds with JDK 17.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">](https://f-droid.org/packages/cn.rbc.termuc)

## Download

- Artifacts in [Github Actions](//github.com/RainbowC0/TermuC/actions)
- [F-Droid](//f-droid.org/packages/cn.rbc.termuc)

## Screenshot

![1](fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg)

## Technology

This app uses `com.termux.RUN_COMMAND` to call Termux to run command, and run `clangd` language server with `netcat`, which builds an insistent I/O channel, offering functions as diagnosing and compilation.

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
- [ ] Workspace

## Interface

Wide windows can keep the file browser beside the editor; compact windows use a browser sheet. Settings and form controls have labeled touch targets and support larger text. Unfinished form input and pending Save/Discard prompts are restored when Android restores saved instance state.

## Unsaved document recovery

**Settings → Editor → Auto-save** optionally saves source files after a one-second typing pause. It is off by default. Failed saves retain dirty recovery drafts. Auto-save pauses for detected external file changes and pending Save/Discard or Reload prompts. Restoring a draft alone does not trigger auto-save.

Source saves run in the background. Newer edits remain dirty when an earlier revision finishes saving; Run, Build, Debug and Save-before-close wait for the save result. Local files use synced staging and atomic replacement. Termux document-provider files use synced staging, a source backup and a private recovery journal; interrupted rename sequences are restored when the source is next opened. Providers must support rename operations or saving fails without truncating the source.

Closing an active project saves `termuc.json` through the same background worker and hardened writer. Failed saves keep the project and tabs open. Edits made while that save is running cancel the close and remain recoverable. Pending closes follow Activity recreation and finish when the Activity resumes. Auto-save stays paused throughout closing so it cannot publish discarded edits; a later explicit source save cancels the pending close.

Open documents are checkpointed in private storage at 500 ms intervals while editing and flushed when the app enters the background. On restart, unsaved text is recovered into the editor without overwriting the source file. Closing dirty tabs or switching projects offers Save, Discard, and Cancel.

If reading a source fails, saving stays blocked across Activity recreation and durable recovery until a successful source reload. This prevents a partial buffer from replacing the original file. Current recovery records retain this guard and continue to read older drafts.

Drafts are excluded from Android backup. An abrupt termination before the next checkpoint can lose the latest keystrokes. Recovery snapshots contain text and document metadata independently of the editor implementation.

## Python, Java and shared execution

The [shared runtime](docs/architecture/multilanguage-runtime.md) handles C/C++ Run, Debug, Build and Compile, plus basic Python and Java execution through Termux. All use the existing background save path, revision checks and durable recovery. Preparation aborts after a failed save or if newer edits appear before dispatch.

Open a saved `.py` or `.java` file outside an active TermuC project and press **Run**:

- Python requires Termux's `python` executable (Python 3). Scripts run from their source directory, supporting relative files, sibling imports and terminal input.
- Java requires Termux's `java` executable from a full JDK 11 or newer with the `jdk.compiler` module. The launcher compiles and runs the UTF-8 source in memory; it does not generate an Android APK. Put the entry class first in the file and use the conventional `public static void main(String[] args)` entry point.
- Install runtimes in Termux yourself; TermuC checks availability and reports missing/incompatible tools. Compiler/runtime errors appear in the terminal and completion preserves the program's exit code.

Open **More options → Run configuration** to set an entry file, program arguments and a working directory. One configuration is saved per project root or standalone browser folder. Empty path fields retain the selected file and default working directory; relative paths start in the configuration's folder. Add one row per argument, without shell quoting; an empty row passes an empty argument. **Use defaults**, then **Save**, clears the overrides. Unconfirmed form values survive Activity recreation.

Run and Debug open the configured entry and save it through the existing recovery-aware path; other dirty tabs remain untouched. Arguments are passed literally, and an optional runtime directory is checked before execution. Native compilation and artifact staging keep their original directory. Build and Compile retain their existing commands, selected file and project directory.

Relative project executables such as `./build/app` retain their project location when a runtime directory is selected. Configured arguments are appended once, after expansion of the existing project command; an empty project command reports an error. A failed configuration save keeps the previous configuration active.

An active `termuc.json` project continues to use its configured commands. Run-configuration arguments are appended to the existing program arguments; selecting a Python/Java entry does not replace the project's command. Additional run settings are stored privately on this installation, so changing them cannot overwrite an unsaved `termuc.json`. Python/Java highlighting and execution do not enable clangd or GDB for those languages. Language servers, debuggers, dependency environments and Maven/Gradle project integration remain separate future work.

Runtime preparation can be cancelled without affecting editor drafts. Programs use the existing Termux terminal; its controls handle running processes. Execution status distinguishes dispatch from confirmed completion, preserves nonzero exit codes, and reports unknown previous status after an app restart. Runtime checks need a Termux version supporting RUN_COMMAND results (0.109+), its RUN_COMMAND permission and external-app access. A missing result produces a bounded preparation timeout; no emulator validation is claimed.

## Wiki

- [*Setup* (Critical)](//github.com/RainbowC0/TermuC/wiki/Setup)
- [Usage](//github.com/RainbowC0/TermuC/wiki/Usage)
