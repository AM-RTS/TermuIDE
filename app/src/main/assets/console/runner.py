"""TermuIDE's single-run PTY bridge. Executed by Termux Python, never Android Python."""
import errno
import fcntl
import hmac
import os
import pty
import selectors
import signal
import socket
import struct
import sys
import tempfile
import termios

MAX_FRAME = 32768
MAX_QUEUE = 262144


def frame(kind, data=b''):
    return struct.pack('!I', len(data) + 1) + kind + data


def kill_run(pid, master):
    # The controlling PTY's foreground job may use its own group within this run's session.
    groups = {pid}
    try:
        foreground = os.tcgetpgrp(master)
        if foreground > 0:
            groups.add(foreground)
    except OSError as error:
        if error.errno not in (errno.EIO, errno.ENOTTY):
            raise
    for group in sorted(groups, key=lambda group: group == pid):
        try:
            os.killpg(group, signal.SIGKILL)
        except ProcessLookupError:
            pass


def run(port, token, argv, runtime_directory):
    peer = socket.create_connection(('127.0.0.1', port), timeout=10)
    peer.sendall(b'TERMUIDE1 ' + token.encode('ascii') + b'\n')
    reply = bytearray()
    while len(reply) < 3:
        part = peer.recv(3 - len(reply))
        if not part:
            raise ConnectionError('Console authentication failed')
        reply.extend(part)
    if not hmac.compare_digest(reply, b'OK\n'):
        raise ConnectionError('Console authentication failed')
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 80, 0, 0))
    ready_read, ready_write = os.pipe()
    pid = os.fork()
    if pid == 0:
        try:
            os.close(ready_read)
            peer.close()
            os.close(master)
            os.setsid()
            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
            for target in (0, 1, 2):
                os.dup2(slave, target)
            if slave > 2:
                os.close(slave)
            os.write(ready_write, b'1')
            os.close(ready_write)
            env = dict(os.environ, TERM='xterm-256color', COLORTERM='truecolor', TERMUIDE_RUN_DIR=runtime_directory)
            os.execvpe(argv[0], argv, env)
        except BaseException as error:
            try:
                os.write(2, ('Unable to execute command: %s\r\n' % error).encode('utf-8', 'replace'))
            finally:
                os._exit(127)
    os.close(slave)
    os.close(ready_write)
    try:
        pump(peer, master, pid, ready_read)
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGHUP, signal.SIG_IGN)
        # A disconnect or signal during startup must wait for the dedicated group to exist.
        os.read(ready_read, 1)
        os.close(ready_read)
        kill_run(pid, master)
        os.waitpid(pid, 0)
        os.close(master)
        peer.close()


def pump(peer, master, pid, ready_read):
    os.read(ready_read, 1)
    peer.setblocking(False)
    os.set_blocking(master, False)
    incoming, outgoing, input_queue = bytearray(), bytearray(), bytearray()
    eof = False
    exited = False
    child_status = None
    exit_sent = False
    poll = selectors.DefaultSelector()

    def kill_group():
        # Keep the leader unreaped until cleanup so its process-group ID cannot be reused.
        kill_run(pid, master)

    def watch(fd, events):
        try:
            poll.get_key(fd)
        except KeyError:
            if events:
                poll.register(fd, events)
        else:
            if events:
                poll.modify(fd, events)
            else:
                poll.unregister(fd)

    try:
        while True:
            if not exited:
                child_status = os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                if child_status:
                    exited = True
                    kill_group()
            if exited and eof and not exit_sent:
                code = child_status.si_status if child_status.si_code == os.CLD_EXITED else 128 + child_status.si_status
                outgoing.extend(frame(b'E', struct.pack('!i', code)))
                exit_sent = True
            if exit_sent and not outgoing:
                break
            watch(peer, selectors.EVENT_READ | (selectors.EVENT_WRITE if outgoing else 0))
            watch(master, (selectors.EVENT_READ if not eof and len(outgoing) < MAX_QUEUE else 0)
                  | (selectors.EVENT_WRITE if input_queue and not eof else 0))
            for key, events in poll.select(0.1):
                if key.fileobj is peer:
                    if events & selectors.EVENT_READ:
                        try:
                            data = peer.recv(MAX_FRAME)
                        except BlockingIOError:
                            continue
                        if not data:
                            return
                        incoming.extend(data)
                        while len(incoming) >= 4:
                            size = struct.unpack('!I', incoming[:4])[0]
                            if not 1 <= size <= MAX_FRAME:
                                raise ValueError('Invalid console frame')
                            if len(incoming) < size + 4:
                                break
                            kind, payload = incoming[4:5], incoming[5:4 + size]
                            del incoming[:4 + size]
                            if kind == b'I':
                                if len(input_queue) + len(payload) > MAX_QUEUE:
                                    raise ValueError('Console input queue is full')
                                input_queue.extend(payload)
                            elif kind == b'R' and len(payload) == 8:
                                rows, columns = struct.unpack('!II', payload)
                                if not (1 <= rows <= 1000 and 2 <= columns <= 1000):
                                    raise ValueError('Invalid terminal size')
                                try:
                                    fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack('HHHH', rows, columns, 0, 0))
                                except OSError as error:
                                    if error.errno not in (errno.EIO, errno.ENOTTY):
                                        raise
                            elif kind == b'C' and not payload:
                                if eof:
                                    continue
                                try:
                                    attributes = termios.tcgetattr(master)
                                    interrupt_char = attributes[6][termios.VINTR]
                                    if attributes[3] & termios.ISIG and interrupt_char in (b'\x03', 3):
                                        if not attributes[3] & termios.NOFLSH:
                                            input_queue.clear()
                                            termios.tcflush(master, termios.TCIFLUSH)
                                        group = os.tcgetpgrp(master)
                                        if group > 0:
                                            os.killpg(group, signal.SIGINT)
                                    else:
                                        # Raw-mode programs consume Ctrl+C as a byte, ahead of pending input.
                                        input_queue.clear()
                                        input_queue.append(3)
                                except (OSError, termios.error) as error:
                                    if error.args[0] not in (errno.EIO, errno.ENOTTY, errno.ESRCH):
                                        raise
                            elif kind == b'K' and not payload:
                                kill_group()
                            else:
                                raise ValueError('Unknown console frame')
                    if events & selectors.EVENT_WRITE and outgoing:
                        try:
                            count = peer.send(outgoing)
                        except BlockingIOError:
                            count = 0
                        del outgoing[:count]
                else:
                    if events & selectors.EVENT_READ:
                        try:
                            data = os.read(master, 16384)
                        except OSError as error:
                            if error.errno == errno.EAGAIN:
                                continue
                            if error.errno != errno.EIO:
                                raise
                            data = b''
                        if data:
                            outgoing.extend(frame(b'O', data))
                        else:
                            eof = True
                            input_queue.clear()
                    if events & selectors.EVENT_WRITE and input_queue and not eof:
                        try:
                            count = os.write(master, input_queue)
                            del input_queue[:count]
                        except OSError as error:
                            if error.errno in (errno.EIO, errno.EPIPE):
                                input_queue.clear()
                            elif error.errno != errno.EAGAIN:
                                raise
    finally:
        poll.close()


if __name__ == '__main__':
    def interrupted(signum, unused):
        raise InterruptedError('Console runner stopped')

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    try:
        with tempfile.TemporaryDirectory(prefix='termuide-run-', dir=os.environ.get('TMPDIR')) as directory:
            run(int(sys.argv[1]), sys.argv[2], sys.argv[3:], directory)
    except Exception as error:
        print('TermuIDE console: %s' % error, file=sys.stderr)
        sys.exit(1)
