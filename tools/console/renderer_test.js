'use strict';
(async () => {
  const checks = [];
  function check(condition, message) { if (!condition) throw new Error(message); checks.push(message); }
  let sequence = 0;
  function write(data, historical = false) {
    const id = String(++sequence);
    return new Promise(resolve => {
      pending.set(id, resolve);
      consoleTerminal.write(btoa(data), id, false, historical);
    });
  }
  try {
    consoleTerminal.configure({ omitted: '[omitted]', language: 'en', background: '#000000', foreground: '#ffffff', cursor: '#ffffff', fontSize: 14, label: 'terminal' });
    consoleTerminal.enable(true);
    await write('\x1b[5n', true);
    check(replies.length === 0, 'Restored terminal queries cannot inject historical responses into stdin');
    await write('\x1b[5n');
    check(replies.join('') === '\x1b[0n', 'Live terminal queries receive their response');
    replies.length = 0;
    terminal.paste('é🙂\n');
    check(replies.join('') === 'é🙂\r', 'Unicode paste is forwarded after history restoration');
    replies.length = 0;
    terminal.element.remove();
    await write('\x1b[5n');
    check(replies.join('') === '\x1b[0n', 'A detached retained renderer still processes terminal queries');
    document.getElementById('terminal').appendChild(terminal.element);
    check(!terminal.options.disableStdin, 'Live output does not leave keyboard input disabled');
    await write('\r\n' + Array.from({ length: 60 }, (_, i) => 'row' + i).join('\r\n'));
    terminal.scrollToLine(10);
    terminal.select(0, 10, 4);
    const selected = terminal.getSelection();
    const previousCols = terminal.cols;
    replies.length = 0;
    consoleTerminal.setFontSize(20);
    check(terminal.options.fontSize === 20 && terminal.cols < previousCols && sizes.at(-1).cols === terminal.cols,
      'Text sizing refits the terminal and reports new PTY dimensions');
    check(terminal.getSelection() === selected && selected.length > 0,
      'Resizing keeps the existing selection of unwrapped text');
    check(terminal.buffer.active.viewportY < terminal.buffer.active.baseY && replies.length === 0,
      'Resizing retains scrollback position without injecting input');
    terminal.scrollToBottom();
    terminal.scrollLines(-3);
    check(terminal.buffer.active.viewportY === terminal.buffer.active.baseY - 3,
      'Scrollback still works after text resizing');
    terminal.paste('after resize');
    check(replies.join('') === 'after resize' && !terminal.options.disableStdin,
      'Input remains enabled after text resizing');
    consoleTerminal.setFontSize(100);
    check(terminal.options.fontSize === 32, 'Oversized text is bounded');
    consoleTerminal.setFontSize(1);
    check(terminal.options.fontSize === 8, 'Undersized text is bounded');
    consoleTerminal.setFontSize(NaN);
    check(terminal.options.fontSize === 8, 'Invalid text sizes are ignored');
    consoleTerminal.setFontSize(14);
    check(terminal.options.fontSize === 14 && terminal.cols === previousCols, 'Reset refits the original text size');
    consoleTerminal.enable(false);
    consoleTerminal.setFontSize(20);
    check(terminal.options.disableStdin, 'Text sizing cannot enable input after completion');
    await write('finished output', true);
    check(terminal.options.disableStdin, 'Restoring output cannot reenable input after program completion');
    check(!failed, 'The bundled renderer completes without a script failure');
    document.getElementById('result').textContent = JSON.stringify({ ok: true, checks });
  } catch (error) {
    document.getElementById('result').textContent = JSON.stringify({ ok: false, error: String(error), checks });
  }
})();
