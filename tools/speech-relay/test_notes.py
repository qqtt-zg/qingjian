"""中文笔记工作流的幂等、权限与模型输出边界。"""

import http.client
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest

from notes.jobs import NoteJobs
from notes.provider import parse_meaning, load_config
from server import SpeechServer, SpeechState
from speech_control import SpeechControl
from voice_packs import VoicePacks


class NoteTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.calls = []
        self.gate = threading.Event()
        self.state = SpeechState("a" * 32, [])
        self.state.control = SpeechControl(self.root, VoicePacks(self.root / "packs"))
        def generate(english):
            self.calls.append(english)
            self.gate.wait(3)
            return "控制；调节"
        self.state.notes = NoteJobs(generate)
        self.server = SpeechServer(("127.0.0.1", 0), self.state)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.id = "a1dd1e78-6954-4a0d-93b8-7fc41890a999"

    def tearDown(self):
        self.gate.set()
        self.state.notes.close()
        self.server.shutdown(); self.server.server_close(); self.thread.join(2)
        self.temp.cleanup()

    def request(self, method, path="/api/notes", body=None, token=None):
        client = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        headers = {"Authorization": "Bearer " + (token or self.state.token), "Content-Type": "application/json"}
        client.request(method, path, json.dumps(body) if body is not None else None, headers)
        response = client.getresponse()
        result = response.status, json.loads(response.read())
        client.close()
        return result

    def enable(self):
        self.state.control.change({"allow_notes": True})

    def test_permission_requires_desktop_opt_in(self):
        data = {"request_id": self.id, "english": "control"}
        self.assertEqual(self.request("POST", body=data)[0], 403)
        self.assertEqual(self.request("POST", body=data, token=self.state.publish_token)[0], 401)
        self.request("POST", "/api/device", {"allow_notes": True})
        self.assertFalse(self.state.control.snapshot()["allow_notes"])
        self.assertEqual(self.calls, [])

    def test_idempotency_payload_binding_and_completion(self):
        self.enable()
        data = {"request_id": self.id, "english": "control"}
        self.assertEqual(self.request("POST", body=data)[0], 202)
        self.assertEqual(self.request("POST", body=data)[0], 202)
        self.assertEqual(self.request("POST", body={**data, "english": "manage"})[0], 409)
        self.gate.set()
        for _ in range(100):
            status, result = self.request("GET", "/api/notes/" + self.id)
            if result.get("state") == "succeeded": break
            time.sleep(.01)
        self.assertEqual((status, result["meaning"]), (200, "控制；调节"))
        self.assertEqual(self.calls, ["control"])
        self.assertEqual(self.request("POST", body=data)[0], 200)

    def test_strict_input_and_busy_limit(self):
        self.enable()
        for data in ({}, [], {"request_id": self.id,"english":"控制"},
                     {"request_id": self.id,"english":"a"*101},
                     {"request_id": self.id,"english":"control","url":"https://other.invalid"}):
            self.assertEqual(self.request("POST", body=data)[0], 400)
        self.request("POST", body={"request_id":self.id,"english":"control"})
        self.assertEqual(self.request("POST", body={"request_id":"b1dd1e78-6954-4a0d-93b8-7fc41890a999","english":"help"})[0],429)
        self.assertEqual(self.request("GET", "/api/notes/not-a-uuid")[0],400)

    def test_output_validation(self):
        self.assertEqual(parse_meaning('{"meaning":"控制；调节"}'), "控制；调节")
        for text in ('{"meaning":"control"}', '{"meaning":42}', '{"meaning":"控制","url":"evil"}',
                     '```json\n{"meaning":"控制"}\n```', '{"meaning":"<script>控制</script>"}'):
            with self.assertRaises(ValueError): parse_meaning(text)

    def test_config_is_read_without_executing_dotenv(self):
        path = self.root / "config.toml"
        path.write_text('[predict]\nenabled=true\nbase_url="https://cloud.invalid/v1"\nmodel="model"\napi_key_env="QJ_NOTE_TEST_KEY"\n', encoding="utf-8")
        path.with_name(".env").write_text('QJ_NOTE_TEST_KEY="local-secret"\n', encoding="utf-8")
        config = load_config(path)
        self.assertEqual(config["key"], "local-secret")
        self.assertEqual(config["url"], "https://cloud.invalid/v1/chat/completions")
        path.write_text('[predict]\nenabled=true\nbase_url="http://remote.invalid"\nmodel="model"\napi_key="secret"\n', encoding="utf-8")
        with self.assertRaises(ValueError): load_config(path)


if __name__ == "__main__":
    unittest.main()
