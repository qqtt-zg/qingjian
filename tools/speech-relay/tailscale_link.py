"""只配置专用的 tailnet HTTPS 端口，不启用 Funnel 或重置其他 Serve 服务。"""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess


class TailscaleLink:
    def __init__(self, executable, port, https_port=8443):
        self.executable = shutil.which(executable)
        if not self.executable and os.name == "nt":
            installed = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Tailscale/tailscale.exe"
            if installed.is_file():
                self.executable = str(installed)
        self.port = port
        self.https_port = https_port

    def command(self, *arguments):
        if not self.executable:
            raise RuntimeError("请先安装并登录官方 Tailscale 客户端。")
        try:
            result = subprocess.run([self.executable, *arguments], capture_output=True, text=True,
                                    encoding="utf-8", errors="replace", timeout=20,
                                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        except subprocess.TimeoutExpired as error:
            output = error.stdout or b""
            if isinstance(output, bytes):
                output = output.decode("utf-8", errors="replace")
            raise RuntimeError("Tailscale 等待启用或连接超时，请先完成以下提示再重试：\n" + output) from error
        if result.returncode:
            raise RuntimeError("Tailscale 操作未完成，请检查登录状态和 HTTPS 启用提示。\n" + result.stdout.strip() + result.stderr.strip())
        return result.stdout

    def start(self):
        status = json.loads(self.command("status", "--json"))
        if status.get("BackendState") != "Running":
            raise RuntimeError("Tailscale 尚未连接，请先登录并开启，再重新启动朗读工具。")
        host = status.get("Self", {}).get("DNSName", "").rstrip(".")
        if not re.fullmatch(r"[a-zA-Z0-9-]+\.[a-zA-Z0-9.-]+\.ts\.net", host):
            raise RuntimeError("未取得 Tailscale HTTPS 域名，请确认 MagicDNS 已开启。")
        url = f"https://{host}:{self.https_port}"
        config = json.loads(self.command("serve", "status", "--json") or "{}") or {}
        key = f"{host}:{self.https_port}"
        route = (config.get("Web") or {}).get(key)
        expected = {"Handlers": {"/": {"Proxy": f"http://127.0.0.1:{self.port}"}}}
        tcp = (config.get("TCP") or {}).get(str(self.https_port))
        if (config.get("AllowFunnel") or {}).get(key):
            raise RuntimeError("该端口已启用 Funnel 公网访问，请选择独立 HTTPS 端口。")
        if route is not None or tcp is not None:
            if route != expected or tcp != {"HTTPS": True}:
                raise RuntimeError("该 HTTPS 端口已有其他 Serve 配置，未覆盖，请换用 --tailscale-port。")
        else:
            print("正在配置仅 tailnet 可见的 HTTPS；首次如未启用 Serve，请按 Tailscale 提示启用。", flush=True)
            print(self.command("serve", "--bg", f"--https={self.https_port}", f"http://127.0.0.1:{self.port}"), flush=True)
        return url
