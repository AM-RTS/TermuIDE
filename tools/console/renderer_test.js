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
    consoleTerminal.enable(false);
    await write('finished output', true);
    check(terminal.options.disableStdin, 'Restoring output cannot reenable input after program completion');
    check(!failed, 'The bundled renderer completes without a script failure');
    document.getElementById('result').textContent = JSON.stringify({ ok: true, checks });
  } catch (error) {
    document.getElementById('result').textContent = JSON.stringify({ ok: false, error: String(error), checks });
  }
})();
