"""临时与命名 HTTPS 隧道的启动与退出；只开放朗读接收服务，不安装系统服务。"""

import os
import re
import shutil
import subprocess
import threading


class QuickTunnel:
    """管理本次启动的临时 cloudflared 子进程，不接管其他隧道。"""

    def __init__(self, executable, port):
        self.executable = executable
        self.port = port
        self.process = None
        self.reader = None
        self.ready = threading.Event()
        self.url = ""

    def start(self):
        executable = shutil.which(self.executable)
        if not executable:
            raise RuntimeError("未找到 cloudflared，请安装官方工具，或用 --cloudflared 指定路径。")
        self.process = subprocess.Popen(
            [executable, "tunnel", "--no-autoupdate", "--protocol", "http2",
             "--url", f"http://127.0.0.1:{self.port}"],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        self.reader = threading.Thread(target=self.read_output, daemon=True)
        self.reader.start()
        if not self.ready.wait(timeout=60) or not self.url or self.process.poll() is not None:
            raise RuntimeError("无法建立公网连接，请检查网络能否连接 Cloudflare 后重试。")
        return self.url

    def read_output(self):
        try:
            for line in self.process.stdout:
                match = re.search(r"https://[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com\b", line)
                if match:
                    self.url = match.group(0)
                    self.ready.set()
        finally:
            self.ready.set()

    def close(self):
        if self.process is None:
            return
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        if self.reader:
            self.reader.join(timeout=2)
        if self.process.stdout:
            self.process.stdout.close()


class NamedTunnel:
    """管理使用隧道令牌运行的固定公网命名隧道。"""

    def __init__(self, executable, host, token):
        self.executable = executable
        self.host = host.strip()
        self.token = token.strip()
        self.process = None
        self.reader = None
        self.ready = threading.Event()
        self.url = ""
        self.output_tail = []

    def start(self):
        executable = shutil.which(self.executable)
        if not executable:
            raise RuntimeError("未找到 cloudflared，请安装官方工具，或用 --cloudflared 指定路径。")
        env = {**os.environ, "TUNNEL_TOKEN": self.token}
        self.process = subprocess.Popen(
            [executable, "tunnel", "--no-autoupdate", "--protocol", "http2", "run"],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", env=env,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        self.reader = threading.Thread(target=self.read_output, daemon=True)
        self.reader.start()
        if not self.ready.wait(timeout=60) or not self.url or self.process.poll() is not None:
            tail = "".join(self.output_tail[-15:])
            if "Provided Tunnel token is not valid" in tail:
                raise RuntimeError("隧道令牌格式无效，请从 Cloudflare 后台重新复制。")
            if "Failed to get tunnel" in tail:
                raise RuntimeError("未找到该隧道，可能已被后台删除或令牌已轮换。")
            if any(err in tail for err in ("failed to connect", "dial tcp", "context deadline exceeded")):
                raise RuntimeError("连接 Cloudflare 失败，请检查网络。")
            raise RuntimeError(f"命名隧道启动失败：{tail.strip() if tail.strip() else '建连超时'}")
        return self.url

    def read_output(self):
        try:
            for line in self.process.stdout:
                self.output_tail.append(line)
                if len(self.output_tail) > 30:
                    self.output_tail.pop(0)
                if re.search(r"Registered tunnel connection\b", line):
                    self.url = f"https://{self.host}"
                    self.ready.set()
        finally:
            self.ready.set()

    def close(self):
        if self.process is None:
            return
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        if self.reader:
            self.reader.join(timeout=2)
        if self.process.stdout:
            self.process.stdout.close()
