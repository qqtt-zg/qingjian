"""原生设置页的后台命令：只连接固定回环地址，以 TOML 返回界面状态。"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zlib

ROOT = Path(os.environ.get("APPDATA", str(Path.home()))) / "Qingjian/speech"
REG_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
REG_RUN_NAME = "QingjianSpeechRelay"


def settings():
    text = (ROOT / "connection.toml").read_text(encoding="utf-8")
    port = int(re.search(r"port\s*=\s*(\d+)", text)[1])
    token = re.search(r'token\s*=\s*"([a-f0-9]{32})"', text)[1]
    if not 1 <= port <= 65535:
        raise ValueError("连接端口无效")
    return port, token


def request(path, data=None, text=False):
    port, token = settings()
    headers = {"Authorization": "Bearer " + token}
    body = None
    if data is not None:
        headers["Content-Type"] = "text/plain; charset=utf-8" if text else "application/json"
        body = data.encode("utf-8") if text else json.dumps(data).encode("utf-8")
    req = urllib.request.Request(f"http://127.0.0.1:{port}" + path, body, headers)
    # 本机请求不经过环境代理，防止管理凭证交给代理服务。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=3) as response:
        raw = response.read(1024 * 1024)
        return json.loads(raw)


def qr_image(url):
    if not url.startswith("https://"):
        return ""
    path = ROOT / ("qr-" + hashlib.sha256(url.encode()).hexdigest()[:16] + ".png")
    if path.exists():
        return str(path)
    script = "const q=require(process.argv[1]);let s='';process.stdin.on('data',b=>s+=b);process.stdin.on('end',()=>{let a=q(0,'M');a.addData(s);a.make();process.stdout.write(JSON.stringify(Array.from({length:a.getModuleCount()},(_,r)=>Array.from({length:a.getModuleCount()},(_,c)=>a.isDark(r,c)?1:0))))});"
    tool = shutil.which("node")
    if not tool:
        return ""
    result = subprocess.run([tool, "-e", script, str(Path(__file__).with_name("vendor") / "qrcode.js")], input=url, capture_output=True, text=True, encoding="utf-8", timeout=10,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0, check=True)
    matrix = json.loads(result.stdout)
    size = (len(matrix) + 8) * 5
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)
        for x in range(size):
            row, col = y // 5 - 4, x // 5 - 4
            pixels.append(0 if 0 <= row < len(matrix) and 0 <= col < len(matrix) and matrix[row][col] else 255)
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 0, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)
    return str(path)


def read_named_config():
    """读取命名隧道配置，返回 (host, token)；不存在或不合法返回 None。"""
    path = ROOT / "named-tunnel.toml"
    if not path.is_file():
        return None
    try:
        text = path.read_text(encoding="utf-8")
        host_match = re.search(r'host\s*=\s*"([^"]+)"', text)
        token_match = re.search(r'token\s*=\s*"([^"]+)"', text)
        if not host_match or not token_match:
            return None
        host = host_match[1].strip()
        token = token_match[1].strip()
        if not host or not token:
            return None
        return {"host": host, "token": token}
    except OSError:
        return None


def is_autostart_enabled():
    if os.name != "nt":
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_RUN_KEY, 0, winreg.KEY_READ) as key:
            winreg.QueryValueEx(key, REG_RUN_NAME)
            return True
    except OSError:
        return False


def set_autostart(enable: bool):
    if os.name != "nt":
        return
    import winreg
    if enable:
        exe = sys.executable
        pythonw = Path(exe).with_name("pythonw.exe")
        runner = str(pythonw) if pythonw.is_file() else exe
        here = Path(__file__).resolve()
        cmd = f'"{runner}" -B "{here}" autostart'
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, REG_RUN_KEY) as key:
            winreg.SetValueEx(key, REG_RUN_NAME, 0, winreg.REG_SZ, cmd)
    else:
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
                winreg.DeleteValue(key, REG_RUN_NAME)
        except OSError:
            pass


def snapshot():
    named = read_named_config()
    try:
        data = request("/api/admin")
    except (OSError, ValueError, TypeError):
        return {
            "running": False,
            "status": "未启动手机朗读连接",
            "autostart": is_autostart_enabled(),
            "has_named_config": named is not None,
            "named_host": named["host"] if named else "",
        }
    control = data["control"]
    return {
        "running": True,
        "enabled": control["enabled"],
        "phone_connected": data["phone_connected"],
        "status": data["phone_status"],
        "pair_url": data["pair_url"],
        "qr_path": qr_image(data["pair_url"]),
        "autostart": is_autostart_enabled(),
        "has_named_config": named is not None,
        "named_host": named["host"] if named else "",
    }


def start():
    if snapshot()["running"]:
        return
    cloudflared = shutil.which("cloudflared")
    here = Path(__file__).resolve()
    for candidate in (here.parents[2] / ".local/tools/cloudflared.exe", here.parents[1] / "cloudflared.exe"):
        if candidate.is_file():
            cloudflared = str(candidate)
            break
    if not cloudflared:
        raise ValueError("未找到手机连接所需的 cloudflared 工具")
    ROOT.mkdir(parents=True, exist_ok=True)

    named = read_named_config()
    cmd = [sys.executable, "-B", str(here.with_name("server.py"))]
    if named:
        cmd.extend(["--named", "--named-host", named["host"], "--named-token", named["token"]])
    else:
        cmd.append("--internet")
    cmd.extend(["--cloudflared", cloudflared])

    with (ROOT / "startup-error.log").open("wb") as errors:
        process = subprocess.Popen(cmd,
                                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=errors,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    for _ in range(80):
        if process.poll() is not None:
            err_log = ""
            try:
                err_log = (ROOT / "startup-error.log").read_text(encoding="utf-8", errors="replace").strip()
            except OSError:
                pass
            if err_log:
                raise RuntimeError(err_log.splitlines()[-1])
            raise RuntimeError("连接工具启动失败，请检查网络或是否已有旧版连接工具运行")
        try:
            if request("/api/admin").get("pair_url", "").startswith("https://"):
                return
        except (OSError, ValueError, TypeError):
            pass
        time.sleep(1)
    raise RuntimeError("建立手机连接超时，请稍后刷新状态")


def start_notes():
    start()
    if "allow_notes" not in request("/api/admin")["control"]:
        request("/api/admin/stop", {})
        for _ in range(20):
            time.sleep(.5)
            if not snapshot()["running"]:
                break
        else:
            raise RuntimeError("旧连接工具尚未退出，请关闭后重试")
        start()
    request("/api/admin/control", {"allow_notes": True})


def output(data):
    for key, value in data.items():
        print(key + " = " + json.dumps(value, ensure_ascii=False))


def save_named_config(data_str):
    """保存命名隧道配置，格式为 JSON 字符串或包含 host 和 token 的文件路径。"""
    if not data_str:
        raise ValueError("缺少配置数据")
    raw = data_str.strip()
    if os.path.isfile(raw):
        raw = Path(raw).read_text(encoding="utf-8")
    parsed = json.loads(raw)
    host = parsed.get("host", "").strip()
    token = parsed.get("token", "").strip()
    if not host:
        raise ValueError("服务地址不能为空")
    if not token or not token.startswith("eyJ"):
        raise ValueError("隧道令牌格式无效，应以 eyJ 开头")
    try:
        decoded = json.loads(base64.b64decode(token + "==").decode("utf-8"))
        if not all(k in decoded for k in ("a", "t", "s")):
            raise ValueError("令牌解出来缺少必要字段")
    except Exception as e:
        raise ValueError(f"隧道令牌解析失败：{e}")
    ROOT.mkdir(parents=True, exist_ok=True)
    cfg_file = ROOT / "named-tunnel.toml"
    content = f'host = "{host}"\ntoken = "{token}"\n'
    temporary = cfg_file.with_suffix(".tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(cfg_file)
    # 如果服务当前在运行，重启以应用固定隧道
    was_running = snapshot()["running"]
    if was_running:
        try:
            request("/api/admin/stop", {})
            time.sleep(1)
        except Exception:
            pass
        start()


def clear_named_config():
    """清除命名隧道配置，恢复临时模式。"""
    cfg_file = ROOT / "named-tunnel.toml"
    if cfg_file.is_file():
        cfg_file.unlink(missing_ok=True)
    was_running = snapshot()["running"]
    if was_running:
        try:
            request("/api/admin/stop", {})
            time.sleep(1)
        except Exception:
            pass
        start()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=(
        "status", "start", "start-notes", "stop", "enable", "pause", "preview",
        "notes-enable", "notes-disable", "autostart", "autostart-enable", "autostart-disable",
        "named-save", "named-clear"
    ))
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    try:
        if args.action == "named-save":
            save_named_config(args.value)
        elif args.action == "named-clear":
            clear_named_config()
        elif args.action == "autostart":
            if read_named_config() is not None:
                start()
            return 0
        elif args.action == "autostart-enable":
            set_autostart(True)
        elif args.action == "autostart-disable":
            set_autostart(False)
        elif args.action == "start":
            start()
        elif args.action == "start-notes":
            start_notes()
        elif args.action == "stop":
            request("/api/admin/stop", {})
            time.sleep(1)
        elif args.action in ("enable", "pause"):
            request("/api/admin/control", {"enabled": args.action == "enable"})
        elif args.action in ("notes-enable", "notes-disable"):
            request("/api/admin/control", {"allow_notes": args.action == "notes-enable"})
            if args.action == "notes-enable" and not request("/api/admin")["control"].get("allow_notes", False):
                raise RuntimeError("旧连接工具不支持笔记，请使用 start-notes 启动新版")
        elif args.action == "preview":
            request("/api/speech", "Hello. English speech is ready.", text=True)
        output(snapshot())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        output({"running": False, "status": str(error), "autostart": is_autostart_enabled(), "has_named_config": read_named_config() is not None})
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
