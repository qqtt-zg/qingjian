"""青简手机连接工具：朗读转发与本机授权的中文笔记工作流，使用 Python 标准库。"""

import argparse
import hmac
import ipaddress
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
import webbrowser

from tunnel import QuickTunnel
from pairing import PairingStore
from tailscale_link import TailscaleLink
from voice_packs import VoicePacks
from speech_control import SpeechControl
from app_updates import AppUpdates
from update_http import send_package
from notes.jobs import NoteJobs
from notes.provider import generate
from learning_sync import LearningSync


class SpeechState:
    """朗读只保存最新一次点击；笔记任务另有有界临时缓存，不积累输入历史。"""

    def __init__(self, token, addresses, pairing=None, control=None):
        self.token = token
        # 手机仅接收和调用已获本机授权的笔记工作流；发布/管理仍用独立凭证。
        self.publish_token = secrets.token_hex(16)
        self.addresses = addresses
        self.public_url = ""
        self.condition = threading.Condition()
        self.pairing = pairing
        self.sequence = pairing.data["sequence"] if pairing else 0
        if pairing:
            self.publish_token = pairing.data["publish_token"]
        self.text = ""
        self.last_poll = 0.0
        self.control = control
        self.stopping = threading.Event()
        self.updates = None
        self.notes = None
        self.learning = None

    def publish(self, text):
        with self.condition:
            sequence = self.sequence + 1
            if self.pairing:
                self.pairing.save(sequence)
            self.sequence = sequence
            self.text = text
            self.condition.notify_all()
            return self.sequence

    def poll(self, after, control_after=None):
        with self.condition:
            self.last_poll = time.monotonic()
            self.condition.wait_for(lambda: self.sequence > after or (control_after is not None and self.control and self.control.snapshot()["revision"] > control_after), timeout=15)
            result = {"sequence": self.sequence, "text": self.text if self.sequence > after else ""}
            if self.control:
                result["control"] = self.control.snapshot()
            return result


class SpeechServer(ThreadingHTTPServer):
    """带配对状态的朗读接收服务。"""

    daemon_threads = True

    def __init__(self, address, state):
        self.state = state
        super().__init__(address, SpeechHandler)


class SpeechHandler(BaseHTTPRequestHandler):
    """静态接收页、鉴权长轮询和仅回环地址可调用的发布入口。"""

    server_version = "QingjianSpeech/1"
    sys_version = ""

    # 已读掉的请求体字节数；回复前要把剩下的读干净（见 drain_body）。
    _body_read = 0

    def handle_one_request(self):
        self._body_read = 0
        super().handle_one_request()

    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    def log_message(self, *_args):
        # 不记录词条、访问地址或配对令牌。
        pass

    def reply(self, status, data, content_type="application/json; charset=utf-8"):
        self.drain_body()
        body = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
        except (ConnectionError, TimeoutError):
            pass

    def authenticated(self, publish=False):
        supplied = self.headers.get("Authorization", "").encode("utf-8")
        token = self.server.state.publish_token if publish else self.server.state.token
        expected = ("Bearer " + token).encode("utf-8")
        if not hmac.compare_digest(supplied, expected):
            self.reply(401, {"error": "配对地址无效，请重新打开电脑上显示的完整地址。"})
            return False
        return True

    def do_HEAD(self):
        if urlsplit(self.path).path.startswith("/api/update/"):
            self.do_GET()
        else:
            self.reply(405, {"error": "此入口不支持 HEAD"})

    def do_GET(self):
        request = urlsplit(self.path)
        if request.path == "/api/learning/words":
            if not self.authenticated(): return
            try:
                if not self.server.state.learning:
                    self.reply(503, {"error": "学习同步未启用"})
                else:
                    self.reply(200, self.server.state.learning.words())
            except (OSError, ValueError):
                self.reply(503, {"error": "电脑生词来源暂不可用"})
            return
        if request.path.startswith("/api/notes/"):
            if not self.notes_allowed(): return
            try:
                status, data = self.server.state.notes.get(request.path.removeprefix("/api/notes/"))
                self.reply(status, data)
            except ValueError as error:
                self.reply(400, {"error": str(error)})
            return
        if request.path == "/":
            page = Path(__file__).with_name("receiver.html").read_bytes()
            self.reply(200, page, "text/html; charset=utf-8")
            return
        if request.path == "/qrcode.js":
            script = Path(__file__).with_name("vendor").joinpath("qrcode.js").read_bytes()
            self.reply(200, script, "text/javascript; charset=utf-8")
            return
        if request.path == "/api/admin":
            if not self.local_admin():
                return
            control = self.server.state.control
            if not control:
                self.reply(503, {"error": "管理功能未初始化"})
                return
            self.reply(200, {**control.desktop(), "pair_url": self.server.state.public_url + "/#" + self.server.state.token})
            return
        if request.path == "/api/update" or request.path.startswith("/api/update/"):
            if not self.authenticated():
                return
            try:
                updates = self.server.state.updates
                if request.path == "/api/update":
                    info = updates.latest() if updates else None
                    self.reply(200, {"update": info})
                    return
                digest = request.path.removeprefix("/api/update/")
                if not updates:
                    self.reply(404, {"error": "电脑尚未提供安装包"})
                    return
                send_package(self, updates, digest)
            except (OSError, ValueError, TypeError, AttributeError):
                if request.path == "/api/update":
                    self.reply(503, {"error": "更新包暂不可用"})
            return
        if request.path.startswith("/api/packs/"):
            if not self.authenticated():
                return
            try:
                pack_id = request.path.removeprefix("/api/packs/")
                metadata_only = pack_id.endswith("/meta")
                if metadata_only:
                    pack_id = pack_id.removesuffix("/meta")
                info = self.server.state.control.packs.info(pack_id)
                path = self.server.state.control.packs.root / (pack_id + ".zip")
            except (OSError, ValueError, AttributeError):
                self.reply(404, {"error": "音色包不可用"})
                return
            if metadata_only:
                self.reply(200, info)
                return
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Length", str(info["size"]))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                with path.open("rb") as stream:
                    while chunk := stream.read(65536):
                        self.wfile.write(chunk)
            except OSError:
                pass
            return
        if request.path not in ("/api/status", "/api/events"):
            self.reply(404, {"error": "未找到页面。"})
            return
        if not self.authenticated():
            return
        state = self.server.state
        if request.path == "/api/status":
            with state.condition:
                self.reply(200, {"sequence": state.sequence, "addresses": state.addresses,
                                 "public_url": state.public_url,
                                 "persistent": state.pairing is not None,
                                 "connected": time.monotonic() - state.last_poll < 25,
                                 "control": state.control.snapshot() if state.control else None})
            return
        try:
            values = parse_qs(request.query).get("after", [])
            if len(values) != 1:
                raise ValueError
            after = int(values[0])
            if not 0 <= after <= 9007199254740991:
                raise ValueError
            controls = parse_qs(request.query).get("control_after", [])
            control_after = int(controls[0]) if len(controls) == 1 else None
            if control_after is not None and control_after < -1:
                raise ValueError
        except ValueError:
            self.reply(400, {"error": "无效的请求序号。"})
            return
        self.reply(200, state.poll(after, control_after))

    def local_admin(self):
        if not ipaddress.ip_address(self.client_address[0]).is_loopback:
            self.reply(403, {"error": "管理操作仅限本机"})
            return False
        return self.authenticated(publish=True)

    def read_body(self, length):
        """读请求体并记下已读字节，回复前好把剩下的丢掉。"""
        data = self.rfile.read(length)
        self._body_read += length
        return data

    def drain_body(self):
        """把没读干净的请求体丢掉。

        错误回复时往往还没读请求体。带着没读完的请求体关连接，Windows 会发 RST
        而不是 FIN，客户端常常只看到「连接被中止」，拿不到本该收到的状态码与提示，
        手机端于是显示笼统的「同步失败」。读干净再关，响应才回得去。
        """
        remaining = self._body_length() - self._body_read
        if remaining <= 0 or remaining > 1024 * 1024:
            return
        try:
            self.connection.settimeout(5.0)     # 丢弃请求体不该久等
            self.rfile.read(remaining)
        except (OSError, ValueError, TimeoutError):
            return
        finally:
            try:
                self.connection.settimeout(None)
            except OSError:
                pass
        self._body_read += remaining

    def _body_length(self):
        try:
            return int(self.headers.get("Content-Length", "0"))
        except (AttributeError, ValueError):
            return 0

    def json_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if self.headers.get_content_type() != "application/json" or not 1 <= length <= 32768:
            raise ValueError("管理请求格式或长度无效")
        return json.loads(self.read_body(length).decode("utf-8"))

    def do_POST(self):
        path = urlsplit(self.path).path
        if path == "/api/learning/backup":
            if not self.authenticated(): return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if self.headers.get_content_type() != "application/json" or not 1 <= length <= 20 * 1024 * 1024:
                    raise ValueError("备份长度无效")
                if not self.server.state.learning:
                    self.reply(503, {"error": "请更新电脑连接工具"})
                    return
                data = json.loads(self.read_body(length).decode("utf-8"))
                self.reply(200, self.server.state.learning.backup(data))
            except (OSError, ValueError, UnicodeDecodeError):
                self.reply(400, {"error": "学习备份未保存，请检查格式和电脑空间"})
            return
        if path == "/api/notes":
            if not self.notes_allowed(): return
            try:
                status, data = self.server.state.notes.start(self.json_body())
                self.reply(status, data)
            except (ValueError, UnicodeError):
                self.reply(400, {"error": "请提交有效的英文词条和请求编号（最多 100 字）"})
            return
        if path in ("/api/admin/control", "/api/admin/stop", "/api/device"):
            if not (self.authenticated() if path == "/api/device" else self.local_admin()):
                return
            try:
                control = self.server.state.control
                if not control:
                    raise ValueError("管理功能未初始化")
                stopping = False
                if path == "/api/device":
                    control.acknowledge(self.json_body())
                elif path == "/api/admin/stop":
                    stopping = True
                else:
                    control.change(self.json_body())
                    with self.server.state.condition:
                        self.server.state.condition.notify_all()
                try:
                    self.reply(200, {"ok": True})
                finally:
                    # 停止标志放在响应写完之后再置。标志一置，主线程就关服务器；服务端的
                    # 工作线程是守护线程，进程一退它就被杀掉，200 还没送到对端连接就被关掉，
                    # 对端看到的是连接重置而不是成功。
                    if stopping:
                        self.server.state.stopping.set()
            except (ValueError, OSError, UnicodeDecodeError):
                self.reply(400, {"error": "请求设置无效，请检查手机版本或音色文件"})
            return
        if urlsplit(self.path).path != "/api/speech":
            self.reply(404, {"error": "未找到接口。"})
            return
        if not ipaddress.ip_address(self.client_address[0]).is_loopback:
            self.reply(403, {"error": "只能从本机提交朗读。"})
            return
        if not self.authenticated(publish=True):
            return
        if self.server.state.control and not self.server.state.control.snapshot()["enabled"]:
            self.reply(409, {"error": "手机朗读已暂停"})
            return
        try:
            if self.headers.get_content_type() != "text/plain":
                raise ValueError
            length = int(self.headers.get("Content-Length", "0"))
            if not 1 <= length <= 8192:
                raise ValueError
            text = self.read_body(length).decode("utf-8")
            if not text.strip() or len(text) > 500:
                raise ValueError
        except (ValueError, UnicodeDecodeError, TimeoutError, ConnectionError):
            self.reply(400, {"error": "朗读内容须为 1–500 字的文本。"})
            return
        try:
            sequence = self.server.state.publish(text.strip())
        except (OSError, ValueError):
            self.reply(503, {"error": "无法保存事件序号，请检查连接工具的数据目录。"})
            return
        self.reply(202, {"sequence": sequence})

    def notes_allowed(self):
        if not self.authenticated(): return False
        state = self.server.state
        if not state.notes:
            self.reply(503, {"error": "请更新电脑手机连接工具"})
            return False
        if not state.control or not state.control.snapshot().get("allow_notes", False):
            self.reply(403, {"error": "请在电脑连接工具启用中文笔记工作流"})
            return False
        return True


def local_addresses():
    """列出本机局域网 IPv4 地址，不连接外部服务。"""
    addresses = set()
    for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
        address = ipaddress.ip_address(info[4][0])
        if address.is_private and not address.is_loopback and not address.is_link_local:
            addresses.add(str(address))
    return sorted(addresses)


def main():
    parser = argparse.ArgumentParser(description="青简手机朗读连接工具")
    parser.add_argument("--port", type=int, default=21835)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--open", action="store_true", help="在电脑浏览器打开配对页")
    parser.add_argument("--state-file", type=Path)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--internet", action="store_true", help="创建临时 HTTPS 手机连接地址")
    modes.add_argument("--tailscale", action="store_true", help="使用 Tailscale 固定 HTTPS 地址和持久配对")
    parser.add_argument("--tailscale-exe", default="tailscale")
    parser.add_argument("--tailscale-port", type=int, default=8443)
    parser.add_argument("--reset-pairing", action="store_true", help="主动撤销已有 Tailscale 配对，生成新凭证")
    parser.add_argument("--cloudflared", default="cloudflared", help="官方 cloudflared 可执行文件路径")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("端口须在 1–65535 之间")
    if not 1 <= args.tailscale_port <= 65535 or (args.tailscale and args.tailscale_port == args.port):
        parser.error("Tailscale HTTPS 端口须有效且不同于本机 HTTP 端口")
    if args.reset_pairing and not args.tailscale:
        parser.error("--reset-pairing 仅用于 --tailscale 模式")
    state = SpeechState(secrets.token_hex(16), [] if args.internet or args.tailscale else local_addresses())
    # 公网模式只监听本机，由隧道转发手机请求，不开放局域网入站端口。
    bind = "127.0.0.1" if args.internet or args.tailscale else args.bind
    try:
        server = SpeechServer((bind, args.port), state)
    except OSError:
        parser.exit(1, "无法启动：端口可能已占用，请关闭旧的连接工具后重试。\n")
    root = Path(os.environ.get("APPDATA", str(Path.home()))) / "Qingjian" / "speech"
    state_file = args.state_file or root / "connection.toml"
    state.control = SpeechControl(state_file.parent, VoicePacks(state_file.parent / "packs"))
    state.updates = AppUpdates(state_file.parent / "updates")
    state.learning = LearningSync(state_file.parent / "learning-backups", root.parent / "user-vocab.tsv")
    state.notes = NoteJobs(generate)
    connection_settings = f'port = {args.port}\ntoken = "{state.publish_token}"\n'
    tunnel = QuickTunnel(args.cloudflared, args.port) if args.internet else None
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()
    exit_code = 0
    try:
        if args.tailscale:
            link = TailscaleLink(args.tailscale_exe, args.port, args.tailscale_port)
            state.public_url = link.start()
            state.pairing = PairingStore(state_file.with_name("pairing.json"), args.reset_pairing)
            state.token = state.pairing.data["receive_token"]
            state.publish_token = state.pairing.data["publish_token"]
            state.sequence = state.pairing.data["sequence"]
            connection_settings = f'port = {args.port}\ntoken = "{state.publish_token}"\n'
        if tunnel:
            print("正在建立临时公网连接…", flush=True)
            state.public_url = tunnel.start()
        state_file.parent.mkdir(parents=True, exist_ok=True)
        temporary = state_file.with_suffix(".tmp")
        temporary.write_text(connection_settings, encoding="utf-8")
        temporary.replace(state_file)
        desktop_url = f"http://127.0.0.1:{args.port}/#{state.token}"
        print("青简手机朗读工具已启动。保持此窗口运行，按 Ctrl+C 关闭。", flush=True)
        if tunnel or args.tailscale:
            print("手机连接地址（Wi-Fi 或移动网络均可）：", flush=True)
            print(f"{state.public_url}/#{state.token}", flush=True)
            if args.tailscale:
                print("电脑、手机须连接同一 Tailscale 网络；此地址重启后保留，主动重置配对除外。", flush=True)
        else:
            print("在电脑浏览器打开配对页，再将其中的地址发送到手机：", flush=True)
            print(desktop_url, flush=True)
        if args.open:
            webbrowser.open(desktop_url)
        while server_thread.is_alive() and not state.stopping.is_set():
            if tunnel and tunnel.process.poll() is not None:
                raise RuntimeError("公网连接工具已退出，请重新启动以获得新的配对地址。")
            time.sleep(0.5)
    except KeyboardInterrupt:
        pass
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr, flush=True)
        exit_code = 1
    finally:
        state.notes.close()
        if tunnel:
            tunnel.close()
        server.shutdown()
        server.server_close()
        server_thread.join(timeout=2)
        try:
            if state_file.read_text(encoding="utf-8") == connection_settings:
                state_file.unlink()
        except (OSError, ValueError):
            pass
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
