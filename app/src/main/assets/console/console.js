'use strict';
window.addEventListener('error', () => ConsoleBridge.failed());
window.addEventListener('unhandledrejection', () => ConsoleBridge.failed());
let terminal;
let fit;
let omitted;
let resizePending = false;
let restoring = false;
let inputEnabled = false;
window.consoleTerminal = {
  configure(options) {
    omitted = options.omitted;
    document.documentElement.lang = options.language;
    document.body.style.backgroundColor = options.background;
    if (!terminal) {
      terminal = new Terminal({
        cursorBlink: true, scrollback: 2000, screenReaderMode: true, disableStdin: true,
        allowProposedApi: false, fontSize: options.fontSize,
        theme: { background: options.background, foreground: options.foreground, cursor: options.cursor }
      });
      fit = new FitAddon.FitAddon();
      terminal.loadAddon(fit);
      terminal.open(document.getElementById('terminal'));
      terminal.textarea.setAttribute('aria-label', options.label);
      terminal.onData(data => { if (!restoring) ConsoleBridge.input(data); });
      terminal.onResize(size => ConsoleBridge.resize(size.cols, size.rows));
      new ResizeObserver(() => {
        if (!resizePending) {
          resizePending = true;
          requestAnimationFrame(() => { resizePending = false; fit.fit(); });
        }
      }).observe(document.getElementById('terminal'));
    }
    terminal.options.theme = { background: options.background, foreground: options.foreground, cursor: options.cursor };
    terminal.options.fontSize = options.fontSize;
    fit.fit();
  },
  write(encoded, sequence, reset, historical = false) {
    restoring = historical;
    terminal.options.disableStdin = historical || !inputEnabled;
    if (reset) {
      terminal.reset();
      terminal.write('\r\n' + omitted + '\r\n');
    }
    const raw = atob(encoded);
    const data = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) data[i] = raw.charCodeAt(i);
    terminal.write(data, () => {
      restoring = false;
      terminal.options.disableStdin = !inputEnabled;
      ConsoleBridge.rendered(sequence);
    });
  },
  enable(enabled) { inputEnabled = enabled; if (terminal) terminal.options.disableStdin = restoring || !enabled; },
  focus() { if (terminal) terminal.focus(); }
};
ConsoleBridge.ready();
