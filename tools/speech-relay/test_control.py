"""管理与手机权限分离、暂停、控制唤醒和受保护的管理接口测试。"""

import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from server import SpeechServer, SpeechState
from speech_control import SpeechControl


class ControlTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.control = SpeechControl(self.root)
        self.state = SpeechState("a" * 32, [], control=self.control)
        self.server = SpeechServer(("127.0.0.1", 0), self.state)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.directory.cleanup()

    def request(self, method, path, token=None, data=None, text=False):
        headers = {"Content-Type": "text/plain" if text else "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        body = None if data is None else data.encode() if text else json.dumps(data).encode()
        client = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        client.request(method, path, body=body, headers=headers)
        response = client.getresponse()
        status, raw = response.status, response.read()
        client.close()
        return status, raw

    def test_phone_cannot_control_pc_or_publish_and_pc_cannot_impersonate_phone(self):
        for path in ("/api/admin", "/api/admin/control", "/api/admin/stop"):
            method = "GET" if path == "/api/admin" else "POST"
            self.assertEqual(self.request(method, path, self.state.token, {})[0], 401)
        self.assertEqual(self.request("POST", "/api/device", self.state.publish_token, {})[0], 401)
        with patch("server.ipaddress.ip_address") as address:
            address.return_value.is_loopback = False
            self.assertEqual(self.request("GET", "/api/admin", self.state.publish_token)[0], 403)

    def test_control_changes_wake_poll_and_pause_rejects_speech(self):
        self.assertEqual(self.request("POST", "/api/admin/control", self.state.publish_token, {"enabled": False})[0], 200)
        status, raw = self.request("GET", "/api/events?after=0&control_after=0", self.state.token)
        self.assertEqual(status, 200)
        self.assertFalse(json.loads(raw)["control"]["enabled"])
        self.assertEqual(self.request("POST", "/api/speech", self.state.publish_token, "hello", True)[0], 409)
        self.assertEqual(self.state.sequence, 0)

    def test_device_acknowledgement_exposes_status_but_not_publish_token(self):
        data = {"status": "音色已就绪", "control_revision": 0}
        self.assertEqual(self.request("POST", "/api/device", self.state.token, data)[0], 200)
        status, raw = self.request("GET", "/api/admin", self.state.publish_token)
        self.assertEqual(status, 200)
        state = json.loads(raw)
        self.assertTrue(state["phone_connected"])
        self.assertEqual(state["phone_status"], "音色已就绪")
        self.assertNotIn(self.state.publish_token.encode(), raw)

    def test_legacy_control_json_with_pack_fields_is_silently_tolerated(self):
        legacy = self.root / "legacy"
        legacy.mkdir()
        (legacy / "control.json").write_text(json.dumps({
            "revision": 2, "enabled": True, "pack": "builtin", "speaker": 3, "allow_notes": True
        }), encoding="utf-8")
        control = SpeechControl(legacy)
        snap = control.snapshot()
        self.assertEqual(snap["revision"], 2)
        self.assertTrue(snap["enabled"])
        self.assertTrue(snap["allow_notes"])

    def test_only_admin_can_stop_service(self):
        self.assertEqual(self.request("POST", "/api/admin/stop", self.state.publish_token, {})[0], 200)
        self.assertTrue(self.state.stopping.wait(timeout=1))


if __name__ == "__main__":
    unittest.main()
