'use strict';
window.addEventListener('error', () => ConsoleBridge.failed());
window.addEventListener('unhandledrejection', () => ConsoleBridge.failed());
let terminal;
let fit;
let omitted;
let resizePending = false;
let restoring = false;
let inputEnabled = false;

function wrappedWidth(buffer, row) {
  const last = buffer.getLine(row)?.getCell(terminal.cols - 1);
  return last && last.getCode() === 0 && last.getWidth() === 1 &&
    buffer.getLine(row + 1)?.getCell(0)?.getWidth() === 2 ? terminal.cols - 1 : terminal.cols;
}

function anchorPosition(buffer, position) {
  // Selection coordinates do not follow reflow; anchor each endpoint to its logical wrapped line.
  let row = position.y;
  while (row > 0 && buffer.getLine(row)?.isWrapped) row--;
  let offset = position.x;
  for (let i = row; i < position.y; i++) offset += wrappedWidth(buffer, i);
  const marker = terminal.registerMarker(row - buffer.baseY - buffer.cursorY);
  return marker ? { marker, offset } : undefined;
}

function resolvePosition(buffer, anchor) {
  if (!anchor || anchor.marker.isDisposed) return undefined;
  let row = anchor.marker.line;
  let offset = anchor.offset;
  while (buffer.getLine(row + 1)?.isWrapped && offset >= wrappedWidth(buffer, row)) {
    offset -= wrappedWidth(buffer, row++);
  }
  return { x: Math.min(offset, terminal.cols), y: row };
}

function fitTerminal() {
  const buffer = terminal.buffer.active;
  const selection = terminal.getSelectionPosition();
  const anchors = buffer.type === 'normal' && selection ?
    [anchorPosition(buffer, selection.start), anchorPosition(buffer, selection.end)] : [];
  const viewport = buffer.type === 'normal' && buffer.viewportY < buffer.baseY ?
    anchorPosition(buffer, { x: 0, y: buffer.viewportY }) : undefined;
  try {
    fit.fit();
    if (anchors.length) {
      const start = resolvePosition(buffer, anchors[0]);
      const end = resolvePosition(buffer, anchors[1]);
      if (start && end) terminal.select(start.x, start.y, (end.y - start.y) * terminal.cols + end.x - start.x);
      else terminal.clearSelection();
    }
    const top = resolvePosition(buffer, viewport);
    if (top) terminal.scrollToLine(top.y);
  } finally {
    [...anchors, viewport].forEach(anchor => anchor?.marker.dispose());
  }
}

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
          requestAnimationFrame(() => { resizePending = false; fitTerminal(); });
        }
      }).observe(document.getElementById('terminal'));
    }
    terminal.options.theme = { background: options.background, foreground: options.foreground, cursor: options.cursor };
    this.setFontSize(options.fontSize);
  },
  setFontSize(size) {
    if (!terminal || !Number.isFinite(size)) return;
    terminal.options.fontSize = Math.max(8, Math.min(32, Math.round(size)));
    fitTerminal();
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
