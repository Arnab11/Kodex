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
    fun launchCommand(port: Int, token: String, dir: File, shell: String, cols: Int, rows: Int, wakeLock: Boolean, idleMinutes: Int): String {
        val b64 = Base64.encodeToString(SCRIPT.toByteArray(), Base64.NO_WRAP)
        val bin = "\"\$PREFIX/bin/kodex-ptyd\""
        return buildString {
            append("mkdir -p \"\$PREFIX/bin\"; echo $b64 | base64 -d > $bin && chmod 700 $bin; ")
            append("command -v python3 >/dev/null 2>&1 || exit 127; ")
            if (wakeLock) append("termux-wake-lock 2>/dev/null; ")
            append("exec python3 $bin $port $token ${sq(dir.absolutePath)} ${sq(shell)} $cols $rows $idleMinutes")
        }
    }

    private val SCRIPT = """
#!/data/data/com.termux/files/usr/bin/python3
# kodex-ptyd: PTY bridge between a Termux shell and the Kodex app (127.0.0.1 only). Installed by Kodex.
# The shell outlives the connection: if Kodex drops, output is buffered and a reconnect picks it up.
import os, sys, pty, socket, select, struct, fcntl, termios, signal, shutil, hmac, time, traceback

LOG = os.path.expanduser("~/.kodex/ptyd.log")
BUF_MAX = 256 * 1024
OUT_HIGH = 512 * 1024
IN_MAX = 4 * 1024 * 1024

def log(msg):
    try:
        os.makedirs(os.path.dirname(LOG), exist_ok=True)
        if os.path.exists(LOG) and os.path.getsize(LOG) > 100000:
            os.replace(LOG, LOG + ".1")
        with open(LOG, "a") as f:
            f.write("%s [%d] %s\n" % (time.strftime("%F %T"), os.getpid(), msg))
    except OSError:
        pass

def frame(t, data=b""):
    return struct.pack(">BI", t, len(data)) + data

def on_signal(signum, _frame):
    log("got signal %d" % signum)
    raise SystemExit(128 + signum)

class Bridge:
    def __init__(self, port, token, cwd, shell, cols, rows, idle):
        self.token, self.cwd, self.shell, self.cols, self.rows, self.idle = token, cwd, shell, cols, rows, idle
        self.srv = socket.socket()
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", port))
        self.srv.listen(2)
        self.conn = None
        self.rbuf = bytearray()
        self.pending = bytearray()
        self.out = bytearray()
        self.inq = bytearray()
        self.pid = None
        self.fd = None
        self.ever = False
        self.last_client = time.time()

    def frames(self):
        while len(self.rbuf) >= 5:
            t, n = struct.unpack(">BI", bytes(self.rbuf[:5]))
            if n > (1 << 20):
                raise ValueError("frame too large")
            if len(self.rbuf) < 5 + n:
                return
            payload = bytes(self.rbuf[5:5 + n])
            del self.rbuf[:5 + n]
            yield t, payload

    def auth(self, c):
        c.settimeout(2)
        buf = bytearray()
        try:
            while len(buf) < 5 or len(buf) < 5 + struct.unpack(">I", bytes(buf[1:5]))[0]:
                chunk = c.recv(4096)
                if not chunk:
                    return False
                buf += chunk
                if len(buf) > (1 << 16):
                    return False
            t, n = struct.unpack(">BI", bytes(buf[:5]))
            payload = bytes(buf[5:5 + n])
            if t != 0 or not hmac.compare_digest(payload, self.token):
                return False
            self.rbuf = bytearray(buf[5 + n:])
            c.setblocking(False)
            return True
        except (OSError, struct.error):
            return False

    def drop(self, why):
        log("client dropped: " + why)
        try:
            self.conn.close()
        except (OSError, AttributeError):
            pass
        self.conn = None
        self.rbuf = bytearray()
        self.out = bytearray()
        self.last_client = time.time()

    def spawn(self):
        path = shutil.which(self.shell) or shutil.which("bash") or "/system/bin/sh"
        env = dict(os.environ, TERM="xterm-256color", COLORTERM="truecolor", KODEX_TERMINAL="1")
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            try:
                os.chdir(self.cwd)
            except OSError:
                pass
            try:
                os.execvpe(path, [path, "-l"], env)
            finally:
                os._exit(127)
        # Non-blocking master: a full PTY input queue must never stall the single relay loop.
        fl = fcntl.fcntl(self.fd, fcntl.F_GETFL)
        fcntl.fcntl(self.fd, fcntl.F_SETFL, fl | os.O_NONBLOCK)
        fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", self.rows, self.cols, 0, 0))
        log("shell started pid=%d %s" % (self.pid, path))

    def accept_client(self):
        try:
            c, _ = self.srv.accept()
        except OSError:
            return False
        if not self.auth(c):
            log("rejected a connection")
            c.close()
            return False
        if self.conn is not None:
            try:
                self.conn.close()
            except OSError:
                pass
        self.conn = c
        self.out = bytearray()
        self.last_client = time.time()
        if self.pid is None:
            self.spawn()
        else:
            log("client reconnected")
        self.ever = True
        if self.pending:
            self.out += frame(1, bytes(self.pending))
            self.pending = bytearray()
        if self.handle_frames():
            return True
        self.flush_out()
        return False

    def handle_frames(self):
        # True when the client asked to close.
        for t, payload in self.frames():
            if t == 1:
                if len(self.inq) + len(payload) <= IN_MAX:
                    self.inq += payload
                else:
                    log("input queue full, dropped %d bytes" % len(payload))
            elif t == 2 and len(payload) == 4:
                c, r = struct.unpack(">HH", payload)
                try:
                    fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", r, c, 0, 0))
                except OSError:
                    pass
            elif t == 3:
                log("close requested")
                return True
        self.flush_in()
        return False

    def flush_in(self):
        # Keystrokes / pastes into the PTY without ever blocking.
        while self.inq and self.fd is not None:
            try:
                n = os.write(self.fd, bytes(self.inq[:8192]))
            except (BlockingIOError, InterruptedError):
                return
            except OSError:
                self.inq = bytearray()
                return
            del self.inq[:n]

    def flush_out(self):
        # Output towards Kodex without ever blocking: leftovers wait for the socket to be writable.
        while self.out and self.conn is not None:
            try:
                n = self.conn.send(bytes(self.out[:65536]))
            except (BlockingIOError, InterruptedError):
                return
            except OSError:
                self.drop("send failed")
                return
            del self.out[:n]

    def pump_pty(self):
        # False when the shell has ended.
        try:
            data = os.read(self.fd, 16384)
        except (BlockingIOError, InterruptedError):
            return True
        except OSError:
            data = b""
        if not data:
            return False
        if self.conn is not None:
            self.out += frame(1, data)
            self.flush_out()
            return True
        self.pending += data
        if len(self.pending) > BUF_MAX:
            del self.pending[:len(self.pending) - BUF_MAX]
        return True

    def pump_client(self):
        # True when the client asked to close.
        try:
            chunk = self.conn.recv(65536)
        except (BlockingIOError, InterruptedError):
            return False
        except OSError:
            chunk = b""
        if not chunk:
            self.drop("connection closed by Kodex")
            return False
        self.rbuf += chunk
        return self.handle_frames()

    def loop(self):
        while True:
            rl = [self.srv]
            wl = []
            if self.fd is not None:
                # Backpressure: stop reading the shell while Kodex is behind, so the TCP window throttles it.
                if self.conn is None or len(self.out) < OUT_HIGH:
                    rl.append(self.fd)
                if self.inq:
                    wl.append(self.fd)
            if self.conn is not None:
                rl.append(self.conn)
                if self.out:
                    wl.append(self.conn)
            ready, wready, _ = select.select(rl, wl, [], 1.0)
            if self.srv in ready and self.accept_client():
                return "close"
            if self.fd is not None and self.fd in wready:
                self.flush_in()
            if self.conn is not None and self.conn in wready:
                self.flush_out()
            if self.fd is not None and self.fd in ready and not self.pump_pty():
                return "exit"
            if self.conn is not None and self.conn in ready and self.pump_client():
                return "close"
            if self.conn is None:
                limit = self.idle if self.ever else 60
                if time.time() - self.last_client > limit:
                    log("idle timeout (%ds without Kodex)" % limit)
                    return "idle"

    def cleanup(self, reason):
        if self.pid is not None:
            if reason == "exit":
                try:
                    _, st = os.waitpid(self.pid, 0)
                    code = os.waitstatus_to_exitcode(st)
                except Exception:
                    code = -1
                log("shell exited code=%s" % code)
                if self.conn is not None:
                    try:
                        self.conn.settimeout(2)
                        self.conn.sendall(bytes(self.out) + frame(4, struct.pack(">i", code)))
                    except OSError:
                        pass
            else:
                log("stopping shell (%s)" % reason)
                try:
                    os.kill(self.pid, signal.SIGHUP)
                except OSError:
                    pass
                for _ in range(20):
                    try:
                        done, _st = os.waitpid(self.pid, os.WNOHANG)
                    except OSError:
                        break
                    if done:
                        break
                    time.sleep(0.1)
                else:
                    try:
                        os.kill(self.pid, signal.SIGKILL)
                        os.waitpid(self.pid, 0)
                    except OSError:
                        pass
        for s in (self.conn, self.srv):
            try:
                s.close()
            except (OSError, AttributeError):
                pass

    def run(self):
        reason = "error"
        try:
            reason = self.loop()
        except SystemExit:
            reason = "signal"
            raise
        except Exception:
            log("crash:\n" + traceback.format_exc())
        finally:
            self.cleanup(reason)

def main():
    port, token, cwd, shell = int(sys.argv[1]), sys.argv[2].encode(), sys.argv[3], sys.argv[4]
    cols, rows, idle = int(sys.argv[5]), int(sys.argv[6]), float(sys.argv[7]) * 60
    for s in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
        signal.signal(s, on_signal)
    Bridge(port, token, cwd, shell, cols, rows, idle).run()

main()
""".trimStart()
}
