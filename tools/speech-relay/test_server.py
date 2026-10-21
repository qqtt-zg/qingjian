"""接收工具的鉴权、文本边界和最新事件语义测试；不使用真实词条或配对文件。"""

import http.client
import json
import secrets
import socket
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from server import SpeechHandler, SpeechServer, SpeechState


class SpeechServerTests(unittest.TestCase):
    def setUp(self):
        self.token = secrets.token_hex(16)
        self.state = SpeechState(self.token, ["192.0.2.1"])
        self.publish_token = self.state.publish_token
        self.server = SpeechServer(("127.0.0.1", 0), self.state)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method, path, payload=None, token=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        headers = {"Content-Type": "text/plain; charset=utf-8"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        body = payload.encode("utf-8") if isinstance(payload, str) else payload
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        status, content = response.status, response.read()
        connection.close()
        return status, content

    def test_api_requires_pairing(self):
        for path in ("/api/status", "/api/events?after=0"):
            self.assertEqual(self.request("GET", path)[0], 401)
        self.assertEqual(self.request("POST", "/api/speech", "hello")[0], 401)
        self.assertEqual(self.state.sequence, 0)

    def test_repeated_clicks_get_new_ids_and_only_latest_event_is_kept(self):
        for text in ("hello", "hello", "I, me"):
            self.assertEqual(self.request("POST", "/api/speech", text, self.publish_token)[0], 202)
        status, body = self.request("GET", "/api/events?after=0", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"sequence": 3, "text": "I, me"})

    def test_invalid_or_oversized_text_is_rejected(self):
        for payload in (" ", "", "x" * 501, b"\xff\xff"):
            self.assertEqual(self.request("POST", "/api/speech", payload, self.publish_token)[0], 400)
        self.assertEqual(self.state.sequence, 0)

    def test_other_devices_cannot_publish_even_with_pairing_token(self):
        with patch("server.ipaddress.ip_address", return_value=SimpleNamespace(is_loopback=False)):
            self.assertEqual(self.request("POST", "/api/speech", "hello", self.publish_token)[0], 403)
        self.assertEqual(self.state.sequence, 0)

    def test_error_reply_reads_the_request_body_first(self):
        # 错误回复前必须把请求体读干净。带着没读完的请求体关连接，Windows 发 RST 而不是
        # FIN，客户端只看到「连接被中止」，拿不到本该收到的状态码与提示。手机端于是把
        # 「配对地址无效」显示成笼统的「同步失败」。
        # 请求头与请求体分两次发：服务端先只读到请求头，回复的时候请求体还留在内核缓冲里。
        sock = socket.create_connection(self.server.server_address, timeout=5)
        try:
            sock.sendall(
                b"POST /api/speech HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\n"
                b"Content-Length: 5\r\n\r\n")
            time.sleep(0.02)
            sock.sendall(b"hello")
            sock.settimeout(3)
            data = b""
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                data += chunk
        finally:
            sock.close()
        self.assertIn(b"401", data.split(b"\r\n")[0])
        self.assertIn("配对地址无效", data.decode("utf-8", "replace"))
        self.assertEqual(self.state.sequence, 0)

    def test_phone_cannot_publish_through_a_loopback_proxy(self):
        self.assertNotEqual(self.token, self.publish_token)
        self.assertEqual(self.request("POST", "/api/speech", "hello", self.token)[0], 401)
        self.assertEqual(self.state.sequence, 0)
        self.assertEqual(self.request("GET", "/api/status", token=self.publish_token)[0], 401)

    def test_status_advertises_public_url_without_publishing_credentials(self):
        self.state.public_url = "https://test-speech.trycloudflare.com"
        status, body = self.request("GET", "/api/status", token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["public_url"], self.state.public_url)
        self.assertNotIn(self.publish_token.encode(), body)
        self.assertNotIn(self.publish_token.encode(), self.request("GET", "/")[1])

    def test_page_has_no_embedded_token_and_no_directory_listing(self):
        status, body = self.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertNotIn(self.token.encode(), body)
        self.assertEqual(self.request("GET", "/server.py")[0], 404)

    def test_qr_generator_is_local_static_code_without_pairing_credentials(self):
        status, body = self.request("GET", "/qrcode.js")
        self.assertEqual(status, 200)
        self.assertIn(b"var qrcode", body)
        self.assertNotIn(self.token.encode(), body)
        self.assertEqual(self.request("GET", "/vendor/qrcode.js")[0], 404)

    def test_invalid_poll_sequence_does_not_wait(self):
        for query in ("", "?after=-1", "?after=no", "?after=0&after=1"):
            self.assertEqual(self.request("GET", "/api/events" + query, token=self.token)[0], 400)

    def test_disconnected_receiver_does_not_raise_during_reply(self):
        for stage in ("headers", "body"):
            handler = SpeechHandler.__new__(SpeechHandler)
            handler.command = "GET"
            handler.send_response = Mock()
            handler.send_header = Mock()
            handler.end_headers = Mock(side_effect=ConnectionAbortedError() if stage == "headers" else None)
            handler.wfile = SimpleNamespace(write=Mock(side_effect=ConnectionAbortedError()))
            handler.reply(200, {"sequence": 1, "text": "hello"})


if __name__ == "__main__":
    unittest.main()
