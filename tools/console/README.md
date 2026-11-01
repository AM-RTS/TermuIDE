# Run console

`app/src/main/assets/console/runner.py` runs under Termux Python using only its standard library. The Android controller owns the authenticated socket; xterm.js renders bytes offline inside a Material 3 screen. See [runtime architecture](../../docs/architecture/multilanguage-runtime.md#execution-and-workspace-boundaries) for ownership and cleanup limits.

Both GitHub workflows run the Linux PTY regressions before building:

```sh
TERMUIDE_CONSOLE_BROWSER_TEST=1 python3 -m unittest discover -s tools/console -p 'test_*.py' -v
```

These exercise real TTY input, resize, nonzero exits, output draining, invalid controls, authentication and process-group cleanup. JVM tests cover the Android-side protocol, renderer replacement, bounded history and failed-save recovery. [CI 41339676267](https://github.com/AM-RTS/TermuIDE/actions/runs/41339676267), code `c5dfab8`, passed all seven PTY checks and 300 app tests, and compiled debug/device-test/signed release APKs. These checks do not establish Android PTY permissions, WebView keyboard/accessibility behaviour or background-process survival. Those require a physical device.

## Vendored terminal renderer

The browser regression uses Chrome already installed on the GitHub runner and exercises the bundled xterm.js: historical query suppression, live query replies, Unicode paste, detached rendering and input enabled/disabled state. It does not install an npm dependency or run an Android emulator. JVM regressions additionally cover full input queues, authentication cancellation/deadlines, exit/write races and renderer ownership across host replacement. These review additions await CI verification.

Unmodified runtime distribution files, downloaded from the npm registry:

| Package | Version | Tarball SHA-256 |
| --- | --- | --- |
| `@xterm/xterm` | 5.5.0 | `bd954fa721872170188cc5d7e83e88db3c83c9a18a4e8d24c2783d26491f59d2` |
| `@xterm/addon-fit` | 0.10.0 | `917ac44972453d5eed52edc1e50260c76398ce48cf2290c2e60671102bba0b33` |

Copy `package/lib/xterm.js`, `package/css/xterm.css`, and `package/lib/addon-fit.js` into the console assets. Original MIT license files are bundled in `app/src/main/assets/licenses/` and exposed by About. Source: [xterm.js 5.5.0](https://github.com/xtermjs/xterm.js/tree/5.5.0). Source maps are not required for runtime and are not bundled. No CDN or network access is used by the renderer.
