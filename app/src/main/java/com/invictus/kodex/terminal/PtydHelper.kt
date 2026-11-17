package com.invictus.kodex.terminal

import android.util.Base64
import java.io.File

/**
 * The Termux side of the in-app terminal: a small python script (stdlib only) that listens on
 * 127.0.0.1, checks a one-time token, starts the shell on a PTY and relays bytes. It is rewritten
 * on every start (a few KB), so it can never be out of date with the app.
 */
object PtydHelper {

    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** One RUN_COMMAND line: refresh the script, require python, optionally hold a wake lock, then run it. */
    fun launchCommand(port: Int, token: String, dir: File, shell: String, cols: Int, rows: Int, wakeLock: Boolean): String {
        val b64 = Base64.encodeToString(SCRIPT.toByteArray(), Base64.NO_WRAP)
        val bin = "\"\$PREFIX/bin/kodex-ptyd\""
        return buildString {
            append("mkdir -p \"\$PREFIX/bin\"; echo $b64 | base64 -d > $bin && chmod 700 $bin; ")
            append("command -v python3 >/dev/null 2>&1 || exit 127; ")
            if (wakeLock) append("termux-wake-lock 2>/dev/null; ")
            append("exec python3 $bin $port $token ${sq(dir.absolutePath)} ${sq(shell)} $cols $rows")
        }
    }

    private val SCRIPT = """
#!/data/data/com.termux/files/usr/bin/python3
# kodex-ptyd: PTY bridge between a Termux shell and the Kodex app (127.0.0.1 only). Installed by Kodex.
import os, sys, pty, socket, select, struct, fcntl, termios, signal, shutil, hmac

def frame(t, data=b""):
    return struct.pack(">BI", t, len(data)) + data

def main():
    port, token, cwd, shell = int(sys.argv[1]), sys.argv[2].encode(), sys.argv[3], sys.argv[4]
    cols, rows = int(sys.argv[5]), int(sys.argv[6])
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(1)
    srv.settimeout(60)
    try:
        conn, _ = srv.accept()
    except socket.timeout:
        return
    finally:
        srv.close()
    conn.settimeout(5)
    rbuf = bytearray()

    def frames():
        while len(rbuf) >= 5:
            t, n = struct.unpack(">BI", bytes(rbuf[:5]))
            if n > (1 << 20):
                raise ValueError("frame too large")
            if len(rbuf) < 5 + n:
                return
            payload = bytes(rbuf[5:5 + n])
            del rbuf[:5 + n]
            yield t, payload

    try:
        while not rbuf:
            chunk = conn.recv(4096)
            if not chunk:
                return
            rbuf += chunk
        first = next(frames(), None)
        if first is None or first[0] != 0 or not hmac.compare_digest(first[1], token):
            return
    except (OSError, ValueError):
        return
    conn.settimeout(None)

    path = shutil.which(shell) or shutil.which("bash") or "/system/bin/sh"
    env = dict(os.environ, TERM="xterm-256color", COLORTERM="truecolor", KODEX_TERMINAL="1")
    pid, fd = pty.fork()
    if pid == 0:
        try:
            os.chdir(cwd)
        except OSError:
            pass
        os.execvpe(path, [path, "-l"], env)
    # size is set by the parent only, so a later resize frame can never be overwritten by the child
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))

    client_gone = False

    def handle(t, payload):
        if t == 1:
            os.write(fd, payload)
        elif t == 2 and len(payload) == 4:
            c, r = struct.unpack(">HH", payload)
            fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", r, c, 0, 0))
        elif t == 3:
            raise SystemExit

    try:
        # frames that arrived together with the HELLO (resize, first input)
        for t, payload in frames():
            handle(t, payload)
        while True:
            ready, _, _ = select.select([conn, fd], [], [])
            if fd in ready:
                try:
                    data = os.read(fd, 8192)
                except OSError:
                    data = b""
                if not data:
                    break
                conn.sendall(frame(1, data))
            if conn in ready:
                chunk = conn.recv(65536)
                if not chunk:
                    client_gone = True
                    break
                rbuf += chunk
                for t, payload in frames():
                    handle(t, payload)
    except (OSError, ValueError, SystemExit):
        client_gone = True
    finally:
        if client_gone:
            try:
                os.kill(pid, signal.SIGHUP)
            except OSError:
                pass
        try:
            _, status = os.waitpid(pid, 0)
            code = os.waitstatus_to_exitcode(status)
            if not client_gone:
                conn.sendall(frame(4, struct.pack(">i", code)))
        except Exception:
            pass
        try:
            conn.close()
        except OSError:
            pass

main()
""".trimStart()
}
