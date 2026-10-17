"""临时 HTTPS 隧道的启动与退出；只开放朗读接收服务，不安装系统服务。"""

import os
import re
import shutil
import subprocess
import threading


class QuickTunnel:
    """管理本次启动的 cloudflared 子进程，不接管其他隧道。"""

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
