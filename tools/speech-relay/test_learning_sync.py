"""隔离目录验证生词来源、备份边界与鉴权，不访问真实用户资料。"""
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.request
import urllib.error

from learning_sync import LearningSync
from server import SpeechServer, SpeechState


class LearningSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.sync = LearningSync(self.root / "backups", self.root / "words.tsv")

    def tearDown(self):
        self.temp.cleanup()

    def test_vocabulary_old_and_new_columns(self):
        self.sync.vocabulary.write_text("# header\nen\tcontrol\t4\t2\t1\t100\t200\t1\t控制\tcontrol the volume\nen\tready\t2\t0\t0\t100\t300\nzh\t坏行\t4\t4\t4\t4\t4\nen\tbad\tx\t0\t0\t0\t0\n", encoding="utf-8")
        rows = self.sync.words()["words"]
        self.assertEqual([r["english"] for r in rows], ["control", "ready"])
        self.assertEqual(rows[0]["meaning"], "控制")
        self.assertTrue(rows[0]["desktop_known"])
        self.assertEqual(rows[1]["meaning"], "")

    def test_missing_vocabulary_is_explicit(self):
        self.assertEqual(self.sync.words(), {"words": [], "available": False})

    def test_readable_latest_and_previous_backup(self):
        first = {"schema": 1, "created": 1, "tables": {"learning_events": [{"word": "control"}]}}
        self.sync.backup(first)
        second = {**first, "created": 2}
        self.sync.backup(second)
        self.assertEqual(json.loads((self.sync.root / "phone-learning-latest.json").read_text(encoding="utf-8")), second)
        self.assertEqual(json.loads((self.sync.root / "phone-learning-previous.json").read_text(encoding="utf-8")), first)
        self.assertEqual(len(list(self.sync.root.glob("*.json"))), 2)

    def test_backup_rejects_settings_and_bad_rows(self):
        for tables in ({"credentials": []}, {"words": "oops"}, {"words": ["oops"]}):
            with self.assertRaises(ValueError):
                self.sync.backup({"schema": 1, "created": 0, "tables": tables})
        self.assertFalse(self.sync.root.exists())

    def test_pair_authentication_and_body_limits(self):
        state = SpeechState("receive-token", [])
        state.learning = self.sync
        server = SpeechServer(("127.0.0.1", 0), state)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        base = "http://127.0.0.1:" + str(server.server_port)
        try:
            with self.assertRaises(urllib.error.HTTPError) as error:
                opener.open(base + "/api/learning/words")
            self.assertEqual(error.exception.code, 401)
            request = urllib.request.Request(base + "/api/learning/words", headers={"Authorization": "Bearer receive-token"})
            self.assertFalse(json.load(opener.open(request))["available"])
            request = urllib.request.Request(base + "/api/learning/backup", b'{}', {"Authorization": "Bearer receive-token", "Content-Type": "text/plain"})
            with self.assertRaises(urllib.error.HTTPError) as error:
                opener.open(request)
            self.assertEqual(error.exception.code, 400)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
