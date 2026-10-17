"""原生设置页的后台命令：只连接固定回环地址，以 TOML 返回界面状态。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zlib

from voice_packs import VoicePacks

ROOT = Path(os.environ.get("APPDATA", str(Path.home()))) / "Qingjian/speech"


def settings():
    import re
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


def snapshot():
    try:
        data = request("/api/admin")
    except (OSError, ValueError, TypeError):
        return {"running": False, "status": "未启动手机朗读连接", "choices": []}
    control = data["control"]
    return {"running": True, "enabled": control["enabled"], "phone_connected": data["phone_connected"], "status": data["phone_status"],
            "pair_url": data["pair_url"], "qr_path": qr_image(data["pair_url"]), "selected": f"{control['pack']}:{control['speaker']}", "choices": data["choices"]}


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
    with (ROOT / "startup-error.log").open("wb") as errors:
        process = subprocess.Popen([sys.executable, "-B", str(here.with_name("server.py")), "--internet", "--cloudflared", cloudflared],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=errors,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    for _ in range(80):
        if process.poll() is not None:
            raise RuntimeError("连接工具启动失败，请检查网络或是否已有旧版连接工具运行")
        try:
            if request("/api/admin").get("pair_url", "").startswith("https://"):
                return
        except (OSError, ValueError, TypeError):
            pass
        time.sleep(1)
    raise RuntimeError("建立手机连接超时，请稍后刷新状态")


def output(data):
    for key, value in data.items():
        if key != "choices":
            print(key + " = " + json.dumps(value, ensure_ascii=False))
    for choice in data.get("choices", []):
        print("[[choices]]")
        print("key = " + json.dumps(choice["key"], ensure_ascii=False))
        print("label = " + json.dumps(choice["label"], ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("status", "start", "stop", "enable", "pause", "select", "preview", "import"))
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    try:
        if args.action == "start":
            start()
        elif args.action == "stop":
            request("/api/admin/stop", {})
            time.sleep(1)
        elif args.action in ("enable", "pause"):
            request("/api/admin/control", {"enabled": args.action == "enable"})
        elif args.action == "select":
            pack, _, speaker = (args.value or "").rpartition(":")
            request("/api/admin/control", {"pack": pack, "speaker": int(speaker)})
        elif args.action == "preview":
            request("/api/speech", "Hello. English speech is ready.", text=True)
        elif args.action == "import":
            info = VoicePacks(ROOT / "packs").add(args.value or "")
            request("/api/admin/control", {"pack": info["id"], "speaker": 0})
        output(snapshot())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        output({"running": False, "status": str(error), "choices": []})
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
