# Runtime and recovery architecture

[README](../../README.md) · [User guide](../usage.md) · [Build instructions](../building.md)

Implemented: shared C/C++/Python/Java execution, per-language LSP sessions, Python environments, optional Java completion/diagnostics, and guided tool installation. Python/Java debuggers, build-tool import, dependency management and additional Java editing features remain separate work.

## Ownership

| Component | Responsibility |
| --- | --- |
| `Languages` | File types, highlighters, runtime/server selection and native-only GDB eligibility |
| `RunCommands` | Immutable command plans, required tools, literal arguments and working directories |
| `RunController` / `RunResultReceiver` | Preparation, probes, cancellation, correlated Termux results and observable execution status |
| `Utils.run` | Existing RUN_COMMAND transport for execution, language servers, debugging and setup |
| `WorkspaceSession` / `HeaderAdapter` | Path-based selection, tab order, retained file operations, project transitions and pending closes |
| `WorkspacePresentation` | Observable workspace snapshots for tabs, diagnostics, prompts, browser entries and action availability |
| `MainActivity` / `EditFragment` | Android lifecycle, editor hosting, user actions and presentation of results |
| `DocumentSessions` / `DraftStore` | Dirty revisions, document ownership and durable private checkpoints |
| `DocumentSaver` / `SourceWriter` | Serialized background writes, revision-aware completion and source publication |
| `Project` / `RunConfiguration` | One immutable current project configuration and private per-scope run/environment overrides |
| `LanguageServers` / `Lsp` / `LspResponses` / `MainHandler` | Server sessions/transport, view-independent response decoding, revision guards and editor updates |
| `DebugSession` / `DebugPanel` | GDB transport/MI state and Compose overlay presentation |
| `LanguageToolInstaller` | Explicit setup confirmation, pending callbacks and post-install verification |
| `RunConsole` / `console/runner.py` / `ConsoleTerminalView` | Authenticated PTY transport, owned process group, bounded output and offline terminal rendering |
| `TermuxPrompts` | Shared Activity-owned install/initialization prompts, actions and saved state |

Sora supplies rendering/input; inherited `codeeditor` utilities retain document, undo, lexer and completion behavior. The run console reuses bundled xterm.js in an isolated WebView with Material 3 controls. No separate runtime module, DI framework or plugin framework is required.

## Execution and workspace boundaries

Capture the entry, action, project configuration, directory and arguments before asynchronous preparation. Open/save the entry through the normal document path, reload project configuration into a validated snapshot, probe tools/source access in Termux, then revalidate the document owner/revision and context before dispatch. Failed saves, newer edits, switched contexts and stale callbacks must not execute a different file or clear recovery.

Generated paths/arguments are passed positionally, not interpolated into shell source. Explicit project commands and compiler flags remain user-authored shell configuration. Native Run/Debug capture program arguments before expanding commands and append them once. Terminal pause wrappers preserve the program exit code and use real newlines.

C/C++ retains compilation, artifact staging and GDB. Python invokes the resolved interpreter; standalone scripts default to their source directory. Java uses the JDK's UTF-8 source-file launch with `jdk.compiler`, without adding a compiler/main-class discovery pipeline. Existing project commands remain authoritative for every language.

Run configuration is private per project root or browser folder. Relative overrides resolve against that scope; explicitly relative project executables keep their project location when the runtime directory changes. Build/Compile retain their selected file and preparation directory. Python environment selection uses the displayed project/source scope and is shared by execution and pylsp.

The Application-owned controller survives Activity recreation and distinguishes preparation, dispatch, completion, failure and unknown status. Probes have bounded waits; interactive programs may run indefinitely. Cancellation stops preparation, not a dispatched Termux process. After app process death, pending-status metadata reports uncertainty rather than replaying execution.

**Run here** uses the same save/context/probe gates and command planner. Python is additionally required for the standard-library PTY runner; C/C++ and Java execution remain on their existing runtimes. The runner connects to a random loopback port with a per-run 256-bit token, then executes the planned argv in a dedicated controlling terminal/session. Bounded binary frames stream terminal bytes and carry input, resize, interrupt and force-stop commands. The bridge waits for the child's process group before processing controls; the group leader remains unreaped until cleanup to prevent PID reuse. A per-run temporary directory isolates native executables and is cleaned after the group stops. It never kills by executable name or runs a general command endpoint.

One in-app run is supported at a time. Closing its screen retains the Application-owned connection and renderer. A `MutableContextWrapper` swaps the visible host context for the Application on detach, and an old host cannot detach a view already moved to its replacement. Bridge callbacks use the main Handler, so hiding the view cannot strand input/output callbacks in a detached View queue. A finished hidden run releases the renderer. Socket loss requests cleanup of the initial group and the PTY's foreground job; jobs leaving both groups and Android killing the runner itself are outside that guarantee. Confirmed exit frames preserve the program's status; missing frames retain unknown status, including after app process death. Input-side errors allow the reader to consume a buffered exit frame first. No console session is automatically re-executed. Source saves and durable drafts are unchanged.

The app retains at most 256 KiB of raw terminal history; the renderer keeps 2,000 scrollback rows. UI output is acknowledged in batches, without an unbounded JavaScript queue. Hiding/rotating keeps the live terminal state rather than replaying output. After renderer loss, replay is bounded to a captured history boundary and suppresses terminal-generated responses; live output then resumes normally. Gaps reset the display and mark omitted history; replaying a truncated ANSI stream cannot reconstruct every full-screen application's prior state. The WebView serves an allowlist of APK assets from a synthetic HTTPS origin, blocks network/navigation and exposes only session input/resize/render acknowledgements. Program output is passed as bytes, never HTML or JavaScript source. Input is rejected as a whole above 16 KiB; control frames clear queued input, and Ctrl+C respects the PTY's signal/raw mode. Authentication has a total two-second deadline; stopping also closes an authenticating peer. Versions, licenses and checks are recorded in `tools/console/README.md`.

Workspace close completion waits for a resumed host and mutable FragmentManager state. Project-save failure keeps the project open; changed tab/session revisions cancel closing. Auto-save pauses throughout closing, and a later explicit save cancels the pending close. Rotation can retain a confirmed decision; process-death restoration recreates prompts without replaying an in-memory Discard approval.

## Background file work

One Application-owned file executor handles directory listings, source/reload reads, closed-file LSP edit preparation, browser mutations and project parsing/template extraction. The save and checkpoint workers retain their existing responsibilities. Directory generations reject stale listings; source reads validate view identity, document revision and source timestamps before applying. Loading disables source input, saves await it, and failed reads retain the source-load guard.

Browser-operation results live in the retained workspace. Only the current resumed host consumes them; a destroyed Activity is never called to refresh its cleared adapter. Project opening rechecks captured tabs/revisions/selection before applying a saved Discard decision. Project creation checks its original configuration and browser directory before activation. Process death does not persist/replay a Discard approval or a filesystem request.

## Durable recovery and source saves

`DocumentSessions` owns dirty state and source-load guards. `DraftStore` records text/metadata independently of the editor, including UTF-16 text. Checkpoints run while typing and on backgrounding. A failed snapshot/write preserves the preceding valid checkpoint and reports failure; incomplete/corrupt records do not hide valid drafts. A source-read failure blocks saving until a full reload, including after recovery.

`DocumentSaver` flushes recovery before replacing a source. Save points include owner identity and revision; finishing an older save cannot mark newer typing or a reopened document clean. Rejected workers clear pending entries. Run/Build/Debug and Save-before-close await the result.

`SourceWriter` publishes local files via synced staging and atomic replacement, resolving symlinks and retaining supported permissions. Termux providers use synced staging, an original backup and a private journal. If publication fails, rollback restores the original; if rollback also fails, the journal/backup remain for restoration on the next open. Providers must support rename; there is no truncate-in-place fallback.

After successful publication, staging/backup cleanup is best effort and must not report that save as failed. If a retained backup cannot be removed before the next save, that write fails safely and newer edits remain recoverable. Cleanup can be retried on a later save.

`SourceWriter` deliberately retains `.termuc-*` staging names so existing interrupted saves remain recoverable. App identity is `amrts.termuide`; projects use `termuide.json` only. Renaming does not migrate another application's private drafts/settings.

Recovery does not guarantee edits before their checkpoint, device power-loss durability, or survival of clearing app data/uninstallation. Drafts are excluded from Android backup.

## Language servers

`LanguageServers` lazily owns sessions keyed by server and normalized project/folder root. C/C++ share clangd; Python uses pylsp; Java uses the optional `termuide-java-lsp`. Each session has its own port, capabilities, request IDs and open-document set. Allocation avoids immediately reusing a shutting-down bridge. All reuse the Termux netcat stdio bridge; closing the last tab stops its session.

Messages carry their source LSP/generation. Shutdown invalidates queued events and completions; requests and results use document versions/revisions. Reconnect/restart reopens actual live buffers, including recovered unsaved text, without force-saving them. Unsupported capabilities are suppressed; only clangd receives fallback compiler flags. Framing bounds headers/body allocation and validates UTF-8 lengths; initialization has a deadline.

The editor uses UTF-16 offsets. The adapter converts pylsp/Jedi code-point positions unless the server explicitly negotiates another supported encoding. Completion edits remain undoable; workspace edits validate every affected open-document revision, reject another session's buffers, and preserve drafts on stale responses. Diagnostics hide messages from older revisions.

Python tooling excludes workspace imports and poisoned Python environment variables during startup, then loads installed packages. A selected missing environment/server never falls back to global Python. Normal script execution keeps its environment; project commands inherit the selected `PATH`/`VIRTUAL_ENV`.

### Optional Java server

The tools bundle uses the [AM-RTS fork of George Fraser's server](https://github.com/AM-RTS/java-language-server/tree/codex/termuide-fixes), pinned by [`tools/java_lsp/revision`](../../tools/java_lsp/revision). Packaging includes JARs, launcher, notices and a separate corresponding-source archive, not a JDK. The launcher selects exactly JDK 25 with compiler exports, limits heap to 256 MiB and uses the default GC for Termux ARM32 compatibility. This does not switch ordinary Java execution's runtime.

The app supplies a root classpath before opening buffers; automatic Maven/Bazel import is disabled. Only completion and diagnostics are enabled. Analysis uses JDK 25 APIs; target-release and extra classpath controls are not exposed.

The fork invalidates retained javac dependency symbols when live siblings change/close/delete. Package metadata follows unsaved text, including initially empty files and compact package declarations; package changes recreate compiler state. Diagnostics recheck all open Java documents after edits/closure. Completed browser create/rename/delete operations restart affected Java roots only. No continuous filesystem watcher is added; external changes may need manual restart. Large-open-tab analysis cost remains unmeasured.

## Tool installation

`LanguageToolInstaller` reuses RUN_COMMAND, result callbacks and background preflight. Pending tokens/scope/server/environment survive process death; reconstructed jobs show uncertainty. **Stop waiting** cancels app tracking/timeout without killing Termux, and late callbacks are ignored. A Termux `flock` prevents overlapping installers. Setup never owns or mutates document/save state.

Python setup targets the selected interpreter, clears inherited `PIP_*`, disables pip config files and uses isolated mode. Verification requires pylsp inside that interpreter's installation directory. Custom indexes/configuration use manual installation.

Java setup preserves the registered runtime selection and detects incompatible old alternatives metadata before JDK mutation. It searches the latest 20 public releases for a matching pinned tools archive, requires its GitHub SHA-256 digest, and rejects traversal, links, duplicates and oversized archives. A fresh extracted launcher must pass preflight before the symlink is replaced. Existing tools remain on failure; old successful folders remain available for rollback. Branch CI artifacts are not public release assets, so fresh guided installation requires a matching published bundle.

## Debugging

`DebugSession` parses GDB MI into immutable snapshots independently of the Activity. Token-correlated replies tolerate interleaved async events and out-of-order responses. Stop/run transitions invalidate stale variable/frame requests; variable objects are cleaned up. Breakpoint controls use GDB-assigned IDs and quoted MI arguments.

`DebugPanel` renders snapshots on the main thread, ignores closed-session callbacks, resets compact sizing and creates a fresh overlay per connection. The console enables IME focus only while visible, including when detached. System input routing still needs physical-device evidence; direct Compose touch tests alone cannot establish it. Console/debug output is streamed separately from inspection snapshots, with coalesced UI updates. Each visible channel retains up to 64 Ki UTF-16 code units and marks omitted history; variable/frame/control snapshots do not copy that history.

## Architecture review fixes

The review added Activity-typed shared setup actions, a List-based completion boundary, and waiting for pending source loads before capturing project-open revisions. Rotation can restart a confirmed reload only from the retained unchanged document; process-death recovery still requires renewed confirmation before discarding draft text. A failed read exposes an explicit Reload action while saving remains blocked.

Project parsing uses the shared reader, so interrupted provider publication is restored before its timestamp is captured. Oversized debugger records are shortened before appending, preventing the visible-history limit from retaining a much larger backing buffer. Regression fixtures cover these cases alongside file-operation rotation, stale listings, auto-save retry and dirty-draft preservation.

## Verification records

- In-app console: [CI 41339676267](https://github.com/AM-RTS/TermuIDE/actions/runs/41339676267), code `c5dfab8`, passed 300 app tests (zero failures/errors/skips), seven Linux PTY checks and 18 installer/launcher checks. Debug, device-test and minified release APKs compiled; release v2/v3 signatures verified. No phone/emulator checks were run for this feature; PTY permissions, WebView input/accessibility and Android background behaviour remain unverified.

Verification applies to the named revisions; host tests and APK compilation do not replace device checks.

- [App CI 41330252822](https://github.com/AM-RTS/TermuIDE/actions/runs/41330252822), `5a09b23` (November 1, 2026): 291 app host tests with zero failures/errors/skips, 14 installer checks and four launcher checks passed. Debug, device-test and minified signed release APKs compiled; v2/v3 signature verification passed. Uploaded reports confirm retained deletion, source/reload rotation, deferred project opening, edits during project parsing, stale listings, auto-save retry, immutable project state and shared prompt regressions executed. The provider test also verifies project parsing after journal restoration. Device-test sources were compiled, not run. No local Gradle/build/test or phone/emulator check was performed. Numerical branch coverage, mutation strength and repeated-run flake rates remain unmeasured.
- [Server CI 40939268982](https://github.com/AM-RTS/java-language-server/actions/runs/40939268982), `917d14ff`: 253 tests, zero failures/errors, ten existing skips; focused regressions and parser/pruner benchmarks passed. Benchmarks do not measure the all-open-document diagnostic workload.
- October 23, 2026: isolated `cn.rbc.termuc.devicecheck` release on Redmi K20 Pro/Android 11 verified C/C++ Build/Compile/Run, project save/discard, process-death draft recovery and GDB stepping, frames, variables, breakpoints, console, dragging and reconnect. This follow-up superseded an earlier unresponsive-overlay observation. Local evidence: `app/build/phone-project-gdb-check/`.

### Java physical-device verification - 2026-10-30

[App CI 41026228285](https://github.com/AM-RTS/TermuIDE/actions/runs/41026228285), `638d555`, supplied the signed TermuIDE release installed in place on Redmi K20 Pro/Android 11. Server pin: `917d14ff`; Termux analysis JDK 25.0.5 alongside the existing default Java/Javac 17.0.15. Old alternatives metadata needed repair and root-staged files needed normal SELinux labels; SELinux enforcement was not disabled.

| Physical check | Observed result |
| --- | --- |
| Java completion and diagnostics | Unsaved package/class in an empty Helper.java supplied Main's completion; dependency-only changes produced/cleared diagnostics without saving. |
| Durable recovery | App kill/relaunch restored both dirty Java buffers exactly and analysis resumed. Discard restored disk declarations. |
| Browser mutations | Create/rename/delete replaced only affected Java sessions while preserving dirty Main. |
| Console execution | C, C++, Python and packaged Java printed expected markers and returned exit 0; the terminal prompt used real newlines. |
| GDB | Breakpoint, Next, variables, frames, console assignment, Continue and overlay removal worked. A nonblocking controlling-terminal permission warning remained in raw MI output. |

Fixtures were removed and prior tabs/auto-save restored. These checks predate guided installation; that installer has not been exercised on the phone. Python LSP on-device completion, all GDB controls in this build, large-project responsiveness, battery use, other devices/ABIs, power loss and mid-checkpoint kills remain unverified. Recovery kills occurred after checkpointing. Existing screenshots are from this session.

## Java server evaluation

The fixed George Fraser fork was selected for optional Java completion/diagnostics because its smaller package and lower observed host memory use suited the initial scope. The [October 29, 2026 comparison](https://github.com/AM-RTS/TermuIDE/actions/runs/40930279775) (harness `7f9e713`) tested it against JDT LS 1.63.0 on ARM64/x86-64 Ubuntu with Temurin 25.

| Measurement | Fixed George fork | JDT LS |
| --- | --- | --- |
| Server archive, excluding JDK | About 3.10 MB | About 51.14 MB |
| Peak host RSS at 256 MiB heap cap | 171–182 MiB | 575–582 MiB |
| First useful completion at that cap | 0.80–1.02 seconds | 3.17–4.80 seconds |
| Small-fixture checks at that cap | 11/11 on both architectures | 10/11 on ARM64; 11/11 on x86-64 |

JDT's failed check retained a stale sibling method during the five-second retry window; other trials passed, and the cause was not investigated. These were single process trials plus restart, with build-tool import disabled. They do not establish general server quality, large-project scaling or phone memory/latency. The linked run retains detailed measurements and logs; actual app/device verification is recorded above.

## References

- [Termux RUN_COMMAND contract](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
- [Java source-file launch](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#using-source-file-mode-to-launch-single-file-source-code-programs)
- [Python command-line execution](https://docs.python.org/3/using/cmdline.html)
- [GDB MI breakpoint commands](https://sourceware.org/gdb/current/onlinedocs/gdb.html/GDB_002fMI-Breakpoint-Commands.html)
