'use strict';
(async () => {
  const checks = [];
  function check(condition, message) { if (!condition) throw new Error(message); checks.push(message); }
  let sequence = 0;
  function write(data, historical = false) {
    const id = String(++sequence);
    return new Promise(resolve => {
      pending.set(id, resolve);
      consoleTerminal.write(btoa(String.fromCharCode(...new TextEncoder().encode(data))), id, false, historical);
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
    await write('\x1bc' + Array.from({ length: 60 }, (_, i) => 'long row ' + i + ': ' + 'abcdef'.repeat(40)).join('\r\n') + '\r\n');
    terminal.scrollToLine(10);
    terminal.select(3, 10, 120);
    const wrappedSelection = terminal.getSelection();
    function viewportParagraph() {
      const buffer = terminal.buffer.active;
      let row = buffer.viewportY;
      while (row > 0 && buffer.getLine(row).isWrapped) row--;
      let text = buffer.getLine(row).translateToString(true);
      while (buffer.getLine(row + 1)?.isWrapped) text += buffer.getLine(++row).translateToString(true);
      return text;
    }
    const scrolledParagraph = viewportParagraph();
    consoleTerminal.setFontSize(20);
    check(terminal.getSelection() === wrappedSelection, 'Resizing preserves selected text across wrapped lines');
    check(viewportParagraph() === scrolledParagraph, 'Resizing keeps the same wrapped paragraph in view');
    consoleTerminal.setFontSize(14);
    check(terminal.getSelection() === wrappedSelection, 'Reset preserves a wrapped selection');
    await write('\x1bc' + Array.from({ length: 45 }, (_, i) => 'unicode row ' + i + ': ' + '界a🙂b'.repeat(40)).join('\r\n') + '\r\n');
    terminal.select(3, 10, 120);
    const unicodeSelection = terminal.getSelection();
    consoleTerminal.setFontSize(21);
    check(terminal.getSelection() === unicodeSelection, 'Wide Unicode selections survive wrapping changes');
    consoleTerminal.setFontSize(14);
    check(terminal.getSelection() === unicodeSelection, 'Reset preserves wide Unicode selections');
    consoleTerminal.enable(false);
    consoleTerminal.setFontSize(20);
    check(terminal.options.disableStdin, 'Text sizing cannot enable input after completion');
    await write('finished output', true);
    check(terminal.options.disableStdin, 'Restoring output cannot reenable input after program completion');
    await write('\x1bc\x1b[31mred\x1b[0m\r\n界🙂 "quoted" \\path\r\n');
    let snapshot = consoleTerminal.snapshotText();
    check(snapshot.text === 'red\n界🙂 "quoted" \\path\n' && !snapshot.truncated,
      'Copy snapshots contain rendered Unicode and actual newlines without ANSI codes');
    const frozen = snapshot.text;
    await write('new output');
    check(snapshot.text === frozen && consoleTerminal.snapshotText().text.endsWith('new output'),
      'Snapshots stay frozen while the live terminal keeps rendering');
    const wrappedText = 'a paragraph with spaces '.repeat(20);
    await write('\x1bc' + wrappedText + '\r\nnext');
    snapshot = consoleTerminal.snapshotText();
    check(snapshot.text === wrappedText + '\nnext', 'Soft wrapping introduces no extra line breaks or lost spaces');
    consoleTerminal.enable(true);
    replies.length = 0;
    terminal.select(0, 0, 4);
    const copySelection = terminal.getSelection();
    consoleTerminal.snapshotText();
    terminal.paste('after snapshot');
    check(replies.join('') === 'after snapshot' && copySelection.length > 0 && !terminal.options.disableStdin,
      'Taking a snapshot does not disable terminal input or inject replies');
    await write('\x1b[?1049h\x1b[Halt screen');
    check(consoleTerminal.snapshotText().text === 'alt screen', 'Copy uses the active alternate screen without mixing old output');
    await write('\x1b[?1049l\x1bc');
    for (let i = 0; i < 20; i++) await write('🙂'.repeat(2000));
    snapshot = consoleTerminal.snapshotText();
    check(snapshot.truncated && snapshot.text.length <= 65536 && snapshot.text.replaceAll('🙂', '') === '',
      'Large copy snapshots are bounded and never split Unicode surrogate pairs');
    await write('\x1bc');
    check(consoleTerminal.snapshotText().text === '', 'An empty terminal produces an empty snapshot');
    check(!failed, 'The bundled renderer completes without a script failure');
    document.getElementById('result').textContent = JSON.stringify({ ok: true, checks });
  } catch (error) {
    document.getElementById('result').textContent = JSON.stringify({ ok: false, error: String(error), checks });
  }
})();
