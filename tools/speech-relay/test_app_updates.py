"""更新包发布、下载鉴权、版本切换与文件边界的隔离测试。"""

import hashlib
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
import zipfile

from app_updates import AppUpdates
from server import SpeechServer, SpeechState


class AppUpdateTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.store = AppUpdates(self.root / "updates")
        self.state = SpeechState("a" * 32, [])
        self.state.updates = self.store
        self.server = SpeechServer(("127.0.0.1", 0), self.state)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.directory.cleanup()

    def apk(self, name="test.apk", abi="arm64-v8a", content=b"synthetic fixture"):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"fixture")
            archive.writestr(f"lib/{abi}/libtest.so", content)
        return path

    def get(self, path, token=None):
        client = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        client.request("GET", path, headers={"Authorization": "Bearer " + token} if token else {})
        response = client.getresponse()
        result = response.status, response.read()
        client.close()
        return result

    def test_authentication_and_exact_download(self):
        apk = self.apk()
        info = self.store.publish(apk, 9, "0.2.0-dev")
        for endpoint in ("/api/update", "/api/update/" + info["sha256"]):
            for token in (None, self.state.publish_token):
                self.assertEqual(self.get(endpoint, token)[0], 401)
        status, body = self.get("/api/update", self.state.token)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["update"], info)
        status, body = self.get("/api/update/" + info["sha256"], self.state.token)
        self.assertEqual(status, 200)
        self.assertEqual(body, apk.read_bytes())
        self.assertEqual(hashlib.sha256(body).hexdigest(), info["sha256"])
        self.assertNotIn(self.state.publish_token.encode(), json.dumps(info).encode())

    def test_empty_corrupt_and_changed_packages(self):
        self.assertEqual(json.loads(self.get("/api/update", self.state.token)[1]), {"update": None})
        old = self.store.publish(self.apk(), 9, "dev")
        new = self.store.publish(self.apk("new.apk", content=b"new"), 10, "dev")
        self.assertEqual(self.get("/api/update/" + old["sha256"], self.state.token)[0], 200)
        self.assertEqual(self.get("/api/update/../../server.py", self.state.token)[0], 404)
        (self.store.root / (new["sha256"] + ".apk")).write_bytes(b"truncated")
        self.assertEqual(self.get("/api/update", self.state.token)[0], 503)

    def test_rejects_emulator_and_invalid_version_without_replacing_latest(self):
        info = self.store.publish(self.apk(), 9, "dev")
        with self.assertRaises(ValueError):
            self.store.publish(self.apk("emulator.apk", abi="x86_64"), 10, "dev")
        with self.assertRaises(ValueError):
            self.store.publish(self.apk(), 0, "dev")
        self.assertEqual(self.store.latest(), info)

    def test_ranges_head_and_resume_after_publication(self):
        apk = self.apk(content=b"0123456789" * 100)
        info = self.store.publish(apk, 10, "dev")
        data = apk.read_bytes()
        endpoint = "/api/update/" + info["sha256"]
        def request(method="GET", value=None, validator=None, token=True):
            client = http.client.HTTPConnection(*self.server.server_address, timeout=3)
            headers = {"Authorization": "Bearer " + self.state.token} if token else {}
            if value is not None:
                headers["Range"] = value
            if validator:
                headers["If-Range"] = validator
            client.request(method, endpoint, headers=headers)
            response = client.getresponse()
            result = response.status, dict(response.getheaders()), response.read()
            client.close()
            return result
        status, headers, body = request("HEAD")
        self.assertEqual((status, body), (200, b""))
        self.assertEqual(headers["Content-Length"], str(len(data)))
        self.assertEqual(headers["Accept-Ranges"], "bytes")
        first = request(value="bytes=0-99", validator=headers["ETag"])
        self.assertEqual(first[0], 206)
        self.assertEqual(first[2], data[:100])
        self.assertEqual(first[1]["Content-Range"], f"bytes 0-99/{len(data)}")
        self.store.publish(self.apk("next.apk", content=b"next"), 11, "next")
        rest = request(value="bytes=100-")
        self.assertEqual(first[2] + rest[2], data)
        self.assertEqual(request(value="bytes=-12")[2], data[-12:])
        for value in ("bytes=999999-", "bytes=8-2", "bytes=-0", "bytes=0-2,4-6", "garbage"):
            self.assertEqual(request(value=value)[0], 416)
        self.assertEqual(request(value="bytes=0-9", validator='"different"')[0], 200)
        self.assertEqual(request(value="bytes=0-9", token=False)[0], 401)


if __name__ == "__main__":
    unittest.main()
